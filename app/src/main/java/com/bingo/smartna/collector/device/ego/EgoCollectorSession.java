package com.bingo.smartna.collector.device.ego;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import com.bingo.smartna.collector.data.Prefs;
import com.bingo.smartna.collector.device.ego.view.OBGLView;
import com.orbbec.obsensor.Config;
import com.orbbec.obsensor.Device;
import com.orbbec.obsensor.Frame;
import com.orbbec.obsensor.FrameSet;
import com.orbbec.obsensor.OBContext;
import com.orbbec.obsensor.Pipeline;
import com.orbbec.obsensor.Sensor;
import com.orbbec.obsensor.StreamProfile;
import com.orbbec.obsensor.StreamProfileList;
import com.orbbec.obsensor.VideoStreamProfile;
import com.orbbec.obsensor.types.Format;
import com.orbbec.obsensor.types.FrameAggregateOutputMode;
import com.orbbec.obsensor.types.FrameType;
import com.orbbec.obsensor.types.SensorType;
import com.orbbec.obsensor.types.StreamType;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Shared Ego net-device session for camera debug / capture.
 * Preview matches {@link EgoSampleNetActivity}: YUYV → {@link OBGLView},
 * H264/H265 → {@link TextureView} + {@link VideoDecoder}.
 */
public final class EgoCollectorSession {
    private static final String TAG = "EgoCollectorSession";
    private static final String ROOT_DIR = "Orbbec/Capture";
    private static final EgoCollectorSession INSTANCE = new EgoCollectorSession();

    public interface Listener {
        void onStatus(String message);

        void onStreamingChanged(boolean streaming, boolean stereo);

        void onError(String message);
    }

    public static final class PreviewTargets {
        public OBGLView left;
        public OBGLView right;
        public TextureView leftTv;
        public TextureView rightTv;
        public View rightPanel;
    }

    private static final class FrameBuffer {
        byte[] data;
        int length;
        long tsUs;
    }

    private final Object lock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private Activity activity;
    private Listener listener;
    private PreviewTargets targets;

    private OBContext obContext;
    private Device device;
    private Pipeline pipeline;
    private Thread videoThread;
    private volatile boolean streamRunning;
    private volatile boolean stereo;
    private volatile boolean releasing;

    private Format leftFormat;
    private Format rightFormat;
    private int leftW;
    private int leftH;
    private int rightW;
    private int rightH;
    private VideoCodec leftCodec;
    private VideoCodec rightCodec;

    private VideoDecoder leftDecoder = new VideoDecoder();
    private VideoDecoder rightDecoder = new VideoDecoder();
    private final LinkedBlockingQueue<FrameBuffer> leftDecodeQueue = new LinkedBlockingQueue<>(8);
    private final LinkedBlockingQueue<FrameBuffer> rightDecodeQueue = new LinkedBlockingQueue<>(8);
    private final LinkedBlockingQueue<FrameBuffer> decodePool = new LinkedBlockingQueue<>(16);
    private Thread leftDecodeThread;
    private Thread rightDecodeThread;

    private volatile StreamTimestampRecorder csvRecorder;
    private volatile Mp4Recorder leftMp4;
    private volatile Mp4Recorder rightMp4;
    private File currentSessionDir;
    private File lastSessionDir;
    private boolean glPaused = true;

    public static EgoCollectorSession get() {
        return INSTANCE;
    }

    public boolean isStreaming() {
        return streamRunning;
    }

    public boolean isCollecting() {
        return csvRecorder != null;
    }

    public File getLastSessionDir() {
        return lastSessionDir;
    }

    public void attach(Activity host, PreviewTargets preview, Listener callback) {
        synchronized (lock) {
            activity = host;
            targets = preview;
            listener = callback;
            releasing = false;
        }
        worker.execute(this::ensureStreaming);
    }

    public void onHostPause() {
        glPaused = true;
        worker.execute(this::resetDecoders);
        mainHandler.post(() -> {
            PreviewTargets t = targets;
            if (t == null) {
                return;
            }
            pauseGl(t.left);
            pauseGl(t.right);
        });
    }

    public void onHostResume() {
        glPaused = false;
        mainHandler.post(this::applyLayout);
        if (device != null && pipeline != null && !streamRunning && !releasing) {
            worker.execute(this::openStreamLocked);
        }
    }

