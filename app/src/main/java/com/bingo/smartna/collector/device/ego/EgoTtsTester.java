package com.bingo.smartna.collector.device.ego;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;

import com.orbbec.obsensor.Device;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * On-device speech test source for {@link EgoSpeakerPlayer}: renders a short
 * phrase to PCM with the system {@link TextToSpeech} engine and streams it out
 * the Ego speaker — <b>no external audio file needed</b>. Use it to validate the
 * real-voice path (synthesized PCM → {@link EgoSpeakerPlayer#write} → Ego USB
 * speaker) end-to-end.
 *
 * <p>{@code TextToSpeech} cannot emit a live PCM stream, so it synthesizes to a
 * WAV file first. This class parses that WAV (the engine chooses its own sample
 * rate, commonly 22050/24000 Hz), converts the USB test path to 48 kHz PCM,
 * feeds it through {@link EgoSpeakerPlayer}, and stops the player once playback
 * time has elapsed.
 *
 * <p>Lifecycle: {@link #init} once (async engine bring-up), {@link #speak} per
 * test, {@link #stopPlayback} on pause, {@link #release} on teardown. Engine and
 * feeder callbacks run on binder/worker threads; the {@link Callback} is invoked
 * on one of those, so marshal to the UI thread yourself.
 *
 * <p>Note (API 30+ package visibility): resolving the system TTS engine requires
 * a {@code <queries>} entry for {@code android.intent.action.TTS_SERVICE} in the
 * manifest; without it {@link #init} may fail with no engine found.
 */
public class EgoTtsTester {

    private static final String TAG = "EgoTtsTester";
    private static final String USB_UTTERANCE_ID = "ego_tts_test_usb";
    private static final String NETWORK_UTTERANCE_ID_PREFIX = "ego_tts_test_network_";
    private static final String WAV_NAME = "ego_tts.wav";
    // USB voice test intentionally keeps a 48 kHz source for validating the
    // AudioTrack-to-UAC path independently from network playback.
    private static final String USB_48K_WAV_NAME = "ego_tts_usb_48k.wav";
    private static final int USB_TEST_SAMPLE_RATE = 48000;
    private static final String NETWORK_SOURCE_WAV_NAME = "ego_tts_network_source.wav";
    private static final String NETWORK_WAV_NAME = "ego_tts_network_16k.wav";
    private static final int NETWORK_SAMPLE_RATE = 16000;
    private static final int FEED_CHUNK_BYTES = 4096;
    // Extra wait after the computed audio duration so the queue fully drains
    // before the player is torn down.
    private static final long DRAIN_MARGIN_MS = 400;

    // Test phrases. Chinese is used only when an offline Chinese voice is present;
    // otherwise we fall back to English, whose offline voice ships on most devices,
    // so the audio path can still be validated without network.
    private static final String PHRASE_ZH = "扬声器语音测试，一二三四五";
    private static final String PHRASE_EN = "Speaker voice test. One two three four five.";

    /** Result of a {@link #speak} request; delivered on a worker/binder thread. */
    public interface Callback {
        void onResult(boolean ok, String message);
    }

    /** Result of rendering and sending the same test phrase through a network Ego. */
    public interface NetworkCallback {
        void onResult(boolean ok, String message);
    }

    private final Context mContext;
    private TextToSpeech mTts;
    private volatile boolean mReady;
    // True once an offline Chinese voice has been selected; drives phrase choice.
    private volatile boolean mPreferChinese;

    private EgoSpeakerPlayer mPlayer;
    private Callback mCallback;
    private Callback mPendingCallback;
    private Device mNetworkDevice;
    private NetworkCallback mNetworkCallback;
    // Only callbacks for this request may start playback or report a result.
    private String mExpectedUtteranceId;
    // Invalidates an in-flight synthesis/conversion when the caller stops it or
    // starts a different playback mode. This prevents a late TTS callback from
    // restarting network audio after stopAudioPlayback() has returned.
    private long mNetworkPlaybackGeneration;

    public EgoTtsTester(Context context) {
        mContext = context.getApplicationContext();
    }

    /**
     * Bring up the TTS engine. Async — {@link #isReady} flips true once the engine
     * has initialized. Safe to call more than once (subsequent calls are no-ops).
     */
    public synchronized void init() {
        if (mTts != null) {
            return;
        }
        mTts = new TextToSpeech(mContext, status -> onEngineInit(status));
    }

    private void onEngineInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS init failed: " + status);
            mReady = false;
            return;
        }
        int r = mTts.setLanguage(Locale.CHINESE);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Chinese TTS unavailable; using default locale");
            mTts.setLanguage(Locale.getDefault());
        }
        // Prefer an offline voice so synthesis does not depend on the network
        // (online voices fail with ERROR_NETWORK_TIMEOUT / -7 when offline).
        selectOfflineVoice();
        mTts.setOnUtteranceProgressListener(mProgress);
        mReady = true;
        Log.i(TAG, "TTS ready");

        // Fire any request made before the engine finished initializing.
        Callback cb;
        synchronized (this) {
            cb = mPendingCallback;
            mPendingCallback = null;
        }
        if (cb != null) {
            speak(cb);
        }
    }

    /**
     * Pick a voice that does not require a network connection. Prefers an offline
     * Chinese voice (keeps the Chinese phrase); otherwise falls back to any offline
     * voice (typically English) and switches the phrase to English.
     */
    private void selectOfflineVoice() {
        try {
            Set<Voice> voices = mTts.getVoices();
            if (voices == null || voices.isEmpty()) {
                Log.w(TAG, "engine reports no voices; synthesis may need network");
                return;
            }
            Voice zhOffline = null;
            Voice anyOffline = null;
            for (Voice v : voices) {
                Set<String> features = v.getFeatures();
                if (features != null
                        && features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) {
                    continue;  // voice data not downloaded
                }
                if (v.isNetworkConnectionRequired()) {
                    continue;  // online-only — the source of the -7 timeout
                }
                String lang = v.getLocale() != null ? v.getLocale().getLanguage() : "";
                if ("zh".equals(lang) && zhOffline == null) {
                    zhOffline = v;
                }
                if (anyOffline == null) {
                    anyOffline = v;
                }
            }
            Voice pick = zhOffline != null ? zhOffline : anyOffline;
            if (pick == null) {
                Log.w(TAG, "no offline voice installed; synthesis may fail without network");
                return;
            }
            int r = mTts.setVoice(pick);
            mPreferChinese = "zh".equals(pick.getLocale().getLanguage());
            Log.i(TAG, "selected offline voice '" + pick.getName() + "' locale="
                    + pick.getLocale() + " setVoice=" + r
                    + " -> phrase=" + (mPreferChinese ? "zh" : "en"));
        } catch (Exception e) {
            Log.w(TAG, "selectOfflineVoice failed: " + e.getMessage());
        }
    }

    public boolean isReady() {
        return mReady;
    }

    /**
     * Synthesize the built-in test phrase and play it out the Ego speaker. Async —
     * the outcome arrives via {@code cb}. If called before the engine is ready the
     * request is deferred and runs automatically once {@link #init} completes. The
     * phrase is Chinese when an offline Chinese voice is available, else English.
     */
    public synchronized void speak(Callback cb) {
        if (mTts == null) {
            init();
        }
        if (!mReady) {
            mPendingCallback = cb;
            Log.i(TAG, "engine not ready; deferring speak()");
            return;
        }
        mCallback = cb;
        mNetworkPlaybackGeneration++;
        mNetworkDevice = null;
        mNetworkCallback = null;
        mExpectedUtteranceId = USB_UTTERANCE_ID;
        String phrase = mPreferChinese ? PHRASE_ZH : PHRASE_EN;
        File wav = new File(mContext.getCacheDir(), WAV_NAME);
        int q = mTts.synthesizeToFile(phrase, new Bundle(), wav, USB_UTTERANCE_ID);
        if (q != TextToSpeech.SUCCESS) {
            mExpectedUtteranceId = null;
            deliver(cb, false, "TTS 合成入队失败 (" + q + ")");
        }
    }

    /**
     * Synthesizes the same built-in test phrase used by {@link #speak(Callback)},
     * converts it to the 16 kHz mono PCM WAV format required by network Ego
     * playback, then sends it through {@link Device#playAudioFile(String)}.
     * This method is asynchronous after synthesis is queued.
     */
    public synchronized void speakToNetwork(Device device, NetworkCallback cb) {
        if (device == null) {
            deliverNetwork(cb, false, "网络 Ego 设备为空");
            return;
        }
        if (mTts == null) {
            init();
        }
        if (!mReady) {
            deliverNetwork(cb, false, "TTS 尚未初始化完成，请稍后重试");
            return;
        }
        mCallback = null;
        mNetworkPlaybackGeneration++;
        mNetworkDevice = device;
        mNetworkCallback = cb;
        String utteranceId = NETWORK_UTTERANCE_ID_PREFIX + mNetworkPlaybackGeneration;
        mExpectedUtteranceId = utteranceId;
        String phrase = mPreferChinese ? PHRASE_ZH : PHRASE_EN;
        File wav = new File(mContext.getCacheDir(), NETWORK_SOURCE_WAV_NAME);
        int q = mTts.synthesizeToFile(phrase, new Bundle(), wav, utteranceId);
        if (q != TextToSpeech.SUCCESS) {
            mNetworkDevice = null;
            mNetworkCallback = null;
            mExpectedUtteranceId = null;
            deliverNetwork(cb, false, "TTS 合成入队失败 (" + q + ")");
        }
    }

    /**
     * Plays the synthesized test phrase through the SDK device audio interface.
     * The generated WAV is 16 kHz, mono, 16-bit PCM.
     */
    public void speakToSdkDevice(Device device, NetworkCallback cb) {
        speakToNetwork(device, cb);
    }

    private final UtteranceProgressListener mProgress = new UtteranceProgressListener() {
        @Override
        public void onStart(String utteranceId) { }

        @Override
        public void onDone(String utteranceId) {
            Device networkDevice;
            NetworkCallback networkCallback;
            long networkPlaybackGeneration;
            synchronized (EgoTtsTester.this) {
                if (!utteranceId.equals(mExpectedUtteranceId)) {
                    return;
                }
                networkDevice = mNetworkDevice;
                networkCallback = mNetworkCallback;
                networkPlaybackGeneration = mNetworkPlaybackGeneration;
            }
            if (networkDevice != null) {
                playSynthesizedWavOnNetwork(networkDevice, networkCallback,
                        networkPlaybackGeneration);
            } else {
                playSynthesizedWav();
            }
        }

        @Override
        public void onError(String utteranceId) {
            deliverSynthesisError(utteranceId, "TTS 合成失败");
        }

        @Override
        public void onError(String utteranceId, int errorCode) {
            deliverSynthesisError(utteranceId, "TTS 合成失败 (" + errorCode + ")");
        }
    };

    private void deliverSynthesisError(String utteranceId, String message) {
        NetworkCallback networkCallback;
        synchronized (this) {
            if (!utteranceId.equals(mExpectedUtteranceId)) {
                return;
            }
            networkCallback = mNetworkCallback;
            mNetworkCallback = null;
            mNetworkDevice = null;
            mExpectedUtteranceId = null;
        }
        if (networkCallback != null) {
            deliverNetwork(networkCallback, false, message);
        } else {
            deliver(mCallback, false, message);
        }
    }

    private void playSynthesizedWavOnNetwork(Device device, NetworkCallback cb,
                                              long generation) {
        new Thread(() -> {
            try {
                if (!isNetworkPlaybackCurrent(device, generation)) {
                    return;
                }
                Wav source = Wav.parse(new File(mContext.getCacheDir(), NETWORK_SOURCE_WAV_NAME));
                File networkWav = new File(mContext.getCacheDir(), NETWORK_WAV_NAME);
                writePcmWav(source, networkWav, NETWORK_SAMPLE_RATE);
                if (!isNetworkPlaybackCurrent(device, generation)) {
                    return;
                }
                device.playAudioFile(networkWav.getAbsolutePath());
                if (isNetworkPlaybackCurrent(device, generation)) {
                    deliverNetwork(cb, true, "语音已通过网络 Ego 播放");
                }
            } catch (Exception e) {
                if (isNetworkPlaybackCurrent(device, generation)) {
                    deliverNetwork(cb, false, "网络语音播放失败: " + e.getMessage());
                }
            } finally {
                synchronized (EgoTtsTester.this) {
                    if (generation == mNetworkPlaybackGeneration) {
                        mNetworkDevice = null;
                        mNetworkCallback = null;
                        mExpectedUtteranceId = null;
                    }
                }
            }
        }, "EgoNetworkTtsPlayback").start();
    }

    private synchronized boolean isNetworkPlaybackCurrent(Device device, long generation) {
        return generation == mNetworkPlaybackGeneration && mNetworkDevice == device;
    }

    /** Stops both pending synthesis and an active network audio playback. */
    public void stopNetworkPlayback(Device device) {
        synchronized (this) {
            mNetworkPlaybackGeneration++;
            mNetworkDevice = null;
            mNetworkCallback = null;
            mExpectedUtteranceId = null;
        }
        TextToSpeech tts = mTts;
        if (tts != null) {
            tts.stop();
        }
        if (device != null) {
            try {
                device.stopAudioPlayback();
            } catch (Exception e) {
                Log.w(TAG, "stop network playback: " + e.getMessage());
            }
        }
    }

    private static void writePcmWav(Wav source, File output, int targetSampleRate)
            throws IOException {
        if (source.formatTag != 1 || source.bitsPerSample != 16
                || source.channels <= 0 || source.sampleRate <= 0
                || targetSampleRate <= 0) {
            throw new IOException("TTS output is not 16-bit PCM WAV");
        }
        int sourceFrames = source.dataLength / (source.channels * 2);
        if (sourceFrames <= 0) {
            throw new IOException("TTS output contains no PCM samples");
        }
        int targetFrames = Math.max(1, (int) (((long) sourceFrames
                * targetSampleRate + source.sampleRate / 2) / source.sampleRate));
        byte[] pcm = new byte[targetFrames * 2];
        for (int target = 0; target < targetFrames; target++) {
            double position = (double) target * source.sampleRate / targetSampleRate;
            int left = Math.min((int) position, sourceFrames - 1);
            int right = Math.min(left + 1, sourceFrames - 1);
            double fraction = position - left;
            int sample = (int) Math.round((1.0 - fraction) * source.sampleAt(left)
                    + fraction * source.sampleAt(right));
            pcm[target * 2] = (byte) sample;
            pcm[target * 2 + 1] = (byte) (sample >> 8);
        }
        try (FileOutputStream stream = new FileOutputStream(output, false)) {
            writeWavHeader(stream, pcm.length, targetSampleRate);
            stream.write(pcm);
        }
    }

    private static void writeWavHeader(FileOutputStream stream, int dataLength,
                                       int sampleRate) throws IOException {
        int byteRate = sampleRate * 2;
        stream.write(new byte[] {'R', 'I', 'F', 'F'});
        writeLe32(stream, 36 + dataLength);
        stream.write(new byte[] {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
        writeLe32(stream, 16);
        writeLe16(stream, 1);
        writeLe16(stream, 1);
        writeLe32(stream, sampleRate);
        writeLe32(stream, byteRate);
        writeLe16(stream, 2);
        writeLe16(stream, 16);
        stream.write(new byte[] {'d', 'a', 't', 'a'});
        writeLe32(stream, dataLength);
    }

    private static void writeLe16(FileOutputStream stream, int value) throws IOException {
        stream.write(value & 0xFF);
        stream.write((value >>> 8) & 0xFF);
    }

    private static void writeLe32(FileOutputStream stream, int value) throws IOException {
        writeLe16(stream, value);
        writeLe16(stream, value >>> 16);
    }

    private void playSynthesizedWav() {
        final Callback cb = mCallback;
        Wav wav;
        try {
            Wav source = Wav.parse(new File(mContext.getCacheDir(), WAV_NAME));
            File usbWav = new File(mContext.getCacheDir(), USB_48K_WAV_NAME);
            writePcmWav(source, usbWav, USB_TEST_SAMPLE_RATE);
            wav = Wav.parse(usbWav);
        } catch (Exception e) {
            deliver(cb, false, "WAV 转换失败: " + e.getMessage());
            return;
        }

        stopPlayback();  // replace any prior playback
        EgoSpeakerPlayer player = new EgoSpeakerPlayer(
                mContext, wav.sampleRate, wav.channels, wav.bitsPerSample);
        if (!player.start()) {
            deliver(cb, false, "未找到 Ego 扬声器输出设备");
            return;
        }
        synchronized (this) {
            mPlayer = player;
        }
        final String route = player.getRoutedDeviceName();

        // Feed the PCM and auto-stop on a worker so the binder callback returns.
        Thread feeder = new Thread(() -> {
            int off = wav.dataOffset;
            int end = wav.dataOffset + wav.dataLength;
            while (off < end && player.isPlaying()) {
                int n = Math.min(FEED_CHUNK_BYTES, end - off);
                player.write(wav.bytes, off, n);
                off += n;
            }
            long durationMs = (long) wav.dataLength * 1000 / Math.max(1, wav.byteRate);
            try {
                Thread.sleep(durationMs + DRAIN_MARGIN_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            stopPlayback();
            deliver(cb, true, "语音已播放 (" + wav.sampleRate + "Hz, 路由: " + route + ")");
        }, "EgoTtsFeeder");
        feeder.setDaemon(true);
        feeder.start();
    }

    /** Stop any in-progress playback and cancel pending synthesis. Keeps the engine. */
    public void stopPlayback() {
        EgoSpeakerPlayer p;
        synchronized (this) {
            p = mPlayer;
            mPlayer = null;
            mNetworkPlaybackGeneration++;
            mNetworkDevice = null;
            mNetworkCallback = null;
            mExpectedUtteranceId = null;
        }
        if (p != null) {
            p.stop();
        }
        TextToSpeech tts = mTts;
        if (tts != null) {
            try {
                tts.stop();
            } catch (Exception e) {
                Log.w(TAG, "tts.stop: " + e.getMessage());
            }
        }
    }

    /** Full teardown: stop playback and shut down the TTS engine. Idempotent. */
    public synchronized void release() {
        stopPlayback();
        if (mTts != null) {
            try {
                mTts.shutdown();
            } catch (Exception e) {
                Log.w(TAG, "tts.shutdown: " + e.getMessage());
            }
            mTts = null;
        }
        mReady = false;
    }

    private void deliver(Callback cb, boolean ok, String msg) {
        Log.i(TAG, "result ok=" + ok + " : " + msg);
        if (cb != null) {
            cb.onResult(ok, msg);
        }
    }

    private void deliverNetwork(NetworkCallback cb, boolean ok, String msg) {
        Log.i(TAG, "network result ok=" + ok + " : " + msg);
        if (cb != null) {
            cb.onResult(ok, msg);
        }
    }

    /** Minimal WAV reader: locates the fmt/data chunks and the format fields. */
    private static final class Wav {
        byte[] bytes;
        int formatTag;
        int sampleRate;
        int channels;
        int bitsPerSample;
        int byteRate;
        int dataOffset;
        int dataLength;

        static Wav parse(File f) throws IOException {
            byte[] b = readAll(f);
            if (b.length < 44) {
                throw new IOException("file too small (" + b.length + " bytes)");
            }
            if (b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F'
                    || b[8] != 'W' || b[9] != 'A' || b[10] != 'V' || b[11] != 'E') {
                throw new IOException("not a RIFF/WAVE file");
            }
            Wav w = new Wav();
            w.bytes = b;
            boolean haveFmt = false;
            boolean haveData = false;
            // Walk the sub-chunks; "data" is not always at offset 44 (engines may
            // insert "fact"/"LIST" chunks first).
            int p = 12;
            while (p + 8 <= b.length) {
                int size = le32(b, p + 4);
                int body = p + 8;
                if (b[p] == 'f' && b[p + 1] == 'm' && b[p + 2] == 't' && b[p + 3] == ' ') {
                    w.formatTag = le16(b, body);
                    w.channels = le16(b, body + 2);
                    w.sampleRate = le32(b, body + 4);
                    w.byteRate = le32(b, body + 8);
                    w.bitsPerSample = le16(b, body + 14);
                    haveFmt = true;
                } else if (b[p] == 'd' && b[p + 1] == 'a' && b[p + 2] == 't' && b[p + 3] == 'a') {
                    w.dataOffset = body;
                    w.dataLength = Math.min(size, b.length - body);
                    haveData = true;
                }
                if (haveFmt && haveData) {
                    break;
                }
                // Chunks are word-aligned: an odd size is followed by a pad byte.
                p = body + size + (size & 1);
            }
            if (!haveFmt || !haveData) {
                throw new IOException("fmt/data chunk missing");
            }
            if (w.formatTag != 1 || w.bitsPerSample != 16) {
                throw new IOException("expected 16-bit PCM WAV");
            }
            if (w.byteRate <= 0) {
                w.byteRate = w.sampleRate * w.channels * w.bitsPerSample / 8;
            }
            return w;
        }

        /** Linear mono mix of all source channels for the specified PCM frame. */
        int sampleAt(int frame) {
            int sum = 0;
            int base = dataOffset + frame * channels * 2;
            for (int channel = 0; channel < channels; channel++) {
                int offset = base + channel * 2;
                sum += (short) ((bytes[offset] & 0xFF) | (bytes[offset + 1] << 8));
            }
            return sum / channels;
        }

        private static int le16(byte[] b, int i) {
            return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8);
        }

        private static int le32(byte[] b, int i) {
            return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8)
                    | ((b[i + 2] & 0xFF) << 16) | ((b[i + 3] & 0xFF) << 24);
        }

        private static byte[] readAll(File f) throws IOException {
            long len = f.length();
            if (len <= 0 || len > 50L * 1024 * 1024) {
                throw new IOException("bad file size: " + len);
            }
            byte[] out = new byte[(int) len];
            int read = 0;
            try (FileInputStream in = new FileInputStream(f)) {
                int n;
                while (read < out.length && (n = in.read(out, read, out.length - read)) >= 0) {
                    read += n;
                }
            }
            if (read == out.length) {
                return out;
            }
            byte[] trimmed = new byte[read];
            System.arraycopy(out, 0, trimmed, 0, read);
            return trimmed;
        }
    }
}
