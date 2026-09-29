package com.bingo.smartna.collector.device.ego;

/**
 * In-place, lossless-band DC-blocking high-pass filter for 16-bit little-endian
 * PCM, applied to each chunk just before it is handed to the WAV recorder.
 *
 * <p>Removes the DC offset and sub-~40Hz rumble that many mic front-ends carry —
 * a component perceived as part of the constant "hiss/hum" floor — while leaving
 * the entire voice band untouched. Unlike a noise gate or spectral subtraction it
 * has no pumping, no swallowed weak signal, and no risk of making the recording
 * worse; the trade-off is that it does not touch true high-frequency white noise.
 *
 * <p>Standard first-order DC blocker: {@code y[n] = x[n] - x[n-1] + R*y[n-1]}.
 * With {@code R = 0.995} at 48 kHz the −3 dB cutoff is ≈ 38 Hz. Per-channel state
 * is kept across chunks so there is no discontinuity at chunk boundaries.
 *
 * <p>Threading: constructed on the UI thread (before the audio callback begins
 * offering), then {@link #processInPlace} is called only from the single
 * audio-callback thread — the filter state is therefore single-threaded and needs
 * no synchronization.
 */
public class PcmProcessor {

    // Feedback coefficient: closer to 1.0 → lower cutoff. 0.995 ≈ 38 Hz @ 48 kHz.
    private static final double R = 0.995;

    private final int channels;
    private final double[] xPrev;
    private final double[] yPrev;

    public PcmProcessor(int channels) {
        this.channels = Math.max(1, channels);
        this.xPrev = new double[this.channels];
        this.yPrev = new double[this.channels];
    }

    /**
     * Filter {@code length} bytes of interleaved 16-bit little-endian PCM in place.
     * A trailing odd byte (incomplete sample) is left untouched.
     */
    public void processInPlace(byte[] buf, int length) {
        int samples = length / 2;  // 16-bit → 2 bytes per sample
        int ch = 0;
        for (int i = 0; i < samples; i++) {
            int idx = i << 1;
            int lo = buf[idx] & 0xFF;
            int hi = buf[idx + 1];              // sign-extended → signed 16-bit
            double x = (hi << 8) | lo;

            double y = x - xPrev[ch] + R * yPrev[ch];
            xPrev[ch] = x;
            yPrev[ch] = y;

            int out = (int) Math.round(y);
            if (out > 32767) {
                out = 32767;
            } else if (out < -32768) {
                out = -32768;
            }
            buf[idx] = (byte) (out & 0xFF);
            buf[idx + 1] = (byte) ((out >> 8) & 0xFF);

            if (++ch >= channels) {
                ch = 0;
            }
        }
    }
}
