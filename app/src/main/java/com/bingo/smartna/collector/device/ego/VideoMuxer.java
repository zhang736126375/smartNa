package com.bingo.smartna.collector.device.ego;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Repackages a raw H.264/H.265 (Annex-B) access-unit stream into a standard
 * .mp4 file via MediaMuxer, WITHOUT re-encoding. Purpose: pull to a PC and
 * inspect whether the camera bitstream itself contains mosaic artifacts,
 * decoupled from the on-device decoder. Since samples are copied verbatim, any
 * artifact present in the source stream is preserved exactly.
 *
 * <p>The codec (AVC vs HEVC) is fixed at construction and must match the format
 * the device was configured to emit — it decides both the track MIME and how
 * the codec-specific data (csd) is assembled:
 * <ul>
 *   <li>H.264: {@code csd-0 = SPS(7)}, {@code csd-1 = PPS(8)}.</li>
 *   <li>H.265: {@code csd-0 = VPS(32) + SPS(33) + PPS(34)} concatenated.</li>
 * </ul>
 */
public class VideoMuxer {
    private static final String TAG = "VideoMuxer";

    /** Result of handing one access unit to the muxer. */
    public enum WriteResult {
        WRITTEN,
        WAITING_FOR_KEY_FRAME,
        FAILED
    }

    private final VideoCodec mVideoCodec;
    private final String mPath;
    private final int mWidth;
    private final int mHeight;

    private MediaMuxer mMuxer;
    private int mTrackIndex = -1;
    private boolean mStarted;
    private boolean mFailed;
    private long mFirstPtsUs = -1;   // baseline so PTS starts at 0
    private long mLastPtsUs = -1;    // last emitted PTS, kept strictly increasing
    // Optional pre-known codec-specific data. When supplied (by the segmenting
    // Mp4Recorder for the 2nd+ segment), the track can start on the first IDR AU
    // without waiting for the stream to carry the parameter sets again — so a
    // rolled-over segment never loses its opening frames. Each entry is a
    // start-code-prefixed NAL: {SPS, PPS} for H.264, {VPS, SPS, PPS} for H.265.
    private final byte[][] mParamSetOverride;

    public VideoMuxer(VideoCodec codec, String path, int width, int height) {
        this(codec, path, width, height, null);
    }

    /**
     * @param paramSetOverride pre-known parameter-set NALs (each 4-byte-start-
     *                         code prefixed) to seed the track, or null to wait
     *                         for the stream to supply them. Order/content is
     *                         codec-specific: {SPS, PPS} for H.264,
     *                         {VPS, SPS, PPS} for H.265.
     */
    public VideoMuxer(VideoCodec codec, String path, int width, int height, byte[][] paramSetOverride) {
        mVideoCodec = codec;
        mPath = path;
        mWidth = width;
        mHeight = height;
        mParamSetOverride = paramSetOverride;
    }