    public void detach(Activity host, boolean releaseDevice) {
        boolean doRelease;
        synchronized (lock) {
            if (activity != null && activity != host) {
                return;
            }
            if (activity == host) {
                activity = null;
                targets = null;
                listener = null;
            }
            doRelease = releaseDevice;
            if (doRelease) {
                releasing = true;
            }
        }
        if (doRelease) {
            worker.execute(this::fullRelease);
        }
    }

    /** 没有页面附着时释放设备，避免上传后离开采集链路泄漏。 */
    public void releaseIfIdle() {
        synchronized (lock) {
            if (activity != null || releasing) {
                return;
            }
            releasing = true;
        }
        worker.execute(this::fullRelease);
    }

    public boolean startCollecting() {
        Activity host = activity;
        if (host == null || !streamRunning) {
            postError("设备未开流");
            return false;
        }
        if (csvRecorder != null) {
            return true;
        }
        if (!ensureCaptureStorageAccess(host)) {
            return false;
        }
        String session = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        File dir = new File(getCaptureRootDir(), session);
        StreamTimestampRecorder recorder = new StreamTimestampRecorder(dir, session);
        if (!recorder.start()) {
            postError("采集启动失败");
            return false;
        }
        if (leftCodec != null && leftW > 0 && leftH > 0) {
            String stem = stereo ? "left_color_" + session : "color_" + session;
            Mp4Recorder rec = new Mp4Recorder(leftCodec,
                    new File(dir, stem + ".mp4").getAbsolutePath(), leftW, leftH, null);
            rec.start();
            leftMp4 = rec;
        }
        if (stereo && rightCodec != null && rightW > 0 && rightH > 0) {
            Mp4Recorder rec = new Mp4Recorder(rightCodec,
                    new File(dir, "right_color_" + session + ".mp4").getAbsolutePath(),
                    rightW, rightH, null);
            rec.start();
            rightMp4 = rec;
        }
        currentSessionDir = dir;
        lastSessionDir = dir;
        csvRecorder = recorder;
        postStatus("采集中 " + session);
        return true;
    }

    public void stopCollecting() {
        StreamTimestampRecorder recorder = csvRecorder;
        if (recorder == null) {
            return;
        }
        csvRecorder = null;
        Mp4Recorder left = leftMp4;
        Mp4Recorder right = rightMp4;
        leftMp4 = null;
        rightMp4 = null;
        File dir = currentSessionDir;
        currentSessionDir = null;
        new Thread(() -> {
            recorder.close();
            if (left != null) {
                left.stopAndWait();
            }
            if (right != null) {
                right.stopAndWait();
            }
            lastSessionDir = dir;
            postStatus("已保存");
        }, "CaptureFinalizer").start();
    }

    private void ensureStreaming() {
        if (releasing) {
            return;
        }
        if (streamRunning) {
            resetDecoders();
            applyLayout();
            startDecodeThreads();
            postStreaming();
            postStatus(statusLine());
            return;
        }
        Activity host = activity;
        if (host == null) {
            return;
        }
        Prefs prefs = new Prefs(host);
        String ip = prefs.getConnectedIp();
        if (ip == null || ip.isEmpty()) {
            postError("还没有已连接的设备");
            return;
        }
        int port = prefs.getConnectedPort();
        try {
            obContext = EgoSdkHolder.acquire(host.getApplicationContext(), null);
            postStatus("正在连接 " + ip + ":" + port);
            device = obContext.createNetDevice(ip, port);
            if (device == null) {
                postError("创建网络设备失败");
                EgoSdkHolder.release();
                obContext = null;
                return;
            }
            try {
                device.timerSyncWithHost();
            } catch (Exception ignored) {
            }
            pipeline = new Pipeline(device);
            stereo = detectStereo(device);
            openStreamLocked();
        } catch (Exception e) {
            Log.e(TAG, "ensureStreaming", e);
            postError("连接失败: " + e.getMessage());
            fullRelease();
        }
    }

