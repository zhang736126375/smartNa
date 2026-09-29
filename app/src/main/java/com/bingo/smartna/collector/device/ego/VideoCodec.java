package com.bingo.smartna.collector.device.ego;

import android.media.MediaCodecList;
import android.media.MediaFormat;

import com.orbbec.obsensor.types.Format;

/**
 * Codec-specific bitstream knowledge shared by the decoder and the mp4 muxer.
 *
 * <p>H.264 and H.265 (HEVC) differ in three ways that every Annex-B consumer in
 * this app must account for:
 * <ul>
 *   <li><b>NAL header</b>: H.264 is 1 byte with the type in {@code b & 0x1F};
 *       H.265 is 2 bytes with the type in {@code (b >> 1) & 0x3F}.</li>
 *   <li><b>Parameter sets</b>: H.264 carries SPS(7)/PPS(8); H.265 additionally
 *       carries VPS(32) before SPS(33)/PPS(34).</li>
 *   <li><b>Keyframe (IDR)</b>: H.264 IDR is NAL type 5; H.265 keyframes span
 *       types 16..21 (BLA/IDR/CRA).</li>
 * </ul>
 * Centralising the bit twiddling here keeps {@link VideoDecoder} and
 * {@link VideoMuxer} free of per-codec branches.
 */
public enum VideoCodec {
    H264(MediaFormat.MIMETYPE_VIDEO_AVC),
    H265(MediaFormat.MIMETYPE_VIDEO_HEVC);

    private final String mMime;

    VideoCodec(String mime) {
        mMime = mime;
    }

    /** MediaCodec MIME type, e.g. {@code "video/avc"} / {@code "video/hevc"}. */
    public String mime() {
        return mMime;
    }

    /**
     * Map an SDK stream {@link Format} to the codec used to decode/mux it, or
     * {@code null} if the format is not an inter-frame video codec this app
     * knows how to handle.
     */
    public static VideoCodec fromFormat(Format format) {
        if (format == Format.H264) {
            return H264;
        }
        if (format == Format.H265 || format == Format.HEVC) {
            return H265;
        }
        return null;
    }

    /** NAL unit type at {@code nalStart} (index of the first byte after the start code). */
    public int nalType(byte[] d, int nalStart) {
        if (this == H264) {
            return d[nalStart] & 0x1F;
        }
        // H.265: type is bits 1..6 of the first NAL header byte.
        return (d[nalStart] >> 1) & 0x3F;
    }

    /** NAL type for SPS — the parameter set decoding cannot start without. */
    public int spsType() {
        return this == H264 ? 7 : 33;
    }

    /** True if {@code type} is an IDR/keyframe slice for this codec. */
    public boolean isKeyFrameNal(int type) {
        if (this == H264) {
            return type == 5;
        }
        // H.265: BLA_W_LP(16)..CRA_NUT(21) are all random-access (keyframe) pictures.
        return type >= 16 && type <= 21;
    }

    /** True if this device has a hardware/software decoder for this codec's MIME. */
    public boolean isDecoderAvailable() {
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        try {
            return list.findDecoderForFormat(
                    MediaFormat.createVideoFormat(mMime, 640, 480)) != null;
        } catch (Exception e) {
            return false;
        }
    }
}
