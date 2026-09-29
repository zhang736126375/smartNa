package com.bingo.smartna.collector.device.ego;

import android.util.Log;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Asynchronous, non-blocking PCM→WAV recorder. Writes little-endian PCM (as
 * delivered by the audio sensor) into a standard .wav file <b>off the calling
 * thread</b>.
 *
 * <p>Designed so recording never disturbs audio reception:
 * <ul>
 *   <li>{@link #offer} copies the PCM into a pooled buffer and enqueues it, then
 *       returns at once — the latency-sensitive audio callback never blocks on
 *       disk I/O.</li>
 *   <li>A single writer thread owns the file handle exclusively (header +
 *       samples + finalize), so no cross-thread lock is needed.</li>
 *   <li>Buffers are recycled through a free list, so steady-state allocation is
 *       near zero.</li>
 *   <li>If the writer falls behind, the bounded queue drops (and counts) the
 *       chunk instead of blocking — recording degrades, audio reception does
 *       not.</li>
 * </ul>
 *
 * <p>Threading: {@link #offer} is called from the single audio-callback thread;
 * {@link #start}/{@link #stop} from the UI thread. The queue and pool are
 * thread-safe; the file is touched only on the writer thread (after {@code start}
 * hands it off via {@link Thread#start}).
 */
public class WavRecorder {

    private static final String TAG = "WavRecorder";
    // Audio frames are small and infrequent vs video; a few seconds of headroom.
    private static final int QUEUE_CAPACITY = 200;
    private static final int JOIN_TIMEOUT_MS = 2000;
    private static final int HEADER_SIZE = 44;

    /** A reusable PCM chunk buffer pooled via the free list. */
    private static final class Buffer {
        byte[] data;
        int length;
    }

    private final String mPath;
    private final int mSampleRate;
    private final int mChannels;
    private final int mBitsPerSample;

    private final LinkedBlockingQueue<Buffer> mQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final LinkedBlockingQueue<Buffer> mPool = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private RandomAccessFile mFile;
    private long mDataBytes;
    private Thread mThread;
    private volatile boolean mRunning;
    private volatile long mDroppedFrames;

    public WavRecorder(String path, int sampleRate, int channels, int bitsPerSample) {
        mPath = path;
        mSampleRate = sampleRate;
        mChannels = channels;
        mBitsPerSample = bitsPerSample;
    }

    /**
     * Open the file, reserve a placeholder header and start the writer thread.
     * @return false if the file could not be opened (nothing is recorded).
     */
    public synchronized boolean start() {
        if (mRunning) {
            return true;
        }
        try {
            mFile = new RandomAccessFile(mPath, "rw");
            mFile.setLength(0);  // truncate any stale file of the same name
            mDataBytes = 0;
            writeHeader(0);  // placeholder — patched with real sizes on stop
        } catch (IOException e) {
            Log.e(TAG, "start failed: " + e.getMessage());
            closeQuietly();
            return false;
        }
        mQueue.clear();
        mDroppedFrames = 0;
        mRunning = true;
        mThread = new Thread(this::writeLoop, "WavRecorder");
        mThread.start();
        return true;
    }

    /**
     * Enqueue one PCM chunk. Non-blocking: copies the bytes into a pooled buffer
     * and returns at once. Drops (and counts) the chunk if the queue is full
     * instead of blocking the caller.
     */
    public void offer(byte[] data, int length) {
        if (!mRunning) {
            return;
        }
        Buffer buf = mPool.poll();
        if (buf == null || buf.data == null || buf.data.length < length) {
            buf = new Buffer();
            buf.data = new byte[length];
        }
        System.arraycopy(data, 0, buf.data, 0, length);
        buf.length = length;
        if (!mQueue.offer(buf)) {
            mDroppedFrames++;
            mPool.offer(buf);  // queue rejected it — return the buffer to the pool
        }
    }

    private void writeLoop() {
        // Loop condition drains the queue before exit, so all enqueued PCM is
        // written even after stop() flips mRunning to false.
        while (mRunning || !mQueue.isEmpty()) {
            try {
                Buffer buf = mQueue.poll(100, TimeUnit.MILLISECONDS);
                if (buf == null) {
                    continue;
                }
                try {
                    mFile.write(buf.data, 0, buf.length);
                    mDataBytes += buf.length;
                } catch (IOException e) {
                    Log.e(TAG, "write: " + e.getMessage());
                } finally {
                    mPool.offer(buf);  // recycle regardless of write outcome
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                Log.e(TAG, "writeLoop: " + e.getMessage());
            }
        }
        // Patch the header with real sizes and close — on the writer thread,
        // the sole owner of the file handle.
        try {
            writeHeader(mDataBytes);
        } catch (IOException e) {
            Log.e(TAG, "patch header: " + e.getMessage());
        }
        closeQuietly();
        if (mDroppedFrames > 0) {
            Log.w(TAG, "dropped " + mDroppedFrames + " frames (writer overrun)");
        }
        Log.i(TAG, "wav saved: " + mPath + " (" + mDataBytes + " PCM bytes)");
    }

    /**
     * Stop recording and finalize the .wav. The writer thread drains the queue,
     * patches the header and closes the file on its way out; this only signals
     * and joins it.
     */
    public synchronized void stop() {
        if (!mRunning && mThread == null) {
            return;
        }
        mRunning = false;
        Thread t = mThread;
        mThread = null;
        if (t != null) {
            try {
                t.join(JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.e(TAG, "stop join interrupted");
            }
            if (t.isAlive()) {
                Log.w(TAG, "writer still draining at stop; wav finalizes asynchronously");
            }
        }
    }

    // -------------------------------------------------------------------------
    // WAV header — written by the owning thread only (start, then writeLoop)
    // -------------------------------------------------------------------------

    private void writeHeader(long dataSize) throws IOException {
        int byteRate = mSampleRate * mChannels * mBitsPerSample / 8;
        int blockAlign = mChannels * mBitsPerSample / 8;
        mFile.seek(0);
        // RIFF chunk
        mFile.writeBytes("RIFF");
        writeLeInt((int) (36 + dataSize));
        mFile.writeBytes("WAVE");
        // fmt  chunk
        mFile.writeBytes("fmt ");
        writeLeInt(16);
        writeLeShort((short) 1);                 // PCM
        writeLeShort((short) mChannels);
        writeLeInt(mSampleRate);
        writeLeInt(byteRate);
        writeLeShort((short) blockAlign);
        writeLeShort((short) mBitsPerSample);
        // data chunk
        mFile.writeBytes("data");
        writeLeInt((int) dataSize);
        // Reposition to the end of the samples so subsequent PCM appends after
        // the header (relevant right after the initial placeholder write).
        mFile.seek(HEADER_SIZE + dataSize);
    }

    private void writeLeInt(int v) throws IOException {
        mFile.write(v & 0xFF);
        mFile.write((v >> 8) & 0xFF);
        mFile.write((v >> 16) & 0xFF);
        mFile.write((v >> 24) & 0xFF);
    }

    private void writeLeShort(short v) throws IOException {
        mFile.write(v & 0xFF);
        mFile.write((v >> 8) & 0xFF);
    }

    private void closeQuietly() {
        if (mFile != null) {
            try {
                mFile.close();
            } catch (IOException ignore) {
            }
            mFile = null;
        }
    }
}