    private void openStreamLocked() {
        if (streamRunning || device == null || pipeline == null || releasing) {
            return;
        }
        try {
            Config config = new Config();
            config.setFrameAggregateOutputMode(FrameAggregateOutputMode.OB_FRAME_AGGREGATE_OUTPUT_DISABLE);
            SensorType leftType = stereo ? SensorType.COLOR_LEFT : SensorType.COLOR;
            StreamProfile leftProfile = pickColorProfile(pipeline, leftType);
            if (leftProfile == null) {
                postError("没有可用的彩色流");
                return;
            }
            VideoStreamProfile leftVp = leftProfile.as(stereo ? StreamType.COLOR_LEFT : StreamType.COLOR);
            leftFormat = leftProfile.getFormat();
            leftCodec = VideoCodec.fromFormat(leftFormat);
            leftW = leftVp.getWidth();
            leftH = leftVp.getHeight();
            config.enableStream(leftProfile);
            leftProfile.close();

            if (stereo) {
                StreamProfile rightProfile = pickColorProfile(pipeline, SensorType.COLOR_RIGHT);
                if (rightProfile != null) {
                    VideoStreamProfile rightVp = rightProfile.as(StreamType.COLOR_RIGHT);
                    rightFormat = rightProfile.getFormat();
                    rightCodec = VideoCodec.fromFormat(rightFormat);
                    rightW = rightVp.getWidth();
                    rightH = rightVp.getHeight();
                    config.enableStream(rightProfile);
                    rightProfile.close();
                }
            }
            pipeline.start(config);
            config.close();
            streamRunning = true;
            glPaused = false;
            applyLayout();
            startDecodeThreads();
            videoThread = new Thread(this::videoLoop, "EgoCollectorVideo");
            videoThread.start();
            postStreaming();
            postStatus(statusLine());
        } catch (Exception e) {
            Log.e(TAG, "openStream", e);
            postError("开流失败: " + e.getMessage());
        }
    }

    private void videoLoop() {
        while (streamRunning && !releasing) {
            try (FrameSet frameSet = pipeline.waitForFrameSet(100)) {
                if (frameSet == null) {
                    continue;
                }
                Frame leftFrame = frameSet.getFrame(stereo ? FrameType.COLOR_LEFT : FrameType.COLOR);
                Frame rightFrame = stereo ? frameSet.getFrame(FrameType.COLOR_RIGHT) : null;
                try {
                    if (leftFrame != null) {
                        handleFrame(leftFrame, true);
                    }
                    if (rightFrame != null) {
                        handleFrame(rightFrame, false);
                    }
                } finally {
                    if (leftFrame != null) {
                        leftFrame.close();
                    }
                    if (rightFrame != null) {
                        rightFrame.close();
                    }
                }
            } catch (Exception e) {
                if (streamRunning) {
                    Log.e(TAG, "videoLoop: " + e.getMessage());
                }
            }
        }
    }

    private void handleFrame(Frame frame, boolean left) {
        StreamTimestampRecorder csv = csvRecorder;
        if (csv != null) {
            try {
                csv.logVideoTimestamp(
                        left ? (stereo ? StreamTimestampRecorder.Stream.LEFT_COLOR
                                : StreamTimestampRecorder.Stream.COLOR)
                                : StreamTimestampRecorder.Stream.RIGHT_COLOR,
                        frame.getTimeStampUs().longValue(),
                        frame.getIndex());
            } catch (Exception ignored) {
            }
        }
        Mp4Recorder mp4 = left ? leftMp4 : rightMp4;
        Format format = left ? leftFormat : rightFormat;
        if (mp4 != null && (format == Format.H264 || format == Format.H265 || format == Format.HEVC)) {
            try {
                mp4.offer(frame, frame.getTimeStampUs().longValue());
            } catch (Exception ignored) {
            }
        }
        if (glPaused) {
            return;
        }
        offerPreview(frame, left);
    }

    private void fullRelease() {
        stopCollecting();
        streamRunning = false;
        joinThread(videoThread);
        videoThread = null;
        joinThread(leftDecodeThread);
        leftDecodeThread = null;
        joinThread(rightDecodeThread);
        rightDecodeThread = null;
        resetDecoders();
        leftDecodeQueue.clear();
        rightDecodeQueue.clear();
        if (pipeline != null) {
            try {
                pipeline.stop();
            } catch (Exception ignored) {
            }
            try {
                pipeline.close();
            } catch (Exception ignored) {
            }
            pipeline = null;
        }
        if (device != null) {
            try {
                device.close();
            } catch (Exception ignored) {
            }
            device = null;
        }
        if (obContext != null) {
            EgoSdkHolder.release();
            obContext = null;
        }
        leftFormat = null;
        rightFormat = null;
        leftCodec = null;
        rightCodec = null;
        postStreaming();
    }

