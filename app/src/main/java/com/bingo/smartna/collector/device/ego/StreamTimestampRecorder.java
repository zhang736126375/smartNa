package com.bingo.smartna.collector.device.ego;

import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.EnumMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Central manager that records the <b>device timestamp</b> of every received
 * frame for the Ego streams into separate CSV files.
 *
 * <p>A stream's file is created lazily on its first row, so only streams that
 * actually produce data leave a file behind. Possible files under {@code dir}
 * (one session = one set):
 * <pre>
 *   audio_&lt;session&gt;.csv        header: timestamp_us
 *   color_&lt;session&gt;.csv        header: timestamp_us
 *   left_color_&lt;session&gt;.csv   header: timestamp_us
 *   right_color_&lt;session&gt;.csv  header: timestamp_us
 *   imu_&lt;session&gt;.csv          header: timestamp_us,x,y,z,type
 * </pre>
 *
 * <p>Threading: every producer (audio / color / accel / gyro callbacks) only
 * builds a line and performs a non-blocking {@code offer} onto an internal
 * queue; a single background thread does all disk I/O. Thus no producer —
 * including the latency-sensitive frame-fetch loop — ever blocks on the file
 * system. If the writer cannot keep up the queue overflows and rows are dropped
 * (and counted) rather than stalling a producer.
 */
public class StreamTimestampRecorder {

    private static final String TAG = "StreamTsRecorder";

    /** Logical CSV a row belongs to. */
    public enum Stream {
        AUDIO("audio", "timestamp_us"),
        COLOR("color", "timestamp_us,frame_index"),
        LEFT_COLOR("left_color", "timestamp_us,frame_index"),
        RIGHT_COLOR("right_color", "timestamp_us,frame_index"),
        SIDE_LEFT_COLOR("side_left_color", "timestamp_us,frame_index"),
        SIDE_RIGHT_COLOR("side_right_color", "timestamp_us,frame_index"),
        IMU("imu", "timestamp_us,x,y,z,type");

        final String fileName;
        final String header;

        Stream(String fileName, String header) {
            this.fileName = fileName;
            this.header = header;
        }
    }

    // Bounded so a disk stall cannot grow the queue without limit and OOM the
    // process. Capacity holds ~30s of the busiest producer (IMU ~1kHz + the video
    // streams, ~2.2k rows/s), a large cushion for the bursty I/O contention of
    // recording several files at once (two mp4 + wav + csv). Under normal I/O it
    // stays near-empty (a LinkedBlockingQueue is a linked list — capacity is a
    // count, not a preallocation), so the cushion costs nothing until a stall; at
    // worst it caps memory at ~65536 * ~64B ≈ 4MB. Only a stall longer than the
    // cushion drops (and counts) rows via offer() below — degrading the CSV, never
    // stalling the latency-sensitive frame-fetch loop.
    private static final int QUEUE_CAPACITY = 65536;
    private static final int FLUSH_INTERVAL_MS = 1000;
    private static final int WRITER_BUFFER_BYTES = 1 << 16;
    private static final int CLOSE_JOIN_TIMEOUT_MS = 5000;

    private static final class Row {
        final Stream stream;
        final long ts;
        final long frameIndex; // -1 for AUDIO/IMU rows (no frame index)
        final float x, y, z;
        final String imuType;  // non-null only for IMU rows

        Row(Stream stream, long ts, long frameIndex,
            float x, float y, float z, String imuType) {
            this.stream     = stream;
            this.ts         = ts;
            this.frameIndex = frameIndex;
            this.x          = x;
            this.y          = y;
            this.z          = z;
            this.imuType    = imuType;
        }
    }

    private final File dir;
    private final String session;
    private final LinkedBlockingQueue<Row> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    // Writers are created lazily on the first row of each stream, so a stream
    // that never produces a row gets no CSV file. Accessed only by the writer
    // thread (and by close()'s post-join drain, after the thread has stopped) —
    // single-threaded, hence a plain EnumMap with no synchronization.
    private final EnumMap<Stream, BufferedWriter> writers =
            new EnumMap<>(Stream.class);

    private Thread writerThread;
    private volatile boolean running;
    private volatile long droppedRows;

    // Scratch buffers owned exclusively by the writer thread (and by close()'s
    // post-join drain, which runs after the thread has stopped) — single-threaded
    // access, so no synchronization and no per-line String allocation.
    private final StringBuilder lineBuilder = new StringBuilder(64);
    private char[] charBuf = new char[64];

    /**
     * @param dir     directory the CSV files are created in (created if absent)
     * @param session token embedded in each file name so a set groups together,
     *                e.g. {@code "20260704_153000"}
     */
    public StreamTimestampRecorder(File dir, String session) {
        this.dir = dir;
        this.session = session;
    }

