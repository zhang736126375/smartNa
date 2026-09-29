package com.bingo.smartna.collector.device.ego;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Plays PCM audio out of the EgoDevice's <b>physical speaker</b> using the
 * standard Android audio path — no libusb, no SDK/native changes.
 *
 * <p>Why this works: the EgoDevice exposes its microphone and speaker on two
 * <em>separate</em> USB AudioStreaming interfaces. The Orbbec SDK only detaches
 * and claims the microphone interface for capture, so the kernel
 * {@code snd-usb-audio} driver keeps the speaker interface and Android continues
 * to expose it as a normal {@link AudioDeviceInfo#TYPE_USB_DEVICE} output. This
 * player finds that output device, pins an {@link AudioTrack} to it with
 * {@link AudioTrack#setPreferredDevice}, and streams PCM to it — concurrently
 * with the SDK's microphone capture and camera streams.
 *
 * <p>The device speaker is fixed at 16000 Hz / mono / 16-bit by firmware, but the
 * Android audio HAL resamples transparently, so callers may feed any PCM rate the
 * {@link AudioTrack} is built for. This class defaults to the native
 * 16000/mono/16-bit to avoid an extra resample.
 *
 * <p>Threading: {@link #start}/{@link #stop} are expected on one control thread
 * (e.g. the UI thread). {@link #write} is non-blocking and thread-safe — it
 * copies the PCM into a pooled buffer and enqueues it; a single pump thread does
 * the blocking {@link AudioTrack#write}, which paces playback to real time. If
 * the pump falls behind, the bounded queue drops (and counts) the chunk instead
 * of blocking the caller. An empty queue simply underruns to silence — the HAL
 * keeps the stream alive, so no manual silence-fill is needed.
 */
public class EgoSpeakerPlayer {

    private static final String TAG = "EgoSpeakerPlayer";

    // EgoDevice USB IDs. VID is shared; PID covers EGO and EGOPro.
    private static final int EGO_VID = 0x2BC5;
    private static final int[] EGO_PIDS = { 0x1201, 0x1204 };

    // Native speaker format (fixed by firmware). The HAL resamples if the track
    // uses a different rate, so these are defaults, not hard requirements.
    public static final int DEFAULT_SAMPLE_RATE = 16000;
    public static final int DEFAULT_CHANNELS = 1;
    public static final int DEFAULT_BITS_PER_SAMPLE = 16;

    // Bounded PCM hand-off queue. Voice chunks are small; a few seconds of
    // headroom is plenty. Overrun drops the newest chunk rather than blocking.
    private static final int QUEUE_CAPACITY = 200;
    private static final int JOIN_TIMEOUT_MS = 2000;

    /** A reusable PCM chunk buffer pooled via the free list. */
    private static final class Buffer {
        byte[] data;
        int length;
    }

    private final Context mContext;
    private final int mSampleRate;
    private final int mChannels;
    private final int mBitsPerSample;

    private final LinkedBlockingQueue<Buffer> mQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final LinkedBlockingQueue<Buffer> mPool = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    private AudioTrack mTrack;
    private Thread mPumpThread;
    private volatile boolean mRunning;
    private volatile long mDroppedChunks;
    private String mRoutedDeviceName = "";

    public EgoSpeakerPlayer(Context context) {
        this(context, DEFAULT_SAMPLE_RATE, DEFAULT_CHANNELS, DEFAULT_BITS_PER_SAMPLE);
    }

    public EgoSpeakerPlayer(Context context, int sampleRate, int channels, int bitsPerSample) {
        mContext = context.getApplicationContext();
        mSampleRate = sampleRate;
        mChannels = channels;
        mBitsPerSample = bitsPerSample;
    }

    /**
     * Locate the Ego speaker output, build an {@link AudioTrack} pinned to it and
     * start the pump thread. Call before {@link #write}.
     *
     * @return true if playback started and was routed to a USB output device;
     *         false if no Ego/USB output device was found or the track could not
     *         be created (nothing is played).
     */
    public synchronized boolean start() {
        if (mRunning) {
            return true;
        }
        if (mChannels != 1 && mChannels != 2) {
            Log.e(TAG, "unsupported channel count: " + mChannels);
            return false;
        }
        if (mBitsPerSample != 16) {
            Log.e(TAG, "unsupported bit depth: " + mBitsPerSample + " (only 16-bit PCM)");
            return false;
        }

        AudioDeviceInfo egoOut = findEgoOutputDevice();
        if (egoOut == null) {
            Log.w(TAG, "no USB audio output device found — is the Ego connected and enumerated?");
            return false;
        }

        int channelMask = mChannels == 1
                ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int minBuf = AudioTrack.getMinBufferSize(mSampleRate, channelMask,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            Log.e(TAG, "getMinBufferSize failed: " + minBuf);
            return false;
        }
        // A couple of min-buffers of headroom keeps the stream from underrunning
        // between write() calls without adding much latency.
        int trackBuf = minBuf * 4;

        try {
            mTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(mSampleRate)
                            .setChannelMask(channelMask)
                            .build())
                    .setBufferSizeInBytes(trackBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (Exception e) {
            Log.e(TAG, "AudioTrack build failed: " + e.getMessage());
            mTrack = null;
            return false;
        }

        // Pin output to the Ego so the PCM cannot leak to the phone speaker or a
        // Bluetooth route. setPreferredDevice only requests — verify via routing.
        boolean pinned = mTrack.setPreferredDevice(egoOut);
        mRoutedDeviceName = String.valueOf(egoOut.getProductName());
        Log.i(TAG, "routing to USB output: '" + mRoutedDeviceName + "' id=" + egoOut.getId()
                + " (setPreferredDevice=" + pinned + ")");

        mQueue.clear();
        mDroppedChunks = 0;
        mRunning = true;
        // Max out the track's own gain; loudness is then governed by the synthesized
        // amplitude and the system media-stream volume on the USB route.
        try {
            mTrack.setVolume(AudioTrack.getMaxVolume());
        } catch (Exception e) {
            Log.w(TAG, "setVolume failed: " + e.getMessage());
        }
        try {
            mTrack.play();
        } catch (Exception e) {
            Log.e(TAG, "AudioTrack play failed: " + e.getMessage());
            mRunning = false;
            mTrack.release();
            mTrack = null;
            return false;
        }
        mPumpThread = new Thread(this::pumpLoop, "EgoSpeakerPump");
        mPumpThread.start();
        return true;
    }

    /**
     * Enqueue one PCM chunk for playback. Non-blocking: copies {@code length}
     * bytes into a pooled buffer and returns at once. Drops (and counts) the
     * chunk if the queue is full rather than blocking the caller.
     *
     * <p><b>The bytes must be raw PCM matching the format this player was built
     * with</b> (see the constructor / {@link #DEFAULT_SAMPLE_RATE} etc.). This is
     * <em>not</em> a file decoder — do not pass WAV/MP3/AAC or any container or
     * compressed data; strip WAV headers first and decode compressed formats to
     * PCM before calling. Required layout:
     * <ul>
     *   <li><b>Encoding</b>: 16-bit <em>signed</em> PCM, <b>little-endian</b>
     *       (low byte first). Not 8-bit, 24/32-bit, or float.</li>
     *   <li><b>Sample rate</b>: must equal the rate this player was built with
     *       ({@code mSampleRate}, default {@value #DEFAULT_SAMPLE_RATE}). Feeding a
     *       different rate is not rejected but plays back <em>pitch-shifted</em>.
     *       To play a 48 kHz source, construct the player at 48000 and let the HAL
     *       resample down to the device's native 16 kHz.</li>
     *   <li><b>Channels</b>: must equal {@code mChannels} (default mono). Stereo
     *       samples are interleaved L,R,L,R…</li>
     *   <li><b>{@code length}</b>: a whole number of frames — a multiple of
     *       {@code channels * 2} bytes (2 for mono 16-bit). A non-frame-aligned
     *       length truncates the trailing partial frame at the {@link AudioTrack}.</li>
     * </ul>
     *
     * @param pcm    little-endian signed 16-bit PCM matching the configured format
     * @param length valid byte count in {@code pcm}; should be frame-aligned
     */
    public void write(byte[] pcm, int length) {
        write(pcm, 0, length);
    }

    /**
     * Offset variant of {@link #write(byte[], int)}: enqueue {@code length} bytes
     * starting at {@code offset}. Same format requirements apply. Lets a caller
     * feed a large PCM buffer in slices without allocating a sub-array per chunk.
     *
     * @param pcm    little-endian signed 16-bit PCM matching the configured format
     * @param offset start index of the valid data in {@code pcm}
     * @param length valid byte count from {@code offset}; should be frame-aligned
     */
    public void write(byte[] pcm, int offset, int length) {
        if (!mRunning || pcm == null || length <= 0) {
            return;
        }
        if (offset < 0 || offset + length > pcm.length) {
            Log.e(TAG, "write out of bounds: offset=" + offset + " length=" + length
                    + " capacity=" + pcm.length);
            return;
        }
        Buffer buf = mPool.poll();
        if (buf == null || buf.data == null || buf.data.length < length) {
            buf = new Buffer();
            buf.data = new byte[length];
        }
        System.arraycopy(pcm, offset, buf.data, 0, length);
        buf.length = length;
        if (!mQueue.offer(buf)) {
            mDroppedChunks++;
            mPool.offer(buf);  // queue rejected it — return the buffer to the pool
        }
    }

    /**
     * Convenience for validation: synthesize a sine tone and stream it to the
     * speaker. Returns immediately; the tone plays asynchronously via the pump
     * thread. Must be called after {@link #start}.
     *
     * @param freqHz     tone frequency (e.g. 440)
     * @param durationMs tone length in milliseconds
     */
    public void playTone(int freqHz, int durationMs) {
        if (!mRunning) {
            Log.w(TAG, "playTone called before start()");
            return;
        }
        final double amplitude = 0.8;  // near full scale; keep some headroom vs clipping
        final double step = 2.0 * Math.PI * freqHz / mSampleRate;
        // Emit in ~100 ms chunks so stop() can interrupt mid-tone and the pooled
        // buffers stay small.
        int chunkFrames = mSampleRate / 10;
        byte[] chunk = new byte[chunkFrames * mChannels * 2];
        int totalFrames = (int) ((long) mSampleRate * durationMs / 1000);
        long phaseIdx = 0;
        int emitted = 0;
        while (emitted < totalFrames && mRunning) {
            int frames = Math.min(chunkFrames, totalFrames - emitted);
            int b = 0;
            for (int i = 0; i < frames; i++) {
                short s = (short) (Math.sin(step * phaseIdx) * amplitude * Short.MAX_VALUE);
                phaseIdx++;
                for (int c = 0; c < mChannels; c++) {
                    chunk[b++] = (byte) (s & 0xFF);
                    chunk[b++] = (byte) ((s >> 8) & 0xFF);
                }
            }
            write(chunk, b);
            emitted += frames;
        }
    }

    private void pumpLoop() {
        // Drain the queue while running; on stop the loop exits promptly (poll
        // times out) so a half-second of queued audio is not force-played.
        while (mRunning) {
            try {
                Buffer buf = mQueue.poll(100, TimeUnit.MILLISECONDS);
                if (buf == null) {
                    continue;  // underrun — HAL emits silence, stream stays alive
                }
                try {
                    // Blocking write in MODE_STREAM paces to the device clock.
                    int written = mTrack.write(buf.data, 0, buf.length);
                    if (written < 0) {
                        Log.e(TAG, "AudioTrack.write error: " + written);
                    }
                } finally {
                    mPool.offer(buf);  // recycle regardless of write outcome
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                Log.e(TAG, "pumpLoop: " + e.getMessage());
            }
        }
        if (mDroppedChunks > 0) {
            Log.w(TAG, "dropped " + mDroppedChunks + " chunks (pump overrun)");
        }
    }

    /** Stop playback and release the track. Idempotent. */
    public synchronized void stop() {
        if (!mRunning && mPumpThread == null && mTrack == null) {
            return;
        }
        mRunning = false;
        Thread t = mPumpThread;
        mPumpThread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.e(TAG, "stop join interrupted");
            }
        }
        if (mTrack != null) {
            try {
                mTrack.stop();
            } catch (Exception e) {
                Log.w(TAG, "AudioTrack stop: " + e.getMessage());
            }
            mTrack.release();
            mTrack = null;
        }
        mQueue.clear();
    }

    public boolean isPlaying() {
        return mRunning;
    }

    /** Product name of the USB output the last {@link #start} routed to, for UI. */
    public String getRoutedDeviceName() {
        return mRoutedDeviceName;
    }

    /**
     * Find the Ego speaker among the current audio output devices. Prefers a USB
     * output whose product name matches the connected Ego {@link UsbDevice}
     * (matched by VID/PID); falls back to the first USB output device so the
     * player still works when the name cannot be cross-referenced.
     */
    public AudioDeviceInfo findEgoOutputDevice() {
        AudioManager am = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            return null;
        }
        String egoName = findEgoUsbProductName();
        AudioDeviceInfo firstUsb = null;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (!isUsbOutput(d.getType())) {
                continue;
            }
            if (firstUsb == null) {
                firstUsb = d;
            }
            CharSequence name = d.getProductName();
            if (egoName != null && name != null && egoName.contentEquals(name)) {
                return d;  // exact Ego match
            }
        }
        if (egoName != null && firstUsb != null) {
            Log.w(TAG, "Ego USB product '" + egoName + "' not matched by name; "
                    + "falling back to first USB output '" + firstUsb.getProductName() + "'");
        }
        return firstUsb;
    }

    private static boolean isUsbOutput(int type) {
        return type == AudioDeviceInfo.TYPE_USB_DEVICE
                || type == AudioDeviceInfo.TYPE_USB_HEADSET
                || type == AudioDeviceInfo.TYPE_USB_ACCESSORY;
    }

    /** Product-name string of the connected Ego USB device, or null if absent. */
    private String findEgoUsbProductName() {
        try {
            UsbManager usb = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);
            if (usb == null) {
                return null;
            }
            for (Map.Entry<String, UsbDevice> e : usb.getDeviceList().entrySet()) {
                UsbDevice dev = e.getValue();
                if (dev.getVendorId() != EGO_VID) {
                    continue;
                }
                for (int pid : EGO_PIDS) {
                    if (dev.getProductId() == pid) {
                        return dev.getProductName();  // may be null on some ROMs
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "findEgoUsbProductName failed: " + e.getMessage());
        }
        return null;
    }
}