    private void offerPreview(Frame frame, boolean left) {
        int size = frame.getDataSize();
        if (size <= 0) {
            return;
        }
        FrameBuffer buf = decodePool.poll();
        if (buf == null) {
            buf = new FrameBuffer();
        }
        if (buf.data == null || buf.data.length < size) {
            buf.data = new byte[size];
        }
        buf.length = size;
        try {
            buf.tsUs = frame.getTimeStampUs().longValue();
            frame.getData(buf.data);
        } catch (Exception e) {
            decodePool.offer(buf);
            return;
        }
        LinkedBlockingQueue<FrameBuffer> queue = left ? leftDecodeQueue : rightDecodeQueue;
        if (!queue.offer(buf)) {
            decodePool.offer(buf);
        }
    }

    private void startDecodeThreads() {
        if (leftDecodeThread == null || !leftDecodeThread.isAlive()) {
            leftDecodeThread = new Thread(() -> decodeLoop(true), "EgoDecodeL");
            leftDecodeThread.start();
        }
        if (rightDecodeThread == null || !rightDecodeThread.isAlive()) {
            rightDecodeThread = new Thread(() -> decodeLoop(false), "EgoDecodeR");
            rightDecodeThread.start();
        }
    }

    private void decodeLoop(boolean left) {
        LinkedBlockingQueue<FrameBuffer> queue = left ? leftDecodeQueue : rightDecodeQueue;
        while (streamRunning && !releasing) {
            try {
                FrameBuffer buf = queue.poll(100, TimeUnit.MILLISECONDS);
                if (buf == null) {
                    continue;
                }
                try {
                    if (glPaused) {
                        continue;
                    }
                    PreviewTargets t = targets;
                    if (t == null) {
                        continue;
                    }
                    Format format = left ? leftFormat : rightFormat;
                    VideoCodec codec = left ? leftCodec : rightCodec;
                    TextureView tv = left ? t.leftTv : t.rightTv;
                    OBGLView gl = left ? t.left : t.right;
                    int w = left ? leftW : rightW;
                    int h = left ? leftH : rightH;
                    if (codec != null && tv != null) {
                        decodeToView(left ? leftDecoder : rightDecoder, codec, tv, w, h, buf);
                    } else if (format == Format.YUYV && gl != null) {
                        gl.update(w, h, StreamType.COLOR, Format.YUYV, buf.data, 1.0f);
                    }
                } finally {
                    decodePool.offer(buf);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Log.e(TAG, "decodeLoop: " + e.getMessage());
            }
        }
    }

    private void decodeToView(VideoDecoder decoder, VideoCodec codec, TextureView view,
                              int width, int height, FrameBuffer buf) {
        if (!decoder.isStarted()) {
            if (codec == null || width <= 0 || height <= 0 || !view.isAvailable()) {
                return;
            }
            Surface surface = new Surface(view.getSurfaceTexture());
            if (!decoder.start(codec, width, height, surface)) {
                surface.release();
                return;
            }
        }
        decoder.decode(buf.data, buf.length, buf.tsUs);
    }

    private synchronized void resetDecoders() {
        leftDecoder.release();
        rightDecoder.release();
        leftDecoder = new VideoDecoder();
        rightDecoder = new VideoDecoder();
    }

    private static void joinThread(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(1500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static StreamProfile pickColorProfile(Pipeline pipeline, SensorType type) {
        StreamProfileList list = pipeline.getStreamProfileList(type);
        if (list == null) {
            return null;
        }
        StreamProfile yuyv = null;
        StreamProfile compressed = null;
        try {
            for (int i = 0; i < list.getCount(); i++) {
                StreamProfile profile = list.getProfile(i);
                Format format = profile.getFormat();
                if (format == Format.YUYV && yuyv == null) {
                    yuyv = profile;
                } else if ((format == Format.H264 || format == Format.H265 || format == Format.HEVC)
                        && compressed == null) {
                    compressed = profile;
                } else {
                    profile.close();
                }
            }
        } finally {
            list.close();
        }
        if (compressed != null) {
            if (yuyv != null) {
                yuyv.close();
            }
            return compressed;
        }
        return yuyv;
    }

    private static boolean detectStereo(Device device) {
        Set<SensorType> types = new HashSet<>();
        try {
            for (Sensor sensor : device.querySensors()) {
                SensorType type = sensor.getType();
                if (type != null) {
                    types.add(type);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "querySensors: " + e.getMessage());
        }
        return types.contains(SensorType.COLOR_LEFT) && types.contains(SensorType.COLOR_RIGHT);
    }

    private void applyLayout() {
        mainHandler.post(() -> {
            PreviewTargets t = targets;
            if (t == null) {
                return;
            }
            if (t.rightPanel != null) {
                t.rightPanel.setVisibility(stereo ? View.VISIBLE : View.GONE);
            }
            bindEye(leftFormat == Format.YUYV, t.leftTv, t.left);
            bindEye(stereo && rightFormat == Format.YUYV, t.rightTv, t.right);
            View slot = t.leftTv != null ? t.leftTv : t.left;
            if (slot != null) {
                slot.post(() -> {
                    adjustViewAspect(t.leftTv, leftW, leftH);
                    if (stereo) {
                        adjustViewAspect(t.rightTv, rightW, rightH);
                    }
                });
            }
            glPaused = false;
        });
    }

    private void bindEye(boolean rawYuyv, TextureView tv, OBGLView gl) {
        if (tv != null) {
            tv.setVisibility(rawYuyv ? View.GONE : View.VISIBLE);
        }
        if (gl == null) {
            return;
        }
        if (rawYuyv) {
            gl.setVisibility(View.VISIBLE);
            resumeGl(gl);
        } else {
            pauseGl(gl);
            gl.setVisibility(View.GONE);
        }
    }

    /**
     * 把 TextureView 收成帧的宽高比（contain），避免 match_parent 把画面拉扁/拉长。
     * 与 {@link EgoSampleNetActivity} / {@link OBGLView} 同一套算法。
     */
    private void adjustViewAspect(TextureView view, int frameW, int frameH) {
        if (view == null || frameW <= 0 || frameH <= 0) {
            return;
        }
        view.post(() -> {
            ViewGroup parent = (ViewGroup) view.getParent();
            if (parent == null) {
                return;
            }
            if (parent.getWidth() <= 0 || parent.getHeight() <= 0) {
                parent.post(() -> adjustViewAspect(view, frameW, frameH));
                return;
            }
            int targetWidth = parent.getWidth();
            int targetHeight = parent.getWidth() * frameH / frameW;
            if (targetHeight > parent.getHeight()) {
                targetHeight = parent.getHeight();
                targetWidth = parent.getHeight() * frameW / frameH;
            }
            ViewGroup.LayoutParams params = view.getLayoutParams();
            params.width = targetWidth;
            params.height = targetHeight;
            view.setLayoutParams(params);
        });
    }

    private void pauseGl(OBGLView view) {
        if (view == null) {
            return;
        }
        try {
            view.onPause();
        } catch (Exception ignored) {
        }
    }

    private void resumeGl(OBGLView view) {
        if (view == null) {
            return;
        }
        try {
            view.onResume();
        } catch (Exception ignored) {
        }
    }

    private String statusLine() {
        String left = leftFormat == null ? "-" : leftW + "x" + leftH + " " + leftFormat;
        if (!stereo) {
            return left;
        }
        String right = rightFormat == null ? "-" : rightW + "x" + rightH + " " + rightFormat;
        return left + " | " + right;
    }

    private void postStatus(String message) {
        mainHandler.post(() -> {
            Listener l = listener;
            if (l != null) {
                l.onStatus(message);
            }
        });
    }

    private void postError(String message) {
        mainHandler.post(() -> {
            Listener l = listener;
            if (l != null) {
                l.onError(message);
            }
        });
    }

    private void postStreaming() {
        boolean running = streamRunning;
        boolean isStereo = stereo;
        mainHandler.post(() -> {
            Listener l = listener;
            if (l != null) {
                l.onStreamingChanged(running, isStereo);
            }
        });
    }

    private static File getCaptureRootDir() {
        return new File(Environment.getExternalStorageDirectory(), ROOT_DIR);
    }

    private boolean ensureCaptureStorageAccess(Activity host) {
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            postError("存储不可用");
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                || Environment.isExternalStorageManager()) {
            return true;
        }
        postError("请授予所有文件访问权限后再采集");
        try {
            host.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + host.getPackageName())));
        } catch (Exception e) {
            host.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
        return false;
    }
}