    /**
     * Feed one Annex-B access unit (may contain parameter-set + slice NALs)
     * tagged with the frame's real device timestamp (µs). The PTS is taken
     * relative to the first frame, so the mp4 honours the true capture framerate
     * (e.g. 60fps) and any jitter — it plays at correct speed and duration
     * rather than a hard-coded framerate.
     */
    public synchronized WriteResult write(byte[] data, int size, long ptsUs) {
        // A MediaMuxer failure is terminal for this output file. Retrying the
        // same path could overwrite a partially finalized file and, if the
        // failure happened after start(), could resume from a non-keyframe.
        if (mFailed) {
            return WriteResult.FAILED;
        }
        try {
            if (!mStarted) {
                // The muxer track needs the parameter sets as codec-specific
                // data. Prefer the pre-known override (segment rollover);
                // otherwise the file cannot start until an access unit carrying
                // all required parameter sets arrives in the stream.
                MediaFormat format = buildFormat(data, size);
                if (format == null) {
                    return WriteResult.WAITING_FOR_KEY_FRAME;
                }
                // Guard: start writing only at an IDR boundary so the file is
                // always decodable from the very first frame.
                if (!isKeyFrame(mVideoCodec, data, size)) {
                    return WriteResult.WAITING_FOR_KEY_FRAME;
                }
                mMuxer = new MediaMuxer(mPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                mTrackIndex = mMuxer.addTrack(format);
                mMuxer.start();
                mStarted = true;
                Log.i(TAG, "muxer started: " + mVideoCodec + " " + mPath);
            }
            if (mFirstPtsUs < 0) {
                mFirstPtsUs = ptsUs;
            }
            long pts = ptsUs - mFirstPtsUs;
            // MediaMuxer requires strictly increasing PTS; guard against equal
            // or out-of-order device timestamps.
            if (pts <= mLastPtsUs) {
                pts = mLastPtsUs + 1;
            }
            mLastPtsUs = pts;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = pts;
            info.flags = isKeyFrame(mVideoCodec, data, size) ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            mMuxer.writeSampleData(mTrackIndex, ByteBuffer.wrap(data, 0, size), info);
            return WriteResult.WRITTEN;
        } catch (Exception e) {
            Log.e(TAG, "write failed: " + e.getMessage());
            mFailed = true;
            releaseMuxer();
            return WriteResult.FAILED;
        }
    }

    /**
     * Build the track MediaFormat from the pre-known override or by extracting
     * the parameter sets from this access unit. Returns null if the required
     * parameter sets are not yet available.
     */
    private MediaFormat buildFormat(byte[] data, int size) {
        MediaFormat format = MediaFormat.createVideoFormat(mVideoCodec.mime(), mWidth, mHeight);
        if (mVideoCodec == VideoCodec.H264) {
            byte[] sps = mParamSetOverride != null ? mParamSetOverride[0] : findNal(mVideoCodec, data, size, 7);
            byte[] pps = mParamSetOverride != null ? mParamSetOverride[1] : findNal(mVideoCodec, data, size, 8);
            if (sps == null || pps == null) {
                return null;
            }
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
        } else {
            byte[] vps = mParamSetOverride != null ? mParamSetOverride[0] : findNal(mVideoCodec, data, size, 32);
            byte[] sps = mParamSetOverride != null ? mParamSetOverride[1] : findNal(mVideoCodec, data, size, 33);
            byte[] pps = mParamSetOverride != null ? mParamSetOverride[2] : findNal(mVideoCodec, data, size, 34);
            if (vps == null || sps == null || pps == null) {
                return null;
            }
            // HEVC csd-0 is the concatenation of VPS+SPS+PPS (each start-code prefixed).
            ByteArrayOutputStream csd = new ByteArrayOutputStream();
            csd.write(vps, 0, vps.length);
            csd.write(sps, 0, sps.length);
            csd.write(pps, 0, pps.length);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd.toByteArray()));
        }
        return format;
    }

    public synchronized void release() {
        releaseMuxer();
    }

    /** Release a fully or partially initialized muxer. Caller holds this monitor. */
    private void releaseMuxer() {
        if (mMuxer != null) {
            try {
                if (mStarted) {
                    mMuxer.stop();
                }
            } catch (Exception ignore) {
            }
            try {
                mMuxer.release();
            } catch (Exception ignore) {
            }
            mMuxer = null;
            mStarted = false;
            Log.i(TAG, "muxer released: " + mPath);
        }
    }

    /** Length of the Annex-B start code at offset i, or 0 if none. */
    private static int startCodeLen(byte[] d, int i, int size) {
        if (i + 3 < size && d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 0 && d[i + 3] == 1) {
            return 4;
        }
        if (i + 2 < size && d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) {
            return 3;
        }
        return 0;
    }

    /**
     * Extract the first NAL unit of the given type, returned with a 4-byte
     * start code prefix (the form MediaMuxer expects for csd buffers).
     */
    static byte[] findNal(VideoCodec codec, byte[] data, int size, int wantType) {
        int i = 0;
        while (i + 3 < size) {
            int scLen = startCodeLen(data, i, size);
            if (scLen == 0) {
                i++;
                continue;
            }
            int nalStart = i + scLen;
            int nalType = codec.nalType(data, nalStart);
            int j = nalStart + 1;
            int nalEnd = size;
            while (j + 2 < size) {
                if (startCodeLen(data, j, size) > 0) {
                    nalEnd = j;
                    break;
                }
                j++;
            }
            if (nalType == wantType) {
                int payloadLen = nalEnd - nalStart;
                byte[] out = new byte[4 + payloadLen];
                out[3] = 1; // 00 00 00 01 start code
                System.arraycopy(data, nalStart, out, 4, payloadLen);
                return out;
            }
            i = nalEnd;
        }
        return null;
    }

    /** True if the access unit contains an IDR/keyframe slice for this codec. */
    static boolean isKeyFrame(VideoCodec codec, byte[] data, int size) {
        int i = 0;
        while (i + 3 < size) {
            int scLen = startCodeLen(data, i, size);
            if (scLen == 0) {
                i++;
                continue;
            }
            if (codec.isKeyFrameNal(codec.nalType(data, i + scLen))) {
                return true;
            }
            i += scLen;
        }
        return false;
    }
}
