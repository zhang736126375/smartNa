package com.bingo.smartna.collector.device.ego;

import android.util.Log;

import com.orbbec.obsensor.Frame;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Asynchronous, non-blocking recorder that muxes a raw H.264/H.265 (Annex-B)
 * access-unit stream into an .mp4 file <b>off the calling thread</b>, wrapping
 * {@link VideoMuxer} (no re-encoding — samples are copied verbatim).
 *
 * <p>Designed so recording never costs the producer a dropped frame:
 * <ul>
 *   <li>{@link #offer(byte[], int, long)} / {@link #offer(Frame, long)} copy the
 *       access unit into a pooled buffer and enqueue it, then return immediately —
 *       they never touch the file system.</li>
 *   <li>A single writer thread drains the queue and does <em>all</em>
 *       MediaMuxer work.</li>
 *   <li>Buffers are recycled through a free list to avoid per-frame allocation.</li>
 *   <li>Capacity is enforced by <em>total byte payload</em> rather than frame
 *       count: an H.265 I-frame can be 10–25× larger than a P-frame, so a fixed
 *       frame count silently under-buffers during keyframe bursts.  If the writer
 *       falls behind and the byte ceiling is reached the frame is dropped (and
 *       counted) rather than blocking — recording degrades, streaming does not.</li>
 * </ul>
 *
 * <p>Threading: {@link #offer} is called from a single producer thread (the
 * frame-fetch loop); {@link #start}/{@link #stop} from the UI thread.
 */
public class Mp4Recorder {

    private static final String TAG = "Mp4Recorder";

    // Byte-based queue capacity: pre-reserves enough in-memory buffer to ride
    // out 5+ seconds of sustained I/O contention (old fixed-60-frame / ~1s cap
    // was exhausted whenever four recorders competed on startup I/O).
    // QUAD mode peak: 4 × 256 MB = 1 GB — fits within typical Android process
    // budget when H264/H265 is ~200 KB/P-frame average.
    private static final long MAX_QUEUE_BYTES  = 256L * 1024 * 1024;
    // Frame-count hard cap prevents extreme I-frame bursts (each up to ~5 MB)
    // from queuing thousands of large frames before the byte gate fires.
    private static final int  QUEUE_CAPACITY   = 2048;
    private static final int  JOIN_TIMEOUT_MS  = 2000;
    private static final int  RAW_OUTPUT_BUFFER_BYTES = 1024 * 1024;
    // Emit a real-time drop warning to logcat at most every 3 s while frames
    // are being dropped — avoids log spam at 60 fps, but surfaces the problem
    // during capture rather than only at stop().
    private static final long WARN_INTERVAL_MS = 3000;

    private static final long MAX_SEGMENT_BYTES       = 1_500L * 1024 * 1024;
    private static final long MAX_SEGMENT_DURATION_US = 10L * 60 * 1_000_000;

    /** A reusable H.264/H.265 access-unit buffer pooled via the free list. */
    private static final class Buffer {
        byte[] data;
        int    length;
        long   ptsUs;
    }

    private final VideoCodec mVideoCodec;
    private final String     mBasePath;
    private final int        mWidth;
    private final int        mHeight;
    private final String     mRawPath;
    private VideoMuxer mMuxer;
    // Diagnostic Annex-B output is owned exclusively by the writer thread.
    private BufferedOutputStream mRawOutput;
    private boolean mRawOutputDisabled;

    // Segment rollover state — touched only on the writer thread.
    private int    mSegmentIndex;
    private long   mSegmentBytes;
    private long   mSegmentStartPtsUs = -1;
    private byte[][] mParamSets;

    private final LinkedBlockingQueue<Buffer> mQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final LinkedBlockingQueue<Buffer> mPool  = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    // Total encoded bytes currently in mQueue. Updated by the producer on enqueue
    // and by the writer thread on dequeue. Single producer → no contention on
    // the increment path; AtomicLong covers the concurrent decrement.
    private final AtomicLong mQueueBytes = new AtomicLong(0);

    // Stats — read by the capture-summary reporter after stop().
    private volatile long mDroppedFrames;
    private volatile long mWrittenFrames;
    private volatile long mStartupSkippedFrames;
    private volatile long mMuxerFailedFrames;
    private volatile long mRawWrittenFrames;
    private volatile long mRawWriteErrors;
    private volatile long mPeakQueueBytes;

    private Thread          mThread;
    private volatile boolean mRunning;
    // Last wall-clock ms at which a real-time drop warning was emitted.
    // Written and read only from the producer thread (single producer).
    private long mLastWarnMs;

    public Mp4Recorder(VideoCodec codec, String path, int width, int height) {
        this(codec, path, width, height, null);
    }

    /**
     * @param rawPath optional diagnostic Annex-B output path; null disables it.
     *                The raw file receives only access units successfully written
     *                to MP4, using the exact same queued buffer.
     */
    public Mp4Recorder(VideoCodec codec, String path, int width, int height, String rawPath) {
        mVideoCodec = codec;
        mBasePath   = path.endsWith(".mp4") ? path.substring(0, path.length() - 4) : path;
        mWidth  = width;
        mHeight = height;
        mRawPath = rawPath;
        mMuxer  = new VideoMuxer(codec, segmentPath(0), width, height);
    }

    /** {@code <base>_<NNN>.mp4} — three-digit segment number, first segment 000. */
    private String segmentPath(int index) {
        return String.format(Locale.US, "%s_%03d.mp4", mBasePath, index);
    }

    /** Start the writer thread. Idempotent. */
    public synchronized void start() {
        if (mRunning) return;
        mQueue.clear();
        mPool.clear();
        mQueueBytes.set(0);
        mDroppedFrames  = 0;
        mWrittenFrames  = 0;
        mStartupSkippedFrames = 0;
        mMuxerFailedFrames = 0;
        mRawWrittenFrames = 0;
        mRawWriteErrors = 0;
        mPeakQueueBytes = 0;
        mLastWarnMs     = 0;
        mRawOutputDisabled = false;
        mRunning = true;
        mThread  = new Thread(this::writeLoop, "Mp4Recorder");
        mThread.start();
    }

    // -------------------------------------------------------------------------
    // Producer API — non-blocking, called only from the frame-fetch thread
    // -------------------------------------------------------------------------

    /**
     * Enqueue one Annex-B access unit via a pre-copied byte array. Non-blocking:
     * drops (and counts) the frame when the byte capacity is exceeded.
     *
     * <p>Note: internally this copies {@code data[0..length]} a second time into
     * a pooled buffer. When the caller does not also need the bytes for decoding,
     * prefer {@link #offer(Frame, long)} which reads the SDK frame directly into
     * the pooled buffer (one copy instead of two).
     */
    public void offer(byte[] data, int length, long ptsUs) {
        if (!mRunning) return;
        if (!acquireQueueSpace(length)) return;
        Buffer buf = borrowBuffer(length);
        System.arraycopy(data, 0, buf.data, 0, length);
        buf.length = length;
        buf.ptsUs  = ptsUs;
        enqueue(buf);
    }

    /**
     * Enqueue one Annex-B access unit by reading the SDK {@link Frame} directly
     * into a pooled buffer — a <em>single</em> JNI copy, avoiding the intermediate
     * Java byte array that {@link #offer(byte[], int, long)} requires.
     *
     * <p>Preferred when the caller does not also need the frame data for decoding
     * (i.e. {@code isVideoDecodeEnabled()} is false), which is the common case
     * during pure data-collection sessions.
     */
    public void offer(Frame frame, long ptsUs) {
        if (!mRunning) return;
        int length = frame.getDataSize();
        if (!acquireQueueSpace(length)) return;
        Buffer buf = borrowBuffer(length);
        try {
            frame.getData(buf.data);   // single JNI copy directly into pooled buffer
        } catch (Exception e) {
            // Roll back the reservation and return the buffer so neither leaks.
            mQueueBytes.addAndGet(-length);
            mPool.offer(buf);
            Log.e(TAG, "offer(Frame): getData failed: " + e.getMessage());
            return;
        }
        buf.length = length;
        buf.ptsUs  = ptsUs;
        enqueue(buf);
    }

    /**
     * Reserve {@code bytes} in the byte-capacity gate.
     * Returns true if space was reserved, false (and increments drop counter) if
     * the ceiling would be exceeded.
     */
    private boolean acquireQueueSpace(int bytes) {
        long cur = mQueueBytes.addAndGet(bytes);
        if (cur > MAX_QUEUE_BYTES) {
            mQueueBytes.addAndGet(-bytes);
            mDroppedFrames++;
            warnIfNeeded();
            return false;
        }
        if (cur > mPeakQueueBytes) mPeakQueueBytes = cur;
        return true;
    }

    private Buffer borrowBuffer(int minLen) {
        Buffer buf = mPool.poll();
        if (buf == null || buf.data == null || buf.data.length < minLen) {
            buf = new Buffer();
            buf.data = new byte[minLen];
        }
        return buf;
    }

    /** Enqueue a pre-filled buffer; rolls back the byte reservation on failure. */
    private void enqueue(Buffer buf) {
        if (!mQueue.offer(buf)) {
            // Frame-count hard cap exceeded (rare: extreme I-frame burst).
            mQueueBytes.addAndGet(-buf.length);
            mDroppedFrames++;
            mPool.offer(buf);
            warnIfNeeded();
        }
    }

    /** Emit a real-time drop warning, throttled to WARN_INTERVAL_MS. */
    private void warnIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - mLastWarnMs >= WARN_INTERVAL_MS) {
            Log.w(TAG, "[REALTIME DROP] dropped=" + mDroppedFrames
                    + " queueBytes=" + mQueueBytes.get() / 1024 + " KB"
                    + " peak=" + mPeakQueueBytes / 1024 + " KB");
            mLastWarnMs = now;
        }
    }

    // -------------------------------------------------------------------------

    private void writeLoop() {
        try {
            while (mRunning || !mQueue.isEmpty()) {
                try {
                    Buffer buf = mQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (buf == null) continue;
                    // Decrement byte accounting as soon as the buffer leaves the queue.
                    mQueueBytes.addAndGet(-buf.length);
                    try {
                        maybeRollSegment(buf);
                        VideoMuxer.WriteResult result = mMuxer.write(
                                buf.data, buf.length, buf.ptsUs);
                        if (result == VideoMuxer.WriteResult.WRITTEN) {
                            // Write the exact same Annex-B access unit only after
                            // MediaMuxer accepted it, so raw and MP4 start at the
                            // same keyframe and contain the same frame sequence.
                            writeRaw(buf);
                            mSegmentBytes += buf.length;
                            mWrittenFrames++;
                            if (mSegmentStartPtsUs < 0) {
                                mSegmentStartPtsUs = buf.ptsUs;
                            }
                        } else if (result == VideoMuxer.WriteResult.WAITING_FOR_KEY_FRAME) {
                            mStartupSkippedFrames++;
                        } else {
                            mMuxerFailedFrames++;
                        }
                    } finally {
                        mPool.offer(buf);  // recycle regardless of write outcome
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "writeLoop: " + e.getMessage());
                }
            }
        } finally {
            closeRawOutput();
            mMuxer.release();
        }
        Log.i(TAG, "stopped: written=" + mWrittenFrames
                + " startupSkipped=" + mStartupSkippedFrames
                + " muxerFailed=" + mMuxerFailedFrames
                + " queueDropped=" + mDroppedFrames
                + " rawWritten=" + mRawWrittenFrames
                + " rawErrors=" + mRawWriteErrors
                + " peak=" + mPeakQueueBytes / 1024 + " KB");
    }

    /** Write one successfully muxed access unit to the optional raw stream. */
    private void writeRaw(Buffer buf) {
        if (mRawPath == null || mRawOutputDisabled) return;
        try {
            if (mRawOutput == null) {
                mRawOutput = new BufferedOutputStream(
                        new FileOutputStream(mRawPath), RAW_OUTPUT_BUFFER_BYTES);
                Log.i(TAG, "raw diagnostic started: " + mRawPath);
            }
            mRawOutput.write(buf.data, 0, buf.length);
            mRawWrittenFrames++;
        } catch (IOException e) {
            mRawWriteErrors++;
            mRawOutputDisabled = true;
            Log.e(TAG, "raw diagnostic write failed: " + e.getMessage());
            closeRawOutput();
        }
    }

    /** Close the writer-owned raw stream. Safe to call more than once. */
    private void closeRawOutput() {
        BufferedOutputStream output = mRawOutput;
        mRawOutput = null;
        if (output == null) return;
        try {
            output.close();
        } catch (IOException e) {
            mRawWriteErrors++;
            Log.e(TAG, "raw diagnostic close failed: " + e.getMessage());
        }
    }

    /**
     * Roll over to the next segment file when a size or duration limit is hit,
     * but only at an IDR access unit so the new file starts on a keyframe.
     * Caches the parameter sets from the first IDR so later segments can start
     * immediately without waiting for the stream to re-supply them.
     */
    private void maybeRollSegment(Buffer buf) {
        if (mParamSets == null) {
            byte[][] sets = extractParamSets(buf);
            if (sets != null) mParamSets = sets;
        }
        boolean overLimit = mSegmentBytes >= MAX_SEGMENT_BYTES
                || (mSegmentStartPtsUs >= 0
                        && buf.ptsUs - mSegmentStartPtsUs >= MAX_SEGMENT_DURATION_US);
        if (!overLimit || !VideoMuxer.isKeyFrame(mVideoCodec, buf.data, buf.length)) {
            return;
        }
        mMuxer.release();
        mSegmentIndex++;
        mMuxer = new VideoMuxer(mVideoCodec, segmentPath(mSegmentIndex),
                mWidth, mHeight, mParamSets);
        mSegmentBytes      = 0;
        mSegmentStartPtsUs = -1;
        Log.i(TAG, "rolled to segment " + mSegmentIndex);
    }

    private byte[][] extractParamSets(Buffer buf) {
        if (mVideoCodec == VideoCodec.H264) {
            byte[] sps = VideoMuxer.findNal(mVideoCodec, buf.data, buf.length, 7);
            byte[] pps = VideoMuxer.findNal(mVideoCodec, buf.data, buf.length, 8);
            return (sps != null && pps != null) ? new byte[][]{sps, pps} : null;
        }
        byte[] vps = VideoMuxer.findNal(mVideoCodec, buf.data, buf.length, 32);
        byte[] sps = VideoMuxer.findNal(mVideoCodec, buf.data, buf.length, 33);
        byte[] pps = VideoMuxer.findNal(mVideoCodec, buf.data, buf.length, 34);
        return (vps != null && sps != null && pps != null) ? new byte[][]{vps, sps, pps} : null;
    }

    /**
     * Stop recording and finalize the .mp4. The writer thread drains the queue
     * and releases the muxer on its way out; this only signals and joins it.
     */
    public synchronized void stop() {
        if (!mRunning && mThread == null) return;
        mRunning = false;
        Thread t = mThread;
        mThread  = null;
        if (t != null) {
            try {
                t.join(JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.e(TAG, "stop join interrupted");
            }
            if (t.isAlive()) {
                Log.w(TAG, "writer still draining at stop; mp4 finalizes asynchronously");
            }
        }
    }

    /**
     * Stop recording and wait until every queued access unit has been written and
     * both MP4 and optional raw outputs have been closed. Call this from a worker
     * thread when final statistics/files are required immediately after return.
     */
    public synchronized void stopAndWait() {
        if (!mRunning && mThread == null) return;
        mRunning = false;
        Thread t = mThread;
        mThread = null;
        if (t == null) return;
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "stopAndWait join interrupted");
        }
    }

    // -------------------------------------------------------------------------
    // Final stats are stable after stopAndWait(); used by capture_summary.json.
    // -------------------------------------------------------------------------

    /** Total frames dropped because the byte-capacity ceiling was reached. */
    public long getDroppedFrames()  { return mDroppedFrames; }

    /** Total frames successfully written to MediaMuxer. */
    public long getWrittenFrames()  { return mWrittenFrames; }

    /** Frames ignored before the first decodable keyframe/parameter-set boundary. */
    public long getStartupSkippedFrames() { return mStartupSkippedFrames; }

    /** Frames not written because MediaMuxer failed (including later terminal failures). */
    public long getMuxerFailedFrames() { return mMuxerFailedFrames; }

    /** Frames successfully copied to the optional diagnostic Annex-B file. */
    public long getRawWrittenFrames() { return mRawWrittenFrames; }

    /** Raw diagnostic open/write/close failures. */
    public long getRawWriteErrors() { return mRawWriteErrors; }

    public boolean isRawDiagnosticEnabled() { return mRawPath != null; }

    /** Peak in-queue payload in bytes, observed over the entire recording session. */
    public long getPeakQueueBytes() { return mPeakQueueBytes; }
}