    /**
     * Start the writer thread. Files are not created here — each stream's CSV
     * is opened lazily the first time it produces a row, so an unused stream
     * leaves no file behind.
     */
    public synchronized boolean start() {
        if (running) {
            return true;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "cannot create dir: " + dir.getAbsolutePath());
            return false;
        }
        queue.clear();
        droppedRows = 0;
        running = true;
        writerThread = new Thread(this::writeLoop, "CsvTsWriter");
        writerThread.start();
        Log.i(TAG, "recording started, session=" + session);
        return true;
    }

    private BufferedWriter open(Stream stream) throws IOException {
        File f = new File(dir, stream.fileName + "_" + session + ".csv");
        BufferedWriter w = new BufferedWriter(new FileWriter(f), WRITER_BUFFER_BYTES);
        w.write(stream.header);
        w.write('\n');
        return w;
    }

    // -------------------------------------------------------------------------
    // Producer API — non-blocking, callable from any thread
    // -------------------------------------------------------------------------

    /** Log one timestamp row for AUDIO (no frame index). */
    public void logTimestamp(Stream stream, long timestampUs) {
        offer(new Row(stream, timestampUs, -1L, 0f, 0f, 0f, null));
    }

    /** Log one video frame row: {@code timestamp_us,frame_index}. */
    public void logVideoTimestamp(Stream stream, long timestampUs, long frameIndex) {
        offer(new Row(stream, timestampUs, frameIndex, 0f, 0f, 0f, null));
    }

    /** Log one IMU row: {@code timestamp_us,x,y,z,type}. */
    public void logImu(long timestampUs, float x, float y, float z, String type) {
        offer(new Row(Stream.IMU, timestampUs, -1L, x, y, z, type));
    }

    private void offer(Row row) {
        if (!running) {
            return;
        }
        if (!queue.offer(row)) {
            // Writer fell behind — drop this row instead of blocking the producer
            droppedRows++;
        }
    }

    /** Total rows dropped due to writer overrun. Safe to call from any thread. */
    public long getDroppedRows() { return droppedRows; }

    // -------------------------------------------------------------------------

    private void writeLoop() {
        long lastFlush = System.currentTimeMillis();
        while (running || !queue.isEmpty()) {
            try {
                Row row = queue.poll(200, TimeUnit.MILLISECONDS);
                if (row != null) {
                    write(row);
                }
                long now = System.currentTimeMillis();
                if (now - lastFlush >= FLUSH_INTERVAL_MS) {
                    flushAll();
                    lastFlush = now;
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                Log.e(TAG, "writeLoop: " + e.getMessage());
            }
        }
        flushAll();
    }

    private void write(Row row) {
        BufferedWriter w = writerFor(row.stream);
        if (w == null) {
            return;
        }
        // Format on the writer thread with reused buffers — keeps all string /
        // float-to-text work (and its garbage) off the producer threads.
        StringBuilder sb = lineBuilder;
        sb.setLength(0);
        sb.append(row.ts);
        if (row.stream == Stream.IMU) {
            sb.append(',').append(row.x).append(',').append(row.y)
              .append(',').append(row.z).append(',').append(row.imuType);
        } else if (row.frameIndex >= 0) {
            // Video streams: append frame index as last column so index gaps
            // are directly visible in the CSV without needing logcat.
            sb.append(',').append(row.frameIndex);
        }
        sb.append('\n');
        int len = sb.length();
        if (charBuf.length < len) {
            charBuf = new char[Math.max(len, charBuf.length * 2)];
        }
        sb.getChars(0, len, charBuf, 0);
        try {
            w.write(charBuf, 0, len);
        } catch (IOException e) {
            Log.e(TAG, "write " + row.stream + ": " + e.getMessage());
        }
    }

    /**
     * Return the writer for {@code stream}, creating its file (with header) on
     * first use. Runs only on the writer thread, so the lazy put needs no lock.
     * Returns null if the file cannot be opened — that stream's rows are dropped.
     */
    private BufferedWriter writerFor(Stream stream) {
        BufferedWriter w = writers.get(stream);
        if (w == null) {
            try {
                w = open(stream);
                writers.put(stream, w);
            } catch (IOException e) {
                Log.e(TAG, "open csv " + stream + " failed: " + e.getMessage());
                return null;
            }
        }
        return w;
    }

    private void flushAll() {
        for (BufferedWriter w : writers.values()) {
            flush(w);
        }
    }

    private void flush(BufferedWriter w) {
        if (w == null) {
            return;
        }
        try {
            w.flush();
        } catch (IOException e) {
            Log.e(TAG, "flush: " + e.getMessage());
        }
    }

    /**
     * Stop the writer thread and finalize the files.
     *
     * <p>Ordering guarantees no data loss and no races: the writer thread owns
     * the writers while it lives; we only touch them here after {@link Thread#join}
     * confirms it has terminated. Every row a producer enqueued while
     * {@code running} was still true is written — the writer drains what it can,
     * then we drain the shutdown-boundary stragglers below.
     */
    public synchronized void close() {
        Thread t = writerThread;
        if (t == null) {
            closeWriters();  // never started — nothing enqueued, writers is empty
            return;
        }
        running = false;     // writer drains the remaining queue, then exits its loop
        try {
            t.join(CLOSE_JOIN_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "close join interrupted");
        }
        if (t.isAlive()) {
            // Pathological (disk stalled beyond the timeout): the thread is still
            // draining. Do NOT touch the writers / scratch buffers concurrently.
            // It sees running==false, drains the queue and flushes on exit, so no
            // data is lost — only the file handles finalize asynchronously.
            Log.w(TAG, "writer still draining at close; files finalize asynchronously");
            writerThread = null;
            return;
        }
        writerThread = null;
        // Thread confirmed dead → exclusive access. Drain any rows enqueued in the
        // boundary window (a producer that read running==true just before the flip).
        Row row;
        while ((row = queue.poll()) != null) {
            write(row);
        }
        flushAll();
        closeWriters();
        if (droppedRows > 0) {
            Log.w(TAG, "dropped " + droppedRows + " rows (writer overrun)");
        }
        Log.i(TAG, "recording stopped, session=" + session);
    }

    private void closeWriters() {
        for (BufferedWriter w : writers.values()) {
            closeWriter(w);
        }
        writers.clear();
    }

    private void closeWriter(BufferedWriter w) {
        if (w == null) {
            return;
        }
        try {
            w.close();
        } catch (IOException e) {
            Log.e(TAG, "closeWriter: " + e.getMessage());
        }
    }
}
