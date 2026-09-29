package com.bingo.smartna.collector.device.ego;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Raw H.264/H.265 (Annex-B) stream decoder based on Android MediaCodec. Decoded
 * frames are rendered directly to the given Surface (zero-copy), in decode order
 * as soon as they are available. The codec (AVC vs HEVC) is chosen per stream via
 * {@link #start}, matching the format the device emits.
 *
 * <p>Two device-specific design points:
 * <ul>
 *   <li><b>csd configuration</b>: the codec is configured only once an access
 *       unit carrying the parameter sets arrives, and they are passed as
 *       codec-specific data (SPS/PPS for H.264; VPS/SPS/PPS for H.265) rather
 *       than relying on the decoder to pick them up inline.</li>
 *   <li><b>H.264 software fallback</b>: see {@link #PREFER_SOFTWARE_H264}.</li>
 * </ul>
 *
 * <p>Frames are rendered immediately, not paced to their device timestamps:
 * software timestamp pacing was tried but, with an independent decoder per stream,
 * anchored each eye to its own first-frame wall-clock time and desynced the
 * left/right preview. Rendering as decoded keeps the two eyes in step.
 */
public class VideoDecoder {
    private static final String TAG = "VideoDecoder";
    private static final long TIMEOUT_US = 10_000;

    // Workaround: on this device the Exynos hardware H.264 decoder
    // (c2.exynos.h264.decoder) decodes ONLY keyframes for the camera's H.264
    // stream — fed ~30fps, it outputs ~2fps and drops every P-frame — while the
    // exact same bitstream decodes correctly in software, off-device, and via the
    // hardware HEVC path. The cause is in the vendor AVC decoder (reproduces at
    // multiple resolutions incl. 16-aligned ones, so it is not a padding/crop
    // issue; the SPS uses gaps_in_frame_num_value_allowed_flag=1) and cannot be
    // fixed app-side — feeding csd, stripping in-band parameter sets, flagging
    // keyframes, and disabling low-latency were all tried. So decode H.264 in
    // Hardware AVC is now tried first. The output-rate watchdog below detects
    // this and similar vendor failures, then retries in software.
    /** Start AVC on hardware and use software only after a verified failure. */
    private static final boolean PREFER_SOFTWARE_H264 = false;
    private static final int H264_NO_OUTPUT_FRAME_LIMIT = 45;
    private static final long H264_NO_OUTPUT_TIMEOUT_MS = 2_000;

    private VideoCodec mVideoCodec = VideoCodec.H264;
    private MediaCodec mCodec;
    private Surface mSurface;
    private final MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();

    // Configuration is deferred until an access unit carrying the parameter sets
    // arrives, so they can be passed as csd. mArmed means start() has captured the
    // codec/size/surface but the MediaCodec is not configured yet.
    private boolean mArmed;
    private VideoCodec mPendingCodec;
    private int mPendingW;
    private int mPendingH;

    // Keeps the input PTS strictly increasing for MediaCodec even if the device
    // clock repeats or steps back.
    private long mLastInputPtsUs;
    private boolean mUseSoftwareDecoder;
    private boolean mSoftwareFallbackAttempted;
    private int mQueuedSinceLastOutput;
    private int mOutputSinceHealthCheck;
    private long mFirstQueuedWithoutOutputMs;

    /**
     * Capture the target codec/size/surface. The MediaCodec is NOT configured
     * here — configuration is deferred to {@link #decode} until an access unit
     * carrying the parameter sets arrives (see {@link #configureFromParamSets}).
     * Returns true so the caller treats the decoder as "started" (armed) and
     * stops recreating the surface.
     *
     * @param codec   AVC or HEVC — must match the format the stream carries
     * @param surface owned by this decoder from now on; released in {@link #release()}
     */
    public synchronized boolean start(VideoCodec codec, int width, int height, Surface surface) {
        if (mCodec != null || mArmed) {
            return true;
        }
        mPendingCodec = codec;
        mPendingW = width;
        mPendingH = height;
        mSurface = surface; // own it now; released in release() even if never configured
        mArmed = true;
        Log.i(TAG, "decoder armed: " + codec + " " + width + "x" + height);
        return true;
    }

    public synchronized boolean isStarted() {
        return mCodec != null || mArmed;
    }

    /**
     * Configure and start the codec using the parameter sets carried in this
     * access unit as csd. Returns false (and stays armed) until an AU carrying all
     * required parameter sets arrives — so leading P-frames before the first IDR
     * are skipped. The caller feeds this same AU once configuration succeeds.
     */
    private boolean configureFromParamSets(byte[] data, int size) {
        MediaFormat format = MediaFormat.createVideoFormat(
                mPendingCodec.mime(), mPendingW, mPendingH);
        if (mPendingCodec == VideoCodec.H264) {
            byte[] sps = VideoMuxer.findNal(mPendingCodec, data, size, 7);
            byte[] pps = VideoMuxer.findNal(mPendingCodec, data, size, 8);
            if (sps == null || pps == null) {
                return false;
            }
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
        } else {
            byte[] vps = VideoMuxer.findNal(mPendingCodec, data, size, 32);
            byte[] sps = VideoMuxer.findNal(mPendingCodec, data, size, 33);
            byte[] pps = VideoMuxer.findNal(mPendingCodec, data, size, 34);
            if (vps == null || sps == null || pps == null) {
                return false;
            }
            // HEVC csd-0 is VPS+SPS+PPS concatenated (each start-code prefixed).
            ByteArrayOutputStream csd = new ByteArrayOutputStream();
            csd.write(vps, 0, vps.length);
            csd.write(sps, 0, sps.length);
            csd.write(pps, 0, pps.length);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd.toByteArray()));
        }
        // IDR access units from the camera can be large; the level-based default
        // input buffer size is not always enough.
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, mPendingW * mPendingH * 3 / 2);
        try {
            mVideoCodec = mPendingCodec;
            mCodec = createDecoder(mVideoCodec);
            mCodec.configure(format, mSurface, null, 0);
            mCodec.start();
            mArmed = false;
            mLastInputPtsUs = Long.MIN_VALUE;
            Log.i(TAG, "decoder configured: " + mVideoCodec + " "
                    + mPendingW + "x" + mPendingH + " impl=" + safeCodecName());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "configure failed (" + decoderKind() + "): " + e.getMessage());
            releaseCodecOnly();
            if (!fallBackToSoftware("configure exception")) {
                release();
            }
            return false;
        }
    }

    /**
     * Create a H.264 hardware decoder first. A software implementation is used
     * only when no hardware decoder exists or after a verified hardware failure.
     */
    private MediaCodec createDecoder(VideoCodec codec) throws java.io.IOException {
        if (codec == VideoCodec.H264 && (PREFER_SOFTWARE_H264 || mUseSoftwareDecoder)) {
            String name = findSoftwareDecoder(codec.mime());
            if (name != null) {
                return MediaCodec.createByCodecName(name);
            }
            Log.w(TAG, "no software H264 decoder found; using default");
        }
        if (codec == VideoCodec.H264) {
            String name = findHardwareDecoder(codec.mime());
            if (name != null) {
                return MediaCodec.createByCodecName(name);
            }
            // No hardware candidate: this is a normal initial software path,
            // not a runtime fallback, so do not consume the one retry.
            name = findSoftwareDecoder(codec.mime());
            if (name != null) {
                mUseSoftwareDecoder = true;
                Log.w(TAG, "no hardware H264 decoder; using " + name);
                return MediaCodec.createByCodecName(name);
            }
        }
        return MediaCodec.createDecoderByType(codec.mime());
    }

    /** Name of a software decoder for {@code mime}, or null if none. */
    private static String findSoftwareDecoder(String mime) {
        return findDecoder(mime, true);
    }

    private static String findHardwareDecoder(String mime) {
        return findDecoder(mime, false);
    }

    private static String findDecoder(String mime, boolean softwareWanted) {
        MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (info.isEncoder()) {
                continue;
            }
            boolean handles = false;
            for (String t : info.getSupportedTypes()) {
                if (t.equalsIgnoreCase(mime)) {
                    handles = true;
                    break;
                }
            }
            if (!handles) {
                continue;
            }
            boolean software;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                software = !info.isHardwareAccelerated();
            } else {
                String n = info.getName().toLowerCase(Locale.US);
                software = n.startsWith("omx.google.") || n.startsWith("c2.android.")
                        || n.contains(".sw.");
            }
            if (software == softwareWanted) {
                return info.getName();
            }
        }
        return null;
    }

    private String safeCodecName() {
        try {
            return mCodec.getName();
        } catch (Exception e) {
            return "?";
        }
    }

    /** Human-readable actual decoder state for the sample UI and diagnostics. */
    public synchronized String getDecodeMode() {
        if (mCodec != null) {
            return "Decode: " + (mUseSoftwareDecoder ? "software" : "hardware")
                    + " (" + safeCodecName() + ")";
        }
        if (mArmed) {
            return "Decode: " + (mUseSoftwareDecoder ? "software" : "hardware")
                    + " (waiting for IDR)";
        }
        return "Decode: not started";
    }

    private String decoderKind() {
        return mUseSoftwareDecoder ? "software" : "hardware";
    }

    /**
     * Retain the Surface, then wait for the next SPS/PPS carrying IDR to create
     * the software codec. The current P-frame cannot initialize a new decoder.
     */
    private boolean fallBackToSoftware(String reason) {
        if (mVideoCodec != VideoCodec.H264 || mUseSoftwareDecoder || mSoftwareFallbackAttempted
                || findSoftwareDecoder(VideoCodec.H264.mime()) == null) {
            return false;
        }
        mSoftwareFallbackAttempted = true;
        mUseSoftwareDecoder = true;
        releaseCodecOnly();
        mArmed = mSurface != null;
        Log.w(TAG, "switching H264 decoder to software: " + reason);
        return true;
    }

    private void releaseCodecOnly() {
        if (mCodec != null) {
            try {
                mCodec.stop();
            } catch (Exception ignore) {
            }
            try {
                mCodec.release();
            } catch (Exception ignore) {
            }
            mCodec = null;
        }
        mQueuedSinceLastOutput = 0;
        mOutputSinceHealthCheck = 0;
        mFirstQueuedWithoutOutputMs = 0;
    }

    /**
     * Feed one H.264/H.265 access unit (Annex-B byte stream, may contain several
     * NAL units) and render any decoded frames to the Surface.
     */
    public synchronized void decode(byte[] data, int size, long ptsUs) {
        if (data == null || size <= 0) {
            return;
        }
        if (mCodec == null) {
            // Not configured yet: wait for an access unit carrying the parameter
            // sets, then configure with them as csd. Until then, drop (this skips
            // any leading P-frames before the first IDR).
            if (!mArmed || !configureFromParamSets(data, size)) {
                return;
            }
        }
        try {
            // Free output buffers first so the codec can accept new input.
            drainOutput();
            // Dropping an access unit corrupts every following P-frame until the
            // next IDR (visible as mosaic), so retry with draining in between
            // instead of giving up after one short wait.
            int inIndex = -1;
            for (int retry = 0; retry < 10; retry++) {
                inIndex = mCodec.dequeueInputBuffer(TIMEOUT_US);
                if (inIndex >= 0) {
                    break;
                }
                drainOutput();
            }
            if (inIndex >= 0) {
                ByteBuffer input = mCodec.getInputBuffer(inIndex);
                input.clear();
                input.put(data, 0, size);
                // MediaCodec wants a non-decreasing input PTS; nudge forward if the
                // device clock repeats or steps back so frame ordering stays stable.
                if (ptsUs <= mLastInputPtsUs) {
                    ptsUs = mLastInputPtsUs + 1;
                }
                mLastInputPtsUs = ptsUs;
                mCodec.queueInputBuffer(inIndex, 0, size, ptsUs, 0);
                noteInputQueued();
                if (mCodec == null) {
                    return; // watchdog changed to software; wait for the next IDR
                }
            } else {
                Log.w(TAG, "input buffer starved, frame dropped -> expect mosaic until next IDR");
            }
            drainOutput();
        } catch (Exception e) {
            Log.e(TAG, "decode failed (" + decoderKind() + "): " + e.getMessage());
            if (!fallBackToSoftware("decode exception")) {
                release();
            }
        }
    }

    /**
     * Render all pending decoded frames to the Surface immediately, in decode
     * order. Non-blocking (timeout 0); returns once no more output is ready.
     */
    private void drainOutput() {
        while (true) {
            int outIndex = mCodec.dequeueOutputBuffer(mBufferInfo, 0);
            if (outIndex >= 0) {
                // Do not use releaseOutputBuffer(index, true). On the Android
                // 16 device this calls MediaCodec::onReleaseOutputBuffer,
                // which multiplies the frame PTS (us) by 1000 to derive a
                // render timestamp and aborts on overflow. The camera PTS is
                // valid for muxing but can be outside that framework path's
                // range. Preview is explicitly unpaced, so render now and pass
                // the nanosecond timestamp directly instead.
                mCodec.releaseOutputBuffer(outIndex, System.nanoTime());
                mOutputSinceHealthCheck++;
            } else if (outIndex != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                    && outIndex != MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                break;  // INFO_TRY_AGAIN_LATER / no more output ready
            }
        }
    }

    /** Detect vendor decoders that accept input but never render a frame. */
    private void noteInputQueued() {
        if (mVideoCodec != VideoCodec.H264 || mUseSoftwareDecoder) {
            return;
        }
        if (++mQueuedSinceLastOutput == 1) {
            mFirstQueuedWithoutOutputMs = android.os.SystemClock.elapsedRealtime();
            return;
        }
        long stalledMs = android.os.SystemClock.elapsedRealtime() - mFirstQueuedWithoutOutputMs;
        if (mQueuedSinceLastOutput >= H264_NO_OUTPUT_FRAME_LIMIT
                && stalledMs >= H264_NO_OUTPUT_TIMEOUT_MS) {
            // Covers both zero-output and the vendor failure where only IDR
            // frames are produced: the output rate is far below input rate.
            if (mOutputSinceHealthCheck * 3 < mQueuedSinceLastOutput) {
                Log.e(TAG, "hardware H264 unhealthy: " + mOutputSinceHealthCheck + " output / "
                        + mQueuedSinceLastOutput + " input frames in " + stalledMs + "ms");
                fallBackToSoftware("decoder output stalled");
            } else {
                mQueuedSinceLastOutput = 0;
                mOutputSinceHealthCheck = 0;
                mFirstQueuedWithoutOutputMs = android.os.SystemClock.elapsedRealtime();
            }
        }
    }

    public synchronized void release() {
        releaseCodecOnly();
        if (mSurface != null) {
            mSurface.release();
            mSurface = null;
        }
        mArmed = false;
        mUseSoftwareDecoder = false;
        mSoftwareFallbackAttempted = false;
    }
}
