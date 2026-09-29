package com.bingo.smartna.collector.device.ego;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.orbbec.obsensor.AccelFrame;
import com.orbbec.obsensor.AccelStreamProfile;
import com.orbbec.obsensor.AudioStreamProfile;
import com.orbbec.obsensor.Config;
import com.orbbec.obsensor.Device;
import com.orbbec.obsensor.DeviceChangedCallback;
import com.orbbec.obsensor.DeviceList;
import com.orbbec.obsensor.Frame;
import com.orbbec.obsensor.FrameSet;
import com.orbbec.obsensor.GyroFrame;
import com.orbbec.obsensor.GyroStreamProfile;
import com.orbbec.obsensor.OBException;
import com.orbbec.obsensor.Pipeline;
import com.orbbec.obsensor.Sensor;
import com.orbbec.obsensor.StreamProfile;
import com.orbbec.obsensor.StreamProfileList;
import com.orbbec.obsensor.VideoStreamProfile;
import com.orbbec.obsensor.property.DeviceProperty;
import com.orbbec.obsensor.types.DeviceInfo;
import com.orbbec.obsensor.types.EgoErrorFlag;
import com.orbbec.obsensor.types.EgoStateCallbackThread;
import com.orbbec.obsensor.types.EgoStateFlag;
import com.orbbec.obsensor.types.EgoStateReport;
import com.orbbec.obsensor.types.Format;
import com.orbbec.obsensor.types.FrameAggregateOutputMode;
import com.orbbec.obsensor.types.FrameType;
import com.orbbec.obsensor.types.PermissionType;
import com.orbbec.obsensor.types.SensorType;
import com.orbbec.obsensor.types.StreamType;
import com.bingo.smartna.R;
import com.bingo.smartna.collector.data.Prefs;
import com.bingo.smartna.collector.device.ego.view.OBGLView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumSet;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Ego 设备综合示例 Activity：USB 连接下演示多路彩色、音频、IMU 开流、预览、录制与设备状态监控。
 * <p>
 * <b>整体流程：</b>
 * <ol>
 *   <li>{@link #onCreate} → {@link EgoSdkActivity#initSDK()} 初始化 SDK</li>
 *   <li>设备插入 → {@link #mDeviceChangedCallback#onDeviceAttach} 打开设备、弹流配置对话框</li>
 *   <li>用户确认 {@link EgoStreamSelection} → {@link #openStream()} 按选择启 Pipeline + Sensor</li>
 *   <li>视频：{@link #mVideoRunnable} 取帧 → 解码预览 / 写 MP4；音频/IMU 各自独立回调</li>
 *   <li>点击 Collect → {@link #startCollecting} 写 CSV 时间戳、MP4、WAV、标定 YAML</li>
 *   <li>Activity 暂停/销毁 → 停流、释放解码器与设备（均在 {@link #mLifecycleExecutor} 后台线程）</li>
 * </ol>
 * <p>
 * <b>彩色布局模式 {@link ColorMode}：</b>
 * <ul>
 *   <li>MONO — 单目 COLOR</li>
 *   <li>STEREO — 左右 COLOR_LEFT / COLOR_RIGHT</li>
 *   <li>QUAD — 四目（含 COLOR_SIDE_LEFT / COLOR_SIDE_RIGHT）</li>
 * </ul>
 */
public class EgoSampleNetActivity extends EgoSdkActivity {

    public static final String EXTRA_IP = "extra_ip";
    public static final String EXTRA_PORT = "extra_port";
    public static final int DEFAULT_NET_PORT = 8090;

    public static void start(Context context, String ip, int port) {
        Intent intent = new Intent(context, EgoSampleNetActivity.class);
        intent.putExtra(EXTRA_IP, ip);
        intent.putExtra(EXTRA_PORT, port > 0 ? port : DEFAULT_NET_PORT);
        context.startActivity(intent);
    }

    private static final String TAG = EgoSampleNetActivity.class.getSimpleName();

    /** WAV 默认采样率（传感器未上报时兜底；实际以 {@link #mWavSampleRate} 为准） */
    private static final int WAV_SAMPLE_RATE = 48000;
    /** WAV 默认声道数 */
    private static final int WAV_CHANNELS = 1;
    /** WAV 默认位深 */
    private static final int WAV_BITS_PER_SAMPLE = 16;

    /** 音频信息 TextView 刷新间隔（毫秒），避免音频回调 ~90 次/秒刷 UI 卡主线程 */
    private static final long AUDIO_UI_REFRESH_INTERVAL_MS = 250;

    /** 采集文件根目录（外部存储下 Orbbec/Capture，每次 Collect 在其下建时间戳子目录） */
    private static final String ROOT_DIR = "Orbbec/Capture";

    /** 开始采集前要求的最小剩余磁盘空间（500MB） */
    private static final long MIN_FREE_DISK_BYTES = 500L * 1024 * 1024;
    /** 开始采集前要求的最小可用内存（200MB） */
    private static final long MIN_AVAIL_MEM_BYTES = 200L * 1024 * 1024;

    /** 调试开关：true 时仅开音频流，用于排查音频噪声是否与视频/IMU 争用 USB 有关 */
    private static final boolean AUDIO_ONLY_DEBUG = false;

    /** 四目测试开关：true 时四目模式只开左右主相机，不开侧向相机 */
    private static final boolean QUAD_ONLY_LEFT_RIGHT_COLOR_FOR_TEST = false;

    /** 诊断开关：为每个 MP4 额外写一份 Annex-B 裸码流（*_raw.h264 / *_raw.h265） */
    private static final boolean DUMP_ENCODED_RAW_FOR_DIAGNOSTIC = true;

    /** 是否已与主机做过时间同步（每个设备连接只做一次 timerSyncWithHost） */
    private boolean timeSyncWithHost = false;

    /** 当前设备彩色相机布局：无 / 单目 / 双目 / 四目 */
    private enum ColorMode { NONE, MONO, STEREO, QUAD }

    /** 当前已打开的 Ego 设备 */
    private Device mDevice;
    /** 视频 Pipeline，负责多路彩色 FrameSet 拉流 */
    private Pipeline mPipeline;
    /** 正在销毁时为 true，防止 SDK 设备回调与 onDestroy 并发释放同一 Device */
    private volatile boolean mReleasing;
    /** 单线程执行器：开流/停流/释放设备等 native 操作均在此线程，避免阻塞 UI */
    private final ExecutorService mLifecycleExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "EgoLifecycle");
        thread.setDaemon(true);
        return thread;
    });
    /** onPause 已请求停流，避免重复提交 stop 任务 */
    private volatile boolean mPauseStopRequested;
    private String mPendingAutoIp = null;
    private EditText mNetIpInput;
    private EditText mNetPortInput;
    private Button mOpenNetDeviceButton;
    /** 音频 Sensor（PCM 回调，不走 Pipeline） */
    private Sensor mAudioSensor;
    /** 加速度计 Sensor */
    private Sensor mAccelSensor;
    /** 陀螺仪 Sensor */
    private Sensor mGyroSensor;
    /** 视频取帧线程（运行 {@link #mVideoRunnable}） */
    private Thread mThread;
    /** 当前是否正在开流 */
    private volatile boolean mIsStreamRunning;

    /** 左/主彩色预览：H264/H265 经 MediaCodec 解码后渲染到此 TextureView */
    private TextureView mLeftColorView;
    private TextureView mRightColorView;
    /** 左/右 YUYV 原始彩色：经 OpenGL（OBGLView）直接渲染 */
    private OBGLView mLeftRawColorView;
    private OBGLView mRightRawColorView;
    /** 主线程访问：YUYV 的 GLSurfaceView 隐藏前必须先 pause */
    private boolean mLeftRawRendererPaused;
    private boolean mRightRawRendererPaused;
    private View mColorDivider;
    private View mRightColorPanel;
    /** 各彩色流分辨率/帧率/解码状态叠加文字 */
    private TextView mLeftColorInfoView;
    private TextView mRightColorInfoView;
    private volatile String mLeftDecoderStatus = "";
    private volatile String mRightDecoderStatus = "";
    private TextView mAudioInfoView;
    private TextView mAccelInfoView;
    private TextView mGyroInfoView;
    /** Ego 设备状态（电量、存储、错误标志等） */
    private TextView mEgoStateInfoView;
    /** 显示当前采集目录路径 */
    private TextView mCsvPathView;
    /** Collect / Stop 采集按钮 */
    private Button mCsvCollectButton;
    /** 打开流参数配置对话框 */
    private Button mStreamConfigButton;
    private EditText mAudioPlaybackVolumeInput;
    /** 是否显示设备端语音与音量测试 UI */
    private static final boolean ENABLE_VOICE_TEST = true;
    /** 设备扬声器 TTS 测试（验证真实语音链路） */
    private EgoTtsTester mTtsTester;
    /** 用户在对话框中确认的流配置（分辨率、码率、开关等） */
    private volatile EgoStreamSelection mStreamSelection;
    /** 换 profile 后需在首帧到达时只读刷新码率范围（流式传输中不能写码率） */
    private volatile boolean mColorBitrateRangeRefreshPending;
    /** CSV 时间戳记录器；非 null 表示正在采集（各生产者线程通过 volatile 读取） */
    private volatile StreamTimestampRecorder mCsvRecorder;
    /** 到达设定录制时长后自动停止采集 */
    private final Runnable mAutoStopCollectRunnable = () -> {
        if (mCsvRecorder != null) {
            stopCollecting();
            showToast("Recording time reached; saved");
        }
    };
    /** 各彩色路 MP4 异步录制器，Collect 时创建 */
    private volatile Mp4Recorder mLeftMp4Recorder;
    private volatile Mp4Recorder mRightMp4Recorder;
    /** 音频 WAV 异步录制器 */
    private volatile WavRecorder mWavRecorder;
    /** 写入 WAV 前对 PCM 做直流阻断高通滤波 */
    private volatile PcmProcessor mAudioProcessor;
    private final VideoDecoder mLeftDecoder = new VideoDecoder();
    private final VideoDecoder mRightDecoder = new VideoDecoder();
    /** 解码队列容量；取帧线程只 offer，解码在独立线程，避免阻塞 Pipeline 丢帧 */
    private static final int DECODE_QUEUE_CAPACITY = 60;
    /** 主左/右彩色 H264/H265 帧解码队列 */
    private final LinkedBlockingQueue<FrameBuffer> mLeftDecodeQueue =
            new LinkedBlockingQueue<>(DECODE_QUEUE_CAPACITY);
    private final LinkedBlockingQueue<FrameBuffer> mRightDecodeQueue =
            new LinkedBlockingQueue<>(DECODE_QUEUE_CAPACITY);
    /** 帧数据对象池，复用 byte[]，减少 GC 导致取帧线程卡顿 */
    private final LinkedBlockingQueue<FrameBuffer> mLeftBufferPool =
            new LinkedBlockingQueue<>(DECODE_QUEUE_CAPACITY);
    private final LinkedBlockingQueue<FrameBuffer> mRightBufferPool =
            new LinkedBlockingQueue<>(DECODE_QUEUE_CAPACITY);
    private Thread mLeftDecodeThread;
    private Thread mRightDecodeThread;
    /** 当前选中 profile 的宽高（用于 MP4 与界面比例） */
    private int mLeftColorW, mLeftColorH;
    private int mRightColorW, mRightColorH;
    /** 各路彩色编码格式（H264/H265），驱动解码器与 MP4 封装 */
    private VideoCodec mLeftColorCodec;
    private VideoCodec mRightColorCodec;
    private volatile Format mLeftColorFormat;
    private volatile Format mRightColorFormat;
    /** 预览 overlay 上的流描述（如 "Left 1920x1080 H264"） */
    private volatile String mLeftColorDesc = "Left Color";
    private volatile String mRightColorDesc = "Right Color";

    // ---------- 四目侧向相机（仅 QUAD 模式使用） ----------
    private TextureView mSideLeftColorView;
    private TextureView mSideRightColorView;
    private OBGLView mSideLeftRawColorView;
    private OBGLView mSideRightRawColorView;
    private boolean mSideLeftRawRendererPaused;
    private boolean mSideRightRawRendererPaused;
    private View mSideLeftColorPanel;
    private View mSideRightColorPanel;
    private View mSideLeftDivider;
    private View mSideRightDivider;
    private TextView mSideLeftColorInfoView;
    private TextView mSideRightColorInfoView;
    private volatile String mSideLeftDecoderStatus = "";
    private volatile String mSideRightDecoderStatus = "";
    private final VideoDecoder mSideLeftDecoder = new VideoDecoder();
    private final VideoDecoder mSideRightDecoder = new VideoDecoder();
    private final LinkedBlockingQueue<FrameBuffer> mSideLeftDecodeQueue =
            new LinkedBlockingQueue<>(DECODE_QUEUE_CAPACITY);
    private final LinkedBlockingQueue<FrameBuffer> mSideRightDecodeQueue =
            new LinkedBlockingQueue<>(DECODE_QUEUE_CAPACITY);
    private final LinkedBlockingQueue<FrameBuffer> mSideLeftBufferPool =
            new LinkedBlockingQueue<>(DECODE_QUEUE_CAPACITY);
    private final LinkedBlockingQueue<FrameBuffer> mSideRightBufferPool =
            new LinkedBlockingQueue<>(DECODE_QUEUE_CAPACITY);
    private Thread mSideLeftDecodeThread;
    private Thread mSideRightDecodeThread;
    private int mSideLeftColorW, mSideLeftColorH;
    private int mSideRightColorW, mSideRightColorH;
    private VideoCodec mSideLeftColorCodec;
    private VideoCodec mSideRightColorCodec;
    private volatile Format mSideLeftColorFormat;
    private volatile Format mSideRightColorFormat;
    private volatile String mSideLeftColorDesc = "Side Left";
    private volatile String mSideRightColorDesc = "Side Right";
    private volatile Mp4Recorder mSideLeftMp4Recorder;
    private volatile Mp4Recorder mSideRightMp4Recorder;
    /** 当前设备实际支持的 Sensor 类型集合 */
    private final EnumSet<SensorType> mSupportedSensors = EnumSet.noneOf(SensorType.class);
    /** 根据设备能力确定的彩色布局模式 */
    private ColorMode mColorMode = ColorMode.NONE;

    private volatile long mAudioFrameCount;
    /** 实际 WAV 参数（来自 AudioStreamProfile，须与 PCM 一致） */
    private volatile int mWavSampleRate = WAV_SAMPLE_RATE;
    private volatile int mWavChannels = WAV_CHANNELS;
    private volatile int mWavBits = WAV_BITS_PER_SAMPLE;
    /** 上次刷新音频 UI 的时间戳 */
    private long mLastAudioUiUpdateMs;
    /** 音频回调线程复用的 PCM 临时缓冲 */
    private byte[] mAudioScratch;

    /** 当前采集会话目录与会话 ID（用于 capture_summary.json） */
    private volatile File   mCurrentSessionDir;
    private volatile String mCurrentSessionId;

    /** 预取的标定 YAML 缓存（对齐 / IMU），避免 Collect 时阻塞 USB */
    private volatile byte[] mAlignCalibData;
    private volatile byte[] mImuCalibData;
    /** 标定读取互斥锁，防止 prefetch 与 Collect 并发 getRawData */
    private final Object mCalibLock = new Object();

    /** USB 设备插拔回调：插入时打开设备并弹配置框，拔出时断开 */
    private final DeviceChangedCallback mDeviceChangedCallback = new DeviceChangedCallback() {

        /** 设备插入：取第一台设备，查询能力，注册 Ego 状态回调，弹出流配置 */
        @Override
        public void onDeviceAttach(DeviceList deviceList) {
            if (mReleasing) {
                deviceList.close();
                return;
            }
            try {
                if (mDevice == null) {
                    mDevice = deviceList.getDevice(0);
                    if (!refreshDeviceCapabilities()) {
                        requestDeviceDisconnect();
                        return;
                    }
                    refreshAudioPlaybackVolumeRange(mDevice);
                    registerEgoStateCallback();
                    runOnUiThread(EgoSampleNetActivity.this::showStreamConfig);
                }
            } catch (Exception e) {
                Log.e(TAG, "onDeviceAttach: " + e.getMessage());
            } finally {
                deviceList.close();
            }
        }

        /** 设备拔出：若 UID 匹配当前设备则请求断开 */
        @Override
        public void onDeviceDetach(DeviceList deviceList) {
            if (mReleasing) {
                deviceList.close();
                return;
            }
            try {
                if (mDevice != null) {
                    for (int i = 0; i < deviceList.getDeviceCount(); i++) {
                        String uid = deviceList.getUid(i);
                        DeviceInfo deviceInfo = mDevice.getInfo();
                        if (null != deviceInfo && TextUtils.equals(uid, deviceInfo.getUid())) {
                            requestDeviceDisconnect();
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "onDeviceDetach: " + e.getMessage());
            } finally {
                deviceList.close();
            }
        }
    };

    @Override
    protected DeviceChangedCallback getDeviceChangedCallback() {
        return mDeviceChangedCallback;
    }

    /**
     * 查询设备支持的 Sensor，并据此设置 {@link #mColorMode}（单目/双目/四目）。
     *
     * @return 至少支持一种彩色模式时返回 true
     */
    private boolean refreshDeviceCapabilities() {
        mSupportedSensors.clear();
        try {
            for (Sensor sensor : mDevice.querySensors()) {
                SensorType type = sensor.getType();
                if (type != null) {
                    mSupportedSensors.add(type);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to query EGO sensor capabilities", e);
            return false;
        }

        if (EgoStreamSelection.isQuadColorDevice(mDevice)) {
            mColorMode = ColorMode.QUAD;
        } else if (mSupportedSensors.contains(SensorType.COLOR_LEFT)
                && mSupportedSensors.contains(SensorType.COLOR_RIGHT)) {
            mColorMode = ColorMode.STEREO;
        } else if (mSupportedSensors.contains(SensorType.COLOR)) {
            mColorMode = ColorMode.MONO;
        } else {
            mColorMode = ColorMode.NONE;
            showToast("Device does not support COLOR or COLOR_LEFT/COLOR_RIGHT.");
            return false;
        }
        updateColorLayout();
        return true;
    }

    /** 根据 {@link #mColorMode} 显示/隐藏右目与侧向预览面板，并调整布局。 */
    private void updateColorLayout() {
        final boolean stereo = mColorMode == ColorMode.STEREO;
        final boolean quad = mColorMode == ColorMode.QUAD;
        runOnUiThread(() -> {
            mRightColorPanel.setVisibility(stereo || quad ? View.VISIBLE : View.GONE);
            mColorDivider.setVisibility(View.GONE);
            mSideLeftColorPanel.setVisibility(quad ? View.VISIBLE : View.GONE);
            mSideRightColorPanel.setVisibility(quad ? View.VISIBLE : View.GONE);
            mSideLeftDivider.setVisibility(View.GONE);
            mSideRightDivider.setVisibility(View.GONE);
            layoutColorPanels(quad, stereo);
            mLeftColorInfoView.setText(stereo || quad ? "Left Color" : "Color");
            if (!stereo && !quad) {
                mRightColorInfoView.setText("Not supported");
            }
        });
    }

    /** 在容器内按单目/双目/四目分配各预览面板的绝对位置与大小。 */
    private void layoutColorPanels(boolean quad, boolean stereo) {
        final View leftPanel = (View) mLeftColorView.getParent();
        final ViewGroup container = (ViewGroup) leftPanel.getParent();
        container.post(() -> {
            int width = container.getWidth();
            int height = container.getHeight();
            if (width <= 0 || height <= 0) return;
            if (quad) {
                int halfWidth = width / 2;
                int halfHeight = height / 2;
                setColorPanelBounds(mSideLeftColorPanel, 0, 0, halfWidth, halfHeight);
                setColorPanelBounds(leftPanel, halfWidth, 0, width - halfWidth, halfHeight);
                setColorPanelBounds(mRightColorPanel, 0, halfHeight, halfWidth, height - halfHeight);
                setColorPanelBounds(mSideRightColorPanel, halfWidth, halfHeight,
                        width - halfWidth, height - halfHeight);
            } else if (stereo) {
                int halfWidth = width / 2;
                setColorPanelBounds(leftPanel, 0, 0, halfWidth, height);
                setColorPanelBounds(mRightColorPanel, halfWidth, 0, width - halfWidth, height);
            } else {
                setColorPanelBounds(leftPanel, 0, 0, width, height);
            }
        });
    }

    /** 设置某个彩色预览面板的 FrameLayout 边距与宽高。 */
    private static void setColorPanelBounds(View panel, int left, int top,
                                            int width, int height) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) panel.getLayoutParams();
        params.width = width;
        params.height = height;
        params.leftMargin = left;
        params.topMargin = top;
        panel.setLayoutParams(params);
    }

    /** 采集/录制支持的彩色压缩格式（H264/H265）。YUYV 仅预览不录 MP4。 */
    private static boolean isColorCaptureFormatSupported(Format format) {
        return format == Format.H264 || format == Format.H265 || format == Format.HEVC || format == Format.YUYV;
    }

    /** 流配置中是否开启视频解码预览（默认开启）。 */
    private boolean isVideoDecodeEnabled() {
        EgoStreamSelection selection = mStreamSelection;
        return selection == null || selection.videoDecodeEnabled;
    }

    /** 流配置中是否开启 MP4 录制（默认开启）。 */
    private boolean isVideoRecordingEnabled() {
        EgoStreamSelection selection = mStreamSelection;
        return selection == null || selection.videoRecordingEnabled;
    }

    /** 开流前设置彩色码率为固定或动态模式。 */
    private void applyColorBitrateMode() {
        DeviceProperty dynamic = DeviceProperty.OB_PROP_COLOR_DYNAMIC_BITRATE_ENABLE_BOOL;
        boolean dynamicEnabled = mStreamSelection.dynamicBitrateEnabled;
        try {
            if (!mDevice.isPropertySupported(dynamic, PermissionType.OB_PERMISSION_READ_WRITE)) {
                Log.w(TAG, "Color dynamic bitrate control is unsupported");
                showToast("Color dynamic bitrate control is unsupported");
                return;
            }
            mDevice.setPropertyValueB(dynamic, dynamicEnabled);
        } catch (Exception e) {
            Log.w(TAG, "apply color bitrate mode failed: " + e.getMessage());
            showToast("Apply color bitrate mode failed");
        }
    }

    /** 开流前查询码率范围并写入用户选择的固定码率。 */
    private void refreshColorBitrateRange(EgoStreamSelection selection) {
        refreshColorBitrateRange(selection, false);
    }

    /**
     * 查询彩色码率 min/max/step，并在非只读模式下写入目标码率。
     * <p>
     * 注意：Pipeline 运行中固件拒绝写码率（errorCode 7），因此：
     * <ul>
     *   <li>{@code readOnly=false} — 在 pipeline.start() 之前调用，写入初始码率</li>
     *   <li>{@code readOnly=true} — 首帧之后只读刷新 UI 显示的实际码率</li>
     * </ul>
     */
    private void refreshColorBitrateRange(EgoStreamSelection selection, boolean readOnly) {
        if (mStreamSelection != selection) return;
        DeviceProperty bitrate = DeviceProperty.OB_PROP_COLOR_BITRATE_INT;
        try {
            if (!mDevice.isPropertySupported(bitrate, PermissionType.OB_PERMISSION_READ_WRITE)) {
                Log.w(TAG, "Color bitrate control is unsupported");
                return;
            }
            int min = mDevice.getMinRangeI(bitrate);
            int max = mDevice.getMaxRangeI(bitrate);
            int step = mDevice.getStepI(bitrate);
            if (min <= 0 || max < min || step <= 0) {
                Log.w(TAG, "Invalid color bitrate range: " + min + ".." + max + ", step=" + step);
                return;
            }
            selection.colorBitrateMin = min;
            selection.colorBitrateMax = max;
            selection.colorBitrateStep = step;
            if (selection.dynamicBitrateEnabled) {
                // 动态码率模式下固件自选实际码率，读回供 UI 显示
                selection.colorBitrate = mDevice.getPropertyValueI(bitrate);
                return;
            }
            // 流式传输中只读刷新，不写码率
            if (readOnly) {
                selection.colorBitrate = mDevice.getPropertyValueI(bitrate);
                return;
            }
            int value = selection.colorBitrate;
            if (value < min || value > max || (step > 0 && (value - min) % step != 0)) {
                int bounded = Math.max(min, Math.min(max, value));
                value = min + ((bounded - min) / step) * step;
                selection.colorBitrate = value;
                Log.w(TAG, "Color bitrate adjusted to " + value + ", range=" + min + ".." + max + ", step=" + step);
            }
            mDevice.setPropertyValueI(bitrate, value);
            selection.colorBitrate = mDevice.getPropertyValueI(bitrate);
        } catch (Exception e) {
            Log.w(TAG, "refresh color bitrate range failed: " + e.getMessage());
            showToast("Refresh color bitrate range failed");
        }
    }

    /** 首帧到达后只读刷新码率范围（确认新 profile 已生效）。 */
    private void requestColorBitrateRangeRefreshAfterFirstFrame() {
        // 忽略旧取帧线程迟到的帧（换 profile 后 mThread 已更换）
        if (Thread.currentThread() != mThread) return;
        if (!mColorBitrateRangeRefreshPending) return;
        mColorBitrateRangeRefreshPending = false;
        final EgoStreamSelection selection = mStreamSelection;
        mLifecycleExecutor.execute(() -> {
            if (mIsStreamRunning && mStreamSelection == selection) {
                refreshColorBitrateRange(selection, true);
            }
        });
    }

    /** 该路是否使用 YUYV 原始格式（走 OpenGL 而非 MediaCodec）。 */
    private static boolean isRawYuyv(EgoStreamSelection.VideoSpec spec, boolean enabled) {
        return enabled && spec != null && spec.format == Format.YUYV;
    }

    /**
     * 开流前在主线程切换 YUYV/OpenGL 与 H264/H265/TextureView 预览模式，
     * 避免 GLSurfaceView 与 MediaCodec Surface 生命周期冲突。
     */
    private boolean prepareColorRenderers(EgoStreamSelection selection) {
        CountDownLatch completed = new CountDownLatch(1);
        final boolean[] prepared = {true};
        runOnUiThread(() -> {
            try {
                setColorRendererMode(true, isRawYuyv(selection.leftVideo, selection.leftEnabled));
                setColorRendererMode(false, isRawYuyv(selection.rightVideo, selection.rightEnabled));
                if (mColorMode == ColorMode.QUAD) {
                    setColorRendererModeSide(true, isRawYuyv(selection.sideLeftVideo, selection.sideLeftEnabled));
                    setColorRendererModeSide(false, isRawYuyv(selection.sideRightVideo, selection.sideRightEnabled));
                }
            } catch (Exception e) {
                prepared[0] = false;
                Log.e(TAG, "Failed to prepare color renderers", e);
            } finally {
                completed.countDown();
            }
        });
        try {
            if (!completed.await(1500, TimeUnit.MILLISECONDS)) {
                Log.e(TAG, "Timed out preparing color renderers before stream start");
                return false;
            }
            return prepared[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 切换主左/右预览：YUYV 用 OBGLView，压缩格式用 TextureView。必须在主线程调用。 */
    private void setColorRendererMode(boolean isLeft, boolean rawYuyv) {
        TextureView decoderView = isLeft ? mLeftColorView : mRightColorView;
        OBGLView rawView = isLeft ? mLeftRawColorView : mRightRawColorView;
        if (rawYuyv) {
            decoderView.setVisibility(View.GONE);
            rawView.setVisibility(View.VISIBLE);
            resumeRawRenderer(isLeft, rawView);
        } else {
            pauseRawRenderer(isLeft, rawView);
            rawView.setVisibility(View.GONE);
            decoderView.setVisibility(View.VISIBLE);
        }
    }

    /** 暂停主左/右 YUYV OpenGL 渲染。 */
    private void pauseRawRenderer(boolean isLeft, OBGLView rawView) {
        boolean paused = isLeft ? mLeftRawRendererPaused : mRightRawRendererPaused;
        if (!paused) {
            rawView.onPause();
            if (isLeft) mLeftRawRendererPaused = true;
            else mRightRawRendererPaused = true;
        }
    }

    /** 恢复主左/右 YUYV OpenGL 渲染。 */
    private void resumeRawRenderer(boolean isLeft, OBGLView rawView) {
        boolean paused = isLeft ? mLeftRawRendererPaused : mRightRawRendererPaused;
        if (paused) {
            rawView.onResume();
            if (isLeft) mLeftRawRendererPaused = false;
            else mRightRawRendererPaused = false;
        }
    }

    /** 侧向相机版 {@link #setColorRendererMode}，必须在主线程调用。 */
    private void setColorRendererModeSide(boolean isSideLeft, boolean rawYuyv) {
        TextureView decoderView = isSideLeft ? mSideLeftColorView : mSideRightColorView;
        OBGLView rawView = isSideLeft ? mSideLeftRawColorView : mSideRightRawColorView;
        if (rawYuyv) {
            decoderView.setVisibility(View.GONE);
            rawView.setVisibility(View.VISIBLE);
            resumeRawRendererSide(isSideLeft, rawView);
        } else {
            pauseRawRendererSide(isSideLeft, rawView);
            rawView.setVisibility(View.GONE);
            decoderView.setVisibility(View.VISIBLE);
        }
    }

    /** 暂停侧向 YUYV OpenGL 渲染。 */
    private void pauseRawRendererSide(boolean isSideLeft, OBGLView rawView) {
        boolean paused = isSideLeft ? mSideLeftRawRendererPaused : mSideRightRawRendererPaused;
        if (!paused) {
            rawView.onPause();
            if (isSideLeft) mSideLeftRawRendererPaused = true;
            else mSideRightRawRendererPaused = true;
        }
    }

    /** 恢复侧向 YUYV OpenGL 渲染。 */
    private void resumeRawRendererSide(boolean isSideLeft, OBGLView rawView) {
        boolean paused = isSideLeft ? mSideLeftRawRendererPaused : mSideRightRawRendererPaused;
        if (paused) {
            rawView.onResume();
            if (isSideLeft) mSideLeftRawRendererPaused = false;
            else mSideRightRawRendererPaused = false;
        }
    }


    private void openNetDeviceFromUi() {
        if (mOBContext == null) {
            showToast("SDK 未初始化");
            return;
        }
        String address = mNetIpInput.getText() == null ? "" : mNetIpInput.getText().toString().trim();
        String portText = mNetPortInput.getText() == null ? "8090" : mNetPortInput.getText().toString().trim();
        if (TextUtils.isEmpty(address)) {
            showToast("请输入 IP");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            showToast("端口号无效");
            return;
        }
        if (port <= 0) {
            showToast("端口号必须大于 0");
            return;
        }

        mOpenNetDeviceButton.setEnabled(false);
        showToast("正在连接 " + address + ":" + port + "...");
        final String finalAddress = address;
        final int finalPort = port;
        mLifecycleExecutor.execute(() -> {
            closeStream();
            releaseDevice();
            try {
                Device device = mOBContext.createNetDevice(finalAddress, finalPort);
                if (device == null) {
                    runOnUiThread(() -> {
                        showToast("创建网络设备失败");
                        mOpenNetDeviceButton.setEnabled(true);
                    });
                    return;
                }
                mDevice = device;
                if (!refreshDeviceCapabilities()) {
                    releaseDevice();
                    runOnUiThread(() -> mOpenNetDeviceButton.setEnabled(true));
                    return;
                }
                refreshAudioPlaybackVolumeRange(mDevice);
                registerEgoStateCallback();
                new Prefs(EgoSampleNetActivity.this).saveConnectedNetDevice(finalAddress, finalPort);
                runOnUiThread(() -> {
                    showToast("已连接到 " + finalAddress + ":" + finalPort);
                    mOpenNetDeviceButton.setEnabled(false);
                });
                runOnUiThread(this::showStreamConfig);
            } catch (Exception e) {
                Log.e(TAG, "createNetDevice failed: " + e.getMessage());
                runOnUiThread(() -> {
                    showToast("打开设备失败: " + e.getMessage());
                    mOpenNetDeviceButton.setEnabled(true);
                });
            }
        });
    }

    /** 初始化布局、绑定控件、注册 Surface 监听、调用 {@link EgoSdkActivity#initSDK()}。 */
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setTitle("Ego-Sample");
        setContentView(R.layout.activity_ego_net_sample);
        mLeftColorView = findViewById(R.id.ego_left_color);
        mRightColorView = findViewById(R.id.ego_right_color);
        mLeftRawColorView = findViewById(R.id.ego_left_color_raw);
        mRightRawColorView = findViewById(R.id.ego_right_color_raw);
        mColorDivider = findViewById(R.id.ego_color_divider);
        mRightColorPanel = findViewById(R.id.ego_right_color_panel);
        mSideLeftColorView = findViewById(R.id.ego_side_left_color);
        mSideRightColorView = findViewById(R.id.ego_side_right_color);
        mSideLeftRawColorView = findViewById(R.id.ego_side_left_color_raw);
        mSideRightRawColorView = findViewById(R.id.ego_side_right_color_raw);
        mSideLeftColorPanel = findViewById(R.id.ego_side_left_color_panel);
        mSideRightColorPanel = findViewById(R.id.ego_side_right_color_panel);
        mSideLeftDivider = findViewById(R.id.ego_side_left_divider);
        mSideRightDivider = findViewById(R.id.ego_side_right_divider);
        mSideLeftColorInfoView = findViewById(R.id.ego_side_left_color_info);
        mSideRightColorInfoView = findViewById(R.id.ego_side_right_color_info);
        mLeftColorInfoView = findViewById(R.id.ego_left_color_info);
        mRightColorInfoView = findViewById(R.id.ego_right_color_info);
        mAudioInfoView = findViewById(R.id.ego_audio_info);
        mAccelInfoView = findViewById(R.id.ego_accel_info);
        mGyroInfoView = findViewById(R.id.ego_gyro_info);
        mEgoStateInfoView = findViewById(R.id.ego_state_info);
        mCsvCollectButton = findViewById(R.id.btn_csv_collect);
        mCsvCollectButton.setOnClickListener(v -> onCsvCollectClick());
        mStreamConfigButton = findViewById(R.id.btn_ego_stream_config);
        mStreamConfigButton.setText("Stream settings");
        mStreamConfigButton.setOnClickListener(v -> showStreamConfig());
        setupVoiceTestUi();
        mCsvPathView = findViewById(R.id.ego_csv_path_info);
        mCsvPathView.setText("Data: " + getCaptureRootDir().getAbsolutePath());
        // Surface 销毁时释放解码器，避免渲染到无效 Surface
        mLeftColorView.setSurfaceTextureListener(decoderSurfaceListener(mLeftDecoder));
        mRightColorView.setSurfaceTextureListener(decoderSurfaceListener(mRightDecoder));
        mSideLeftColorView.setSurfaceTextureListener(decoderSurfaceListener(mSideLeftDecoder));
        mSideRightColorView.setSurfaceTextureListener(decoderSurfaceListener(mSideRightDecoder));
        mNetIpInput = findViewById(R.id.ego_net_ip_input);
        mNetPortInput = findViewById(R.id.ego_net_port_input);
        mOpenNetDeviceButton = findViewById(R.id.btn_open_net_device);
        mOpenNetDeviceButton.setOnClickListener(v -> openNetDeviceFromUi());
        initSDK();

        String autoIp = getIntent().getStringExtra(EXTRA_IP);
        int autoPort = getIntent().getIntExtra(EXTRA_PORT, DEFAULT_NET_PORT);
        if (autoPort <= 0) {
            autoPort = DEFAULT_NET_PORT;
        }
        if (!TextUtils.isEmpty(autoIp)) {
            mNetIpInput.setText(autoIp);
            mNetPortInput.setText(String.valueOf(autoPort));
            mPendingAutoIp = autoIp;
        }
    }

    /**
     * TextureView 监听器：Surface 销毁时在后台释放对应 {@link VideoDecoder}，
     * 防止 Activity 切换后仍向无效 Surface 渲染。
     */
    private TextureView.SurfaceTextureListener decoderSurfaceListener(VideoDecoder decoder) {
        return new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) {
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture s) {
                requestDecoderRelease(decoder);
                return true;  // 由系统释放 SurfaceTexture
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture s) {
            }
        };
    }

    /** 隐藏所有彩色预览视图（保留信息 overlay），用于仅采集不预览模式。 */
    private void hideColorPreviews() {
        runOnUiThread(() -> {
            // 隐藏 GLSurfaceView 前必须先 pause，否则 GL 线程仍在跑
            pauseRawRenderer(true, mLeftRawColorView);
            pauseRawRenderer(false, mRightRawColorView);
            pauseRawRendererSide(true, mSideLeftRawColorView);
            pauseRawRendererSide(false, mSideRightRawColorView);
            mLeftColorView.setVisibility(View.INVISIBLE);
            mRightColorView.setVisibility(View.INVISIBLE);
            mSideLeftColorView.setVisibility(View.INVISIBLE);
            mSideRightColorView.setVisibility(View.INVISIBLE);
            mLeftRawColorView.setVisibility(View.GONE);
            mRightRawColorView.setVisibility(View.GONE);
            mSideLeftRawColorView.setVisibility(View.GONE);
            mSideRightRawColorView.setVisibility(View.GONE);
        });
    }

    /** 若设备与流配置仍在，则恢复开流。 */
    @Override
    protected void onResume() {
        super.onResume();
        if (mPendingAutoIp != null) {
            mPendingAutoIp = null;
            openNetDeviceFromUi();
            return;
        }
        if (mDevice != null && mStreamSelection != null && !mIsStreamRunning) {
            requestOpenStream();
        }
    }

    /** 暂停时先异步停止采集，再停流，避免 ANR。 */
    @Override
    protected void onPause() {
        stopCollecting();
        requestStopStreamOnly();
        requestStopTts(false);
        if (isVideoDecodeEnabled()) {
            pauseRawRenderer(true, mLeftRawColorView);
            pauseRawRenderer(false, mRightRawColorView);
            pauseRawRendererSide(true, mSideLeftRawColorView);
            pauseRawRendererSide(false, mSideRightRawColorView);
        }
        super.onPause();
    }

    /** 标记释放中，停止 TTS，在后台线程完整释放设备与 SDK。 */
    @Override
    protected void onDestroy() {
        mReleasing = true;
        requestStopTts(true);
        requestFullRelease();
        super.onDestroy();
    }

    /** 在 {@link #mLifecycleExecutor} 中异步调用 {@link #openStream()}。 */
    private void requestOpenStream() {
        mLifecycleExecutor.execute(() -> {
            if (!mReleasing) {
                mPauseStopRequested = false;
                openStream();
            }
        });
    }

    /** 弹出 {@link EgoStreamConfigDialog}，用户确认后重启流。 */
    private void showStreamConfig() {
        final Device device = mDevice;
        if (device == null) { showToast("请先连接 Ego 设备"); return; }
        mLifecycleExecutor.execute(() -> {
            if (mPipeline == null) mPipeline = new Pipeline(device);
            final Pipeline pipeline = mPipeline;
            runOnUiThread(() -> EgoStreamConfigDialog.show(this, device, pipeline,
                    mColorMode == ColorMode.STEREO || mColorMode == ColorMode.QUAD, mSupportedSensors.contains(SensorType.ACCEL),
                    mSupportedSensors.contains(SensorType.GYRO), true, true, mStreamSelection, selection -> {
                        mStreamSelection = selection;
                        mLifecycleExecutor.execute(() -> { stopStreamOnly(); openStream(); });
                    }));
        });
    }

    /** 仅停流不释放设备（用于 onPause）。 */
    private void requestStopStreamOnly() {
        if (mPauseStopRequested) {
            return;
        }
        mPauseStopRequested = true;
        mLifecycleExecutor.execute(this::stopStreamOnly);
    }

    /** 根据 {@link #ENABLE_VOICE_TEST} 绑定或隐藏设备端语音/音量测试控件。 */
    private void setupVoiceTestUi() {
        View voiceBtn = findViewById(R.id.btn_ego_voice_test);
        EditText volumeInput = findViewById(R.id.input_ego_volume);
        mAudioPlaybackVolumeInput = volumeInput;
        View volumeSetBtn = findViewById(R.id.btn_ego_volume_set);
        if (!ENABLE_VOICE_TEST) {
            voiceBtn.setVisibility(View.GONE);
            volumeInput.setVisibility(View.GONE);
            volumeSetBtn.setVisibility(View.GONE);
            return;
        }
        voiceBtn.setOnClickListener(v -> onVoiceTestClick());
        volumeSetBtn.setOnClickListener(v -> onAudioPlaybackVolumeSetClick(volumeInput));
        Device device = mDevice;
        if (device != null) {
            mLifecycleExecutor.execute(() -> {
                if (!mReleasing && device == mDevice) {
                    refreshAudioPlaybackVolumeRange(device);
                }
            });
        }
    }

    /** 读取固件报告的播放音量范围，显示在输入框 hint 中。 */
    private void refreshAudioPlaybackVolumeRange(Device device) {
        DeviceProperty property = DeviceProperty.OB_PROP_DEVICE_AUDIO_OUTPUT_VOLUME_INT;
        try {
            if (!device.isPropertySupported(property, PermissionType.OB_PERMISSION_READ)
                    || !device.isPropertySupported(property, PermissionType.OB_PERMISSION_WRITE)) {
                updateAudioPlaybackVolumeHint(device, "不支持");
                return;
            }
            int min = device.getMinRangeI(property);
            int max = device.getMaxRangeI(property);
            if (min > max) {
                Log.w(TAG, "Invalid speaker volume range: " + min + ".." + max);
                updateAudioPlaybackVolumeHint(device, "范围无效");
                return;
            }
            updateAudioPlaybackVolumeHint(device, min + " ~ " + max + " dB");
        } catch (Exception e) {
            Log.w(TAG, "Read speaker volume range failed: " + e.getMessage());
            updateAudioPlaybackVolumeHint(device, "范围不可用");
        }
    }

    /** 在主线程更新音量输入框 hint。 */
    private void updateAudioPlaybackVolumeHint(Device device, String hint) {
        runOnUiThread(() -> {
            if (device == mDevice && mAudioPlaybackVolumeInput != null) {
                mAudioPlaybackVolumeInput.setHint(hint);
            }
        });
    }

    /** Set the device-side speaker volume to the dB value entered by the user. */
    /** 用户点击设置音量：校验范围并写入设备属性。 */
    private void onAudioPlaybackVolumeSetClick(EditText volumeInput) {
        String text = volumeInput.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            volumeInput.setError("请输入音量值（dB）");
            return;
        }

        final int target;
        try {
            target = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            volumeInput.setError("请输入有效的整数音量值");
            return;
        }

        final Device device = mDevice;
        if (device == null) {
            showToast("请先连接 Ego 设备");
            return;
        }
        mLifecycleExecutor.execute(() -> {
            if (mReleasing || device != mDevice) {
                return;
            }
            DeviceProperty property = DeviceProperty.OB_PROP_DEVICE_AUDIO_OUTPUT_VOLUME_INT;
            try {
                if (!device.isPropertySupported(property, PermissionType.OB_PERMISSION_READ)
                        || !device.isPropertySupported(property, PermissionType.OB_PERMISSION_WRITE)) {
                    showToast("设备不支持扬声器音量调节");
                    return;
                }
                int min = device.getMinRangeI(property);
                int max = device.getMaxRangeI(property);
                if (target < min || target > max) {
                    showToast("音量范围: " + min + " ~ " + max + " dB");
                    return;
                }
                device.setAudioPlaybackVolume(target);
                showToast("扬声器音量: " + target + " dB");
            } catch (Exception e) {
                Log.w(TAG, "Set speaker volume failed: " + e.getMessage());
                showToast("扬声器音量调节失败: " + e.getMessage());
            }
        });
    }

    /**
     * Voice test: synthesize a short Chinese phrase on-device (TextToSpeech) and
     * convert it to 16 kHz PCM, then play it through Device.playAudioFile().
     * USB devices use the SDK's Android AudioTrack adapter; network devices use RTP.
     */
    /** 点击语音测试：通过 TTS 向设备扬声器播放测试语。 */
    private void onVoiceTestClick() {
        if (mTtsTester == null) {
            mTtsTester = new EgoTtsTester(this);
            mTtsTester.init();
        }
        if (!mTtsTester.isReady()) {
            showToast("TTS 初始化中，请稍后再次点击语音测试");
            return;
        }
        // Callback fires on a worker/binder thread; showToast marshals to UI.
        // The tester picks the phrase (Chinese if an offline voice exists, else English).
        final EgoTtsTester tester = mTtsTester;
        final Device device = mDevice;
        if (device == null) {
            showToast("请先连接 Ego 设备");
            return;
        }
        mLifecycleExecutor.execute(() -> {
            if (!mReleasing && device == mDevice) {
                tester.speakToSdkDevice(device, (ok, msg) -> {
                    if (ok && !mReleasing && device == mDevice) {
                        try {
                            // This sample treats each voice test as a complete
                            // playback session, so close 304 after the file drains.
                            device.stopAudioPlayback();
                        } catch (Exception e) {
                            Log.w(TAG, "Stop completed voice playback failed: " + e.getMessage());
                        }
                    }
                    showToast(msg);
                });
            }
        });
    }

    /**
     * Stop TTS voice playback off the UI thread. {@code release=true} also shuts
     * down the engine (teardown); {@code false} keeps it warm for the next resume.
     */
    /** 停止 TTS；{@code release=true} 时同时释放 {@link #mTtsTester}。 */
    private void requestStopTts(boolean release) {
        final EgoTtsTester tester = mTtsTester;
        final Device device = mDevice;
        if (release) {
            mTtsTester = null;
        }
        if (tester == null || mLifecycleExecutor.isShutdown()) {
            return;
        }
        mLifecycleExecutor.execute(() -> {
            tester.stopNetworkPlayback(device);
            if (release) {
                tester.release();
            }
        });
    }

    /** Device callbacks must not synchronously close potentially blocking SDK resources. */
    /** 请求断开当前设备（停流 + releaseDevice）。 */
    private void requestDeviceDisconnect() {
        mLifecycleExecutor.execute(() -> {
            closeStream();
            releaseDevice();
        });
    }

    /** Surface callbacks run on the UI thread; MediaCodec release does not. */
    /** 在生命周期线程异步释放指定解码器。 */
    private void requestDecoderRelease(VideoDecoder decoder) {
        if (!mReleasing) {
            mLifecycleExecutor.execute(decoder::release);
        }
    }

    /** Keep all native/USB teardown off Android's main thread. */
    /** 完整释放：停流、关设备、releaseSDK、关闭 lifecycle 线程池。 */
    private void requestFullRelease() {
        mLifecycleExecutor.execute(() -> {
            final long releaseStartMs = System.currentTimeMillis();
            try {
                closeStream();
                releaseDevice();
                releaseSDK();
            } finally {
                Log.i(TAG, "full release completed in "
                        + (System.currentTimeMillis() - releaseStartMs) + "ms");
                mLifecycleExecutor.shutdown();
            }
        });
    }

    /**
     * Close the native device exactly once. Synchronized + idempotent so the UI
     * thread (onDestroy) and the SDK callback thread (onDeviceDetach) can never
     * double-close it — a double-close destroys a libusb mutex the USB event
     * thread may still hold, which aborts the process in libusb_handle_events.
     */
    /** 关闭 Pipeline/Sensor、释放 Device，并清空标定缓存。 */
    private synchronized void releaseDevice() {
        if (mDevice != null) {
            try {
                mDevice.close();
            } catch (Exception e) {
                Log.e(TAG, "releaseDevice: " + e.getMessage());
            }
            timeSyncWithHost = false;
            mDevice = null;
            // Drop cached calib so a reconnected device re-prefetches its own.
            mAlignCalibData = null;
            mImuCalibData = null;
        }
    }

    /** 注册 Ego 设备状态回调，更新 {@link #mEgoStateInfoView}。 */
    private void registerEgoStateCallback() {
        try {
            mDevice.setEgoStateCallback(this::updateEgoStateInfo, EgoStateCallbackThread.MAIN);
            runOnUiThread(() -> mEgoStateInfoView.setText("EGO State: listening..."));
        } catch (Exception e) {
            Log.w(TAG, "EGO state callback is unavailable", e);
            runOnUiThread(() -> mEgoStateInfoView.setText("EGO State: unavailable"));
        }
    }

    /** 将 Ego 状态报告格式化为可读字符串并刷新 UI。 */
    private void updateEgoStateInfo(EgoStateReport state) {
        String stateFlagNames = describeStateFlags(state.getStateFlags());
        String errorFlagNames = describeErrorFlags(state.getErrorFlags());
        mEgoStateInfoView.setText(String.format(Locale.US,
                "EGO: %s (raw=%d)  seq=%d\n"
                        + "State: %s (0x%08X)\n"
                        + "Error: %s (0x%016X)\n"
                        + "Storage: %,d bytes",
                state.getWorkState(), state.getRawWorkState(), state.getSequence(),
                stateFlagNames, state.getStateFlags(), errorFlagNames, state.getErrorFlags(),
                state.getStorageFreeBytes()));
    }

    /** 将 Ego 状态标志位转为中文描述。 */
    private static String describeStateFlags(long flags) {
        StringBuilder names = new StringBuilder();
        long knownFlags = 0;
        for (EgoStateFlag flag : EgoStateFlag.values()) {
            knownFlags |= flag.value();
            if ((flags & flag.value()) != 0) {
                appendFlagName(names, flag.name());
            }
        }
        appendUnknownFlags(names, flags & ~knownFlags);
        return names.length() == 0 ? "NONE" : names.toString();
    }

    /** 将 Ego 错误标志位转为中文描述。 */
    private static String describeErrorFlags(long flags) {
        StringBuilder names = new StringBuilder();
        long knownFlags = 0;
        for (EgoErrorFlag flag : EgoErrorFlag.values()) {
            knownFlags |= flag.value();
            if ((flags & flag.value()) != 0) {
                appendFlagName(names, flag.name());
            }
        }
        appendUnknownFlags(names, flags & ~knownFlags);
        return names.length() == 0 ? "NONE" : names.toString();
    }

    /** 追加未识别的标志位十六进制值。 */
    private static void appendUnknownFlags(StringBuilder names, long unknownFlags) {
        if (unknownFlags != 0) {
            appendFlagName(names, String.format(Locale.US, "UNKNOWN(0x%X)", unknownFlags));
        }
    }

    /** 向标志描述字符串追加一项名称。 */
    private static void appendFlagName(StringBuilder names, String name) {
        if (names.length() > 0) {
            names.append(", ");
        }
        names.append(name);
    }

    /** 系统内存紧张时自动停止采集并保存已录数据，避免 OOM 杀进程丢数据。 */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW && mCsvRecorder != null) {
            Log.w(TAG, "onTrimMemory level=" + level + " — auto-stopping capture");
            stopCollecting();
            showToast("内存告急，已自动停止并保存采集");
        }
    }

    /** 系统低内存回调：若正在采集则停止并保存。 */
    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (mCsvRecorder != null) {
            Log.w(TAG, "onLowMemory — auto-stopping capture");
            stopCollecting();
            showToast("内存告急，已自动停止并保存采集");
        }
    }

    // -------------------------------------------------------------------------
    // 开流 / 停流
    // -------------------------------------------------------------------------

    /**
     * 核心开流逻辑（在 {@link #mLifecycleExecutor} 线程调用，需 synchronized）：
     * <ol>
     *   <li>准备预览渲染器、主机时间同步、码率模式</li>
     *   <li>按 {@link EgoStreamSelection} 向 {@link Config} 启用各路彩色 profile</li>
     *   <li>{@link Pipeline#start} 启动视频；独立 {@link Sensor} 启动音频与 IMU</li>
     *   <li>启动 {@link #mVideoRunnable} 取帧线程与各解码线程</li>
     *   <li>后台预取标定 YAML</li>
     * </ol>
     */
    private synchronized void openStream() {
        if (mIsStreamRunning || mDevice == null || mStreamSelection == null) {
            return;
        }

        try {
            // 仅预览模式需要准备 Surface / OpenGL 渲染器
            if (isVideoDecodeEnabled() && !prepareColorRenderers(mStreamSelection)) {
                Log.w(TAG, "openStream aborted: color renderer preparation failed");
                return;
            }
            if (!isVideoDecodeEnabled()) {
                hideColorPreviews();
            }
            // 换 profile 前清空上一次的 codec/format 状态
            mLeftColorCodec = null;
            mRightColorCodec = null;
            mLeftColorFormat = null;
            mRightColorFormat = null;
            mLeftDecoderStatus = "";
            mRightDecoderStatus = "";
            mSideLeftColorCodec = null;
            mSideRightColorCodec = null;
            mSideLeftColorFormat = null;
            mSideRightColorFormat = null;
            mSideLeftDecoderStatus = "";
            mSideRightDecoderStatus = "";
            // 记录 USB 连接类型（USB2.0 可能限制 H264 码率导致运动马赛克）
            try {
                DeviceInfo info = mDevice.getInfo();
                if (info != null) {
                    Log.w(TAG, "USB connectionType = " + info.getConnectionType());
                    showToast("USB: " + info.getConnectionType());
                }
            } catch (Exception e) {
                Log.w(TAG, "read connectionType failed: " + e.getMessage());
            }

            try {
                if(mDevice != null && !timeSyncWithHost){
                    mDevice.timerSyncWithHost();
                    timeSyncWithHost = true;
                }
            } catch (Exception e) {
                Log.w(TAG, "timerSyncWithHost failed: " + e.getMessage());
            }
            applyColorBitrateMode();

            if (mPipeline == null) {
                mPipeline = new Pipeline(mDevice);
            }

            Config config = new Config();
            // 关闭帧聚合，避免左右流时间戳配对导致丢帧（H264 丢帧会马赛克到下一 IDR）
            config.setFrameAggregateOutputMode(
                    FrameAggregateOutputMode.OB_FRAME_AGGREGATE_OUTPUT_DISABLE);

            if (mColorMode == ColorMode.QUAD) {
                boolean enableQuadSideColors = !QUAD_ONLY_LEFT_RIGHT_COLOR_FOR_TEST;
                if (!enableQuadSideColors) {
                    Log.i(TAG, "Quad test: COLOR_SIDE_LEFT and COLOR_SIDE_RIGHT are disabled");
                }
                // For side cameras fall back to the inner-camera spec when the dialog
                // has not configured them separately (stereo-only dialog path).
                EgoStreamSelection.VideoSpec sideLeftSpec = mStreamSelection.sideLeftVideo != null
                        ? mStreamSelection.sideLeftVideo : mStreamSelection.leftVideo;
                EgoStreamSelection.VideoSpec sideRightSpec = mStreamSelection.sideRightVideo != null
                        ? mStreamSelection.sideRightVideo : mStreamSelection.rightVideo;

                // COLOR_SIDE_LEFT
                StreamProfileList sideLeftProfiles = enableQuadSideColors
                        ? mPipeline.getStreamProfileList(SensorType.COLOR_SIDE_LEFT) : null;
                if (sideLeftProfiles != null) {
                    boolean found = false;
                    for (int i = 0; i < sideLeftProfiles.getCount(); i++) {
                        StreamProfile p = sideLeftProfiles.getProfile(i);
                        VideoStreamProfile vp = p.as(StreamType.COLOR_SIDE_LEFT);
                        VideoCodec codec = VideoCodec.fromFormat(p.getFormat());
                        if (mStreamSelection.sideLeftEnabled && EgoStreamSelection.matches(p, sideLeftSpec)) {
                            if (!isColorCaptureFormatSupported(p.getFormat())
                                    || (isVideoDecodeEnabled() && codec != null && !codec.isDecoderAvailable())) { p.close(); continue; }
                            mSideLeftColorCodec = codec;
                            mSideLeftColorFormat = p.getFormat();
                            mSideLeftColorW = vp.getWidth(); mSideLeftColorH = vp.getHeight();
                            mSideLeftColorDesc = "SideL " + mSideLeftColorW + "x" + mSideLeftColorH + " " + p.getFormat();
                            config.enableStream(p); p.close(); found = true;
                            final String info = mSideLeftColorDesc;
                            runOnUiThread(() -> mSideLeftColorInfoView.setText(info));
                            if (isVideoDecodeEnabled()) adjustViewAspect(mSideLeftColorView, mSideLeftColorW, mSideLeftColorH);
                            break;
                        }
                        p.close();
                    }
                    if (!found) Log.w(TAG, "COLOR_SIDE_LEFT: no matching profile");
                    sideLeftProfiles.close();
                } else if (enableQuadSideColors) {
                    Log.w(TAG, "COLOR_SIDE_LEFT profiles is null");
                }

                // COLOR_LEFT
                StreamProfileList leftProfilesQ = mPipeline.getStreamProfileList(SensorType.COLOR_LEFT);
                if (leftProfilesQ != null) {
                    boolean found = false;
                    for (int i = 0; i < leftProfilesQ.getCount(); i++) {
                        StreamProfile p = leftProfilesQ.getProfile(i);
                        VideoStreamProfile vp = p.as(StreamType.COLOR_LEFT);
                        VideoCodec codec = VideoCodec.fromFormat(p.getFormat());
                        if (mStreamSelection.leftEnabled && EgoStreamSelection.matches(p, mStreamSelection.leftVideo)) {
                            if (!isColorCaptureFormatSupported(p.getFormat())
                                    || (isVideoDecodeEnabled() && codec != null && !codec.isDecoderAvailable())) { p.close(); continue; }
                            mLeftColorCodec = codec;
                            mLeftColorFormat = p.getFormat();
                            mLeftColorW = vp.getWidth(); mLeftColorH = vp.getHeight();
                            mLeftColorDesc = "Left " + mLeftColorW + "x" + mLeftColorH + " " + p.getFormat();
                            config.enableStream(p); p.close(); found = true;
                            final String info = mLeftColorDesc;
                            runOnUiThread(() -> mLeftColorInfoView.setText(info));
                            if (isVideoDecodeEnabled()) adjustViewAspect(mLeftColorView, mLeftColorW, mLeftColorH);
                            break;
                        }
                        p.close();
                    }
                    if (!found) Log.w(TAG, "COLOR_LEFT: no matching profile");
                    leftProfilesQ.close();
                } else {
                    Log.w(TAG, "COLOR_LEFT profiles is null");
                }

                // COLOR_RIGHT
                StreamProfileList rightProfilesQ = mPipeline.getStreamProfileList(SensorType.COLOR_RIGHT);
                if (rightProfilesQ != null) {
                    boolean found = false;
                    for (int i = 0; i < rightProfilesQ.getCount(); i++) {
                        StreamProfile p = rightProfilesQ.getProfile(i);
                        VideoStreamProfile vp = p.as(StreamType.COLOR_RIGHT);
                        VideoCodec codec = VideoCodec.fromFormat(p.getFormat());
                        if (mStreamSelection.rightEnabled && EgoStreamSelection.matches(p, mStreamSelection.rightVideo)) {
                            if (!isColorCaptureFormatSupported(p.getFormat())
                                    || (isVideoDecodeEnabled() && codec != null && !codec.isDecoderAvailable())) { p.close(); continue; }
                            mRightColorCodec = codec;
                            mRightColorFormat = p.getFormat();
                            mRightColorW = vp.getWidth(); mRightColorH = vp.getHeight();
                            mRightColorDesc = "Right " + mRightColorW + "x" + mRightColorH + " " + p.getFormat();
                            config.enableStream(p); p.close(); found = true;
                            final String info = mRightColorDesc;
                            runOnUiThread(() -> mRightColorInfoView.setText(info));
                            if (isVideoDecodeEnabled()) adjustViewAspect(mRightColorView, mRightColorW, mRightColorH);
                            break;
                        }
                        p.close();
                    }
                    if (!found) Log.w(TAG, "COLOR_RIGHT: no matching profile");
                    rightProfilesQ.close();
                } else {
                    Log.w(TAG, "COLOR_RIGHT profiles is null");
                }

                // COLOR_SIDE_RIGHT
                StreamProfileList sideRightProfiles = enableQuadSideColors
                        ? mPipeline.getStreamProfileList(SensorType.COLOR_SIDE_RIGHT) : null;
                if (sideRightProfiles != null) {
                    boolean found = false;
                    for (int i = 0; i < sideRightProfiles.getCount(); i++) {
                        StreamProfile p = sideRightProfiles.getProfile(i);
                        VideoStreamProfile vp = p.as(StreamType.COLOR_SIDE_RIGHT);
                        VideoCodec codec = VideoCodec.fromFormat(p.getFormat());
                        if (mStreamSelection.sideRightEnabled && EgoStreamSelection.matches(p, sideRightSpec)) {
                            if (!isColorCaptureFormatSupported(p.getFormat())
                                    || (isVideoDecodeEnabled() && codec != null && !codec.isDecoderAvailable())) { p.close(); continue; }
                            mSideRightColorCodec = codec;
                            mSideRightColorFormat = p.getFormat();
                            mSideRightColorW = vp.getWidth(); mSideRightColorH = vp.getHeight();
                            mSideRightColorDesc = "SideR " + mSideRightColorW + "x" + mSideRightColorH + " " + p.getFormat();
                            config.enableStream(p); p.close(); found = true;
                            final String info = mSideRightColorDesc;
                            runOnUiThread(() -> mSideRightColorInfoView.setText(info));
                            if (isVideoDecodeEnabled()) adjustViewAspect(mSideRightColorView, mSideRightColorW, mSideRightColorH);
                            break;
                        }
                        p.close();
                    }
                    if (!found) Log.w(TAG, "COLOR_SIDE_RIGHT: no matching profile");
                    sideRightProfiles.close();
                } else if (enableQuadSideColors) {
                    Log.w(TAG, "COLOR_SIDE_RIGHT profiles is null");
                }
            } else if (mColorMode == ColorMode.STEREO) {
            StreamProfileList leftProfiles = mPipeline.getStreamProfileList(SensorType.COLOR_LEFT);
            if (leftProfiles != null) {
                int leftCount = leftProfiles.getCount();
                Log.i(TAG, "COLOR_LEFT available profiles count: " + leftCount);
                for (int i = 0; i < leftCount; i++) {
                    StreamProfile p = leftProfiles.getProfile(i);
                    VideoStreamProfile vp = p.as(StreamType.COLOR_LEFT);
                    Log.i(TAG, "  COLOR_LEFT [" + i + "] " + vp.getWidth() + "x" + vp.getHeight()
                            + " fps=" + vp.getFps() + " fmt=" + vp.getFormat());
                    p.close();
                }
                boolean leftFound = false;
                for (int i = 0; i < leftCount; i++) {
                    StreamProfile p = leftProfiles.getProfile(i);
                    VideoStreamProfile vp = p.as(StreamType.COLOR_LEFT);
                    VideoCodec codec = VideoCodec.fromFormat(p.getFormat());
                    if (mStreamSelection.leftEnabled && EgoStreamSelection.matches(p, mStreamSelection.leftVideo)) {
                        if (!isColorCaptureFormatSupported(p.getFormat())) {
                            Log.w(TAG, "COLOR_LEFT unsupported capture format: " + p.getFormat());
                            showToast("Left color: unsupported format " + p.getFormat());
                            p.close();
                            continue;
                        }
                        if (isVideoDecodeEnabled() && codec != null && !codec.isDecoderAvailable()) {
                            p.close();
                            continue;
                        }
                        mLeftColorCodec = codec;
                        mLeftColorFormat = p.getFormat();
                        mLeftColorW = vp.getWidth();
                        mLeftColorH = vp.getHeight();
                        Log.i(TAG, "COLOR_LEFT selected: " + mLeftColorW + "x" + mLeftColorH
                                + " fps=" + vp.getFps() + " fmt=" + vp.getFormat());
                        config.enableStream(p);
                        p.close();
                        leftFound = true;
                        mLeftColorDesc = "Left " + mLeftColorW + "x" + mLeftColorH
                                + " " + vp.getFormat();
                        final String leftInfo = mLeftColorDesc;
                        runOnUiThread(() -> mLeftColorInfoView.setText(leftInfo));
                        if (isVideoDecodeEnabled()) adjustViewAspect(mLeftColorView, mLeftColorW, mLeftColorH);
                        break;
                    }
                    p.close();
                }
                if (!leftFound) {
                    Log.w(TAG, "COLOR_LEFT has no supported H264/H265/YUYV profile");
                    showToast("Left color: no H264/H265/YUYV profile");
                }
                leftProfiles.close();
            } else {
                Log.w(TAG, "COLOR_LEFT profiles is null");
            }

            StreamProfileList rightProfiles = mPipeline.getStreamProfileList(SensorType.COLOR_RIGHT);
            if (rightProfiles != null) {
                int rightCount = rightProfiles.getCount();
                Log.i(TAG, "COLOR_RIGHT available profiles count: " + rightCount);
                for (int i = 0; i < rightCount; i++) {
                    StreamProfile p = rightProfiles.getProfile(i);
                    VideoStreamProfile vp = p.as(StreamType.COLOR_RIGHT);
                    Log.i(TAG, "  COLOR_RIGHT [" + i + "] " + vp.getWidth() + "x" + vp.getHeight()
                            + " fps=" + vp.getFps() + " fmt=" + vp.getFormat());
                    p.close();
                }
                boolean rightFound = false;
                for (int i = 0; i < rightCount; i++) {
                    StreamProfile p = rightProfiles.getProfile(i);
                    VideoStreamProfile vp = p.as(StreamType.COLOR_RIGHT);
                    VideoCodec codec = VideoCodec.fromFormat(p.getFormat());
                    if (mStreamSelection.rightEnabled && EgoStreamSelection.matches(p, mStreamSelection.rightVideo)) {
                        if (!isColorCaptureFormatSupported(p.getFormat())) {
                            Log.w(TAG, "COLOR_RIGHT unsupported capture format: " + p.getFormat());
                            showToast("Right color: unsupported format " + p.getFormat());
                            p.close();
                            continue;
                        }
                        if (isVideoDecodeEnabled() && codec != null && !codec.isDecoderAvailable()) {
                            p.close();
                            continue;
                        }
                        mRightColorCodec = codec;
                        mRightColorFormat = p.getFormat();
                        mRightColorW = vp.getWidth();
                        mRightColorH = vp.getHeight();
                        Log.i(TAG, "COLOR_RIGHT selected: " + mRightColorW + "x" + mRightColorH
                                + " fps=" + vp.getFps() + " fmt=" + vp.getFormat());
                        config.enableStream(p);
                         p.close();
                        rightFound = true;
                        mRightColorDesc = "Right " + mRightColorW + "x" + mRightColorH
                                + " " + vp.getFormat();
                        final String rightInfo = mRightColorDesc;
                        runOnUiThread(() -> mRightColorInfoView.setText(rightInfo));
                        if (isVideoDecodeEnabled()) adjustViewAspect(mRightColorView, mRightColorW, mRightColorH);
                        break;
                    }
                    p.close();
                }
                if (!rightFound) {
                    Log.w(TAG, "COLOR_RIGHT has no supported H264/H265/YUYV profile");
                    showToast("Right color: no H264/H265/YUYV profile");
                }
                rightProfiles.close();
            } else {
                Log.w(TAG, "COLOR_RIGHT profiles is null");
            }
            } else if (mColorMode == ColorMode.MONO) {
                StreamProfileList colorProfiles = mPipeline.getStreamProfileList(SensorType.COLOR);
                if (colorProfiles != null) {
                    int colorCount = colorProfiles.getCount();
                    Log.i(TAG, "COLOR available profiles count: " + colorCount);
                    for (int i = 0; i < colorCount; i++) {
                        StreamProfile p = colorProfiles.getProfile(i);
                        VideoStreamProfile vp = p.as(StreamType.COLOR);
                        Log.i(TAG, "  COLOR [" + i + "] " + vp.getWidth() + "x" + vp.getHeight()
                                + " fps=" + vp.getFps() + " fmt=" + vp.getFormat());
                        p.close();
                    }
                    boolean colorFound = false;
                    for (int i = 0; i < colorCount; i++) {
                        StreamProfile p = colorProfiles.getProfile(i);
                        VideoStreamProfile vp = p.as(StreamType.COLOR);
                        VideoCodec codec = VideoCodec.fromFormat(p.getFormat());
                        if (mStreamSelection.leftEnabled && EgoStreamSelection.matches(p, mStreamSelection.leftVideo)) {
                            if (!isColorCaptureFormatSupported(p.getFormat())) {
                                Log.w(TAG, "COLOR unsupported capture format: " + p.getFormat());
                                showToast("Color: unsupported format " + p.getFormat());
                                p.close();
                                continue;
                            }
                            if (isVideoDecodeEnabled() && codec != null && !codec.isDecoderAvailable()) {
                                p.close();
                                continue;
                            }
                            mLeftColorCodec = codec;
                            mLeftColorFormat = p.getFormat();
                            mLeftColorW = vp.getWidth();
                            mLeftColorH = vp.getHeight();
                            Log.i(TAG, "COLOR selected: " + mLeftColorW + "x" + mLeftColorH
                                    + " fps=" + vp.getFps() + " fmt=" + vp.getFormat());
                            config.enableStream(p);
                            p.close();
                            colorFound = true;
                            mLeftColorDesc = "Color " + mLeftColorW + "x" + mLeftColorH
                                    + " " + vp.getFormat();
                            final String colorInfo = mLeftColorDesc;
                            runOnUiThread(() -> mLeftColorInfoView.setText(colorInfo));
                            if (isVideoDecodeEnabled()) adjustViewAspect(mLeftColorView, mLeftColorW, mLeftColorH);
                            break;
                        }
                        p.close();
                    }
                    if (!colorFound) {
                        Log.w(TAG, "COLOR has no supported H264/H265/YUYV profile");
                        showToast("Color: no H264/H265/YUYV profile");
                    }
                    colorProfiles.close();
                } else {
                    Log.w(TAG, "COLOR profiles is null");
                }
            }

            boolean noVideoSelected = mColorMode == ColorMode.QUAD
                    ? (!mStreamSelection.leftEnabled && !mStreamSelection.rightEnabled
                            && !mStreamSelection.sideLeftEnabled && !mStreamSelection.sideRightEnabled)
                    : (!mStreamSelection.leftEnabled && !mStreamSelection.rightEnabled);
            if (AUDIO_ONLY_DEBUG || noVideoSelected) {
                // Debug isolation: skip the video pipeline entirely so no H264
                // decode/render/mux competes with the audio stream for CPU/USB.
                config.close();
                mIsStreamRunning = true;
                Log.w(TAG, "video pipeline skipped: no video stream selected");
            } else {
                Log.i(TAG, "Starting pipeline...");
                // Apply the color bitrate BEFORE start(): the device firmware
                // rejects setPropertyValueI(prop 279) while streaming (errorCode 7).
                // We do the full write here (stopped state) so the first frame
                // arrives with the correct bitrate already configured.
                refreshColorBitrateRange(mStreamSelection, false);
                mPipeline.enableFrameSync();
                mPipeline.start(config);
                config.close();

                mIsStreamRunning = true;
                // Keep the pending flag so the first-frame callback can do a
                // read-only refresh: after the new profile is confirmed active the
                // firmware's actual chosen bitrate is re-read and shown in the UI
                // (important in dynamic-bitrate mode where firmware picks the value).
                mColorBitrateRangeRefreshPending = true;
                if (isVideoDecodeEnabled()) {
                mLeftDecodeQueue.clear();
                mRightDecodeQueue.clear();
                mSideLeftDecodeQueue.clear();
                mSideRightDecodeQueue.clear();
                if (mLeftDecodeThread == null) {
                    mLeftDecodeThread = new Thread(() -> decodeLoop(mLeftDecodeQueue,
                            mLeftBufferPool, mLeftDecoder, mLeftColorView, mLeftRawColorView, "Left"),
                            "LeftColorDecode");
                    mLeftDecodeThread.start();
                }

                if ((mColorMode == ColorMode.STEREO || mColorMode == ColorMode.QUAD)
                        && mRightDecodeThread == null) {
                    mRightDecodeThread = new Thread(() -> decodeLoop(mRightDecodeQueue,
                            mRightBufferPool, mRightDecoder, mRightColorView, mRightRawColorView, "Right"),
                            "RightColorDecode");
                    mRightDecodeThread.start();
                }

                if (mColorMode == ColorMode.QUAD) {
                    if (mSideLeftDecodeThread == null) {
                        mSideLeftDecodeThread = new Thread(() -> decodeLoop(mSideLeftDecodeQueue,
                                mSideLeftBufferPool, mSideLeftDecoder, mSideLeftColorView,
                                mSideLeftRawColorView, "SideLeft"), "SideLeftColorDecode");
                        mSideLeftDecodeThread.start();
                    }
                    if (mSideRightDecodeThread == null) {
                        mSideRightDecodeThread = new Thread(() -> decodeLoop(mSideRightDecodeQueue,
                                mSideRightBufferPool, mSideRightDecoder, mSideRightColorView,
                                mSideRightRawColorView, "SideRight"), "SideRightColorDecode");
                        mSideRightDecodeThread.start();
                    }
                }
                }
                if (mThread == null) {
                    mThread = new Thread(mVideoRunnable);
                    mThread.start();
                }
            }
            runOnUiThread(() -> mCsvCollectButton.setEnabled(true));
        } catch (Exception e) {
            Log.e(TAG, "openStream pipeline failed: " + e.getMessage());
            return;
        }

        // Audio is optional — failure does not block Color streaming
        try {
            Sensor audioSensor = mDevice.getSensor(SensorType.AUDIO);
            if (audioSensor != null) {
                Log.i(TAG, "Audio sensor found, starting...");
                StreamProfile audioProfile = null;
                StreamProfileList profileList = audioSensor.getStreamProfileList();
                if (profileList != null) {
                    int profileCount = profileList.getCount();
                    Log.i(TAG, "Audio available profiles count: " + profileCount);
                    for (int i = 0; i < profileCount; i++) {
                        StreamProfile p = profileList.getProfile(i);
                        Log.i(TAG, "  Audio [" + i + "] type=" + p.getType()
                                + " fmt=" + p.getFormat());
                        p.close();
                    }
                    if (profileCount > 0) {
                        audioProfile = profileList.getProfile(0);
                        AudioStreamProfile ap = audioProfile.as(StreamType.AUDIO);
                        // Capture the real encoding params so the WAV header matches
                        // the PCM the sensor actually delivers. Guard against a sensor
                        // that reports 0/garbage by falling back to the constants.
                        int sr = ap.getSampleRate();
                        int ch = ap.getChannelCount();
                        int bits = ap.getBitsPerSample();
                        mWavSampleRate = sr > 0 ? sr : WAV_SAMPLE_RATE;
                        mWavChannels = ch > 0 ? ch : WAV_CHANNELS;
                        mWavBits = bits > 0 ? bits : WAV_BITS_PER_SAMPLE;
                        Log.i(TAG, "Audio selected profile [0]: type=" + audioProfile.getType()
                                + " fmt=" + audioProfile.getFormat()
                                + " sampleRate=" + ap.getSampleRate() + "Hz"
                                + " channels=" + ap.getChannelCount()
                                + " bitsPerSample=" + ap.getBitsPerSample());
                    }
                    profileList.close();
                }
                if (audioProfile == null) {
                    Log.w(TAG, "Audio sensor has no available stream profile");
                    runOnUiThread(() -> mAudioInfoView.setText("Audio: no profile"));
                } else {
                    mAudioFrameCount = 0;
                    audioSensor.start(audioProfile, frame -> {
                        if (frame == null) {
                            return;
                        }
                        long count = ++mAudioFrameCount;
                        int dataSize = frame.getDataSize();
                        StreamTimestampRecorder recorder = mCsvRecorder;
                        if (recorder != null) {
                            try {
                                recorder.logTimestamp(StreamTimestampRecorder.Stream.AUDIO,
                                        frame.getTimeStampUs().longValue());
                            } catch (Exception ignore) {
                                // a timestamp read failure must not disturb streaming
                            }
                        }
                        // Fork the PCM to the async WAV recorder FIRST — this is the
                        // latency-critical path. offer() copies and returns at once
                        // (disk I/O is on the recorder's own thread), so the audio
                        // callback is never blocked and reception is unaffected.
                        WavRecorder wav = mWavRecorder;
                        if (wav != null && dataSize > 0) {
                            if (mAudioScratch == null || mAudioScratch.length < dataSize) {
                                mAudioScratch = new byte[dataSize];
                            }
                            // Use the count getData() actually copied, not dataSize —
                            // writing dataSize when fewer bytes were filled appends
                            // stale buffer contents as audible noise.
                            int copied = frame.getData(mAudioScratch);
                            if (copied > 0) {
                                // Filter in place before offering — removes DC/rumble
                                // from the floor. No-op if no processor is set.
                                PcmProcessor proc = mAudioProcessor;
                                if (proc != null) {
                                    proc.processInPlace(mAudioScratch, copied);
                                }
                                wav.offer(mAudioScratch, copied);
                            }
                        }

                        // Refresh the overlay at most a few times a second. Doing a
                        // String.format + runOnUiThread on every frame (~90+/s) churns
                        // garbage on this latency-sensitive thread and can stall it
                        // into dropping samples (the warble + crackle we are avoiding).
                        long nowMs = System.currentTimeMillis();
                        if (nowMs - mLastAudioUiUpdateMs >= AUDIO_UI_REFRESH_INTERVAL_MS) {
                            mLastAudioUiUpdateMs = nowMs;
                            String info = String.format(Locale.getDefault(),
                                    "Audio [#%d] fmt=%s size=%d bytes",
                                    count, frame.getFormat(), dataSize);
                            runOnUiThread(() -> mAudioInfoView.setText(info));
                        }
                        frame.close();
                    });
                    audioProfile.close();
                    mAudioSensor = audioSensor;
                    Log.i(TAG, "Audio sensor started successfully");
                }
            } else {
                Log.w(TAG, "Audio sensor not found on this device");
                runOnUiThread(() -> mAudioInfoView.setText("Audio: not supported"));
            }
        } catch (Exception e) {
            Log.w(TAG, "openStream audio failed: " + e.getMessage());
            runOnUiThread(() -> mAudioInfoView.setText("Audio: unavailable"));
        }

        // IMU is optional — uses independent Sensor, not Pipeline
        if (!AUDIO_ONLY_DEBUG) {
            if (mStreamSelection.accelEnabled) startImuSensor(SensorType.ACCEL, FrameType.ACCEL, mAccelInfoView, "Accel", "m/s²",
                    "accel", (f) -> ((AccelFrame) f).getAccelData());
            if (mStreamSelection.gyroEnabled) startImuSensor(SensorType.GYRO, FrameType.GYRO, mGyroInfoView, "Gyro", "rad/s",
                    "gyro", (f) -> ((GyroFrame) f).getGyroData());
        }

        // 开流后立即后台预取标定，避免 Collect 时阻塞 USB 导致全流卡顿
        prefetchCalibAsync();
    }

    /** 从 IMU 帧中提取 x/y/z 向量的函数式接口。 */
    private interface ImuVector {
        float[] get(Frame typedFrame);
    }

    /**
     * 启动加速度计或陀螺仪 Sensor 直连流，更新 UI 并在采集时写 CSV。
     */
    private void startImuSensor(SensorType sensorType, FrameType frameType,
                                TextView infoView, String label, String unit,
                                String csvType, ImuVector vector) {
        try {
            Sensor sensor = mDevice.getSensor(sensorType);
            if (sensor == null) {
                runOnUiThread(() -> infoView.setText(label + ": not supported"));
                return;
            }
            StreamProfile profile = null;
            StreamProfileList profileList = sensor.getStreamProfileList();
            if (profileList != null) {
                int count = profileList.getCount();
                for (int i = 0; i < count; i++) {
                    StreamProfile p = profileList.getProfile(i);
                    if (sensorType == SensorType.ACCEL) {
                        AccelStreamProfile ap = p.as(StreamType.ACCEL);
                        if (EgoStreamSelection.matches(p, mStreamSelection.accel)) {
                            profile = p;
                            Log.i(TAG, label + " selected profile [" + i + "]: rate="
                                    + ap.getSampleRate() + " range=" + ap.getFullScaleRange());
                            break;
                        }
                    } else if (sensorType == SensorType.GYRO) {
                        GyroStreamProfile gp = p.as(StreamType.GYRO);
                        if (EgoStreamSelection.matches(p, mStreamSelection.gyro)) {
                            profile = p;
                            Log.i(TAG, label + " selected profile [" + i + "]: rate="
                                    + gp.getSampleRate() + " range=" + gp.getFullScaleRange());
                            break;
                        }
                    }
                    p.close();
                }
                profileList.close();
            }
            if (profile == null) {
                runOnUiThread(() -> infoView.setText(label + ": no profile"));
                return;
            }
            final long[] count = {0};
            sensor.start(profile, frame -> {
                if (frame == null) {
                    return;
                }
                Frame typed = frame.as(frameType);
                try {
                    float[] v = vector.get(typed);
                    StreamTimestampRecorder recorder = mCsvRecorder;
                    if (recorder != null) {
                        try {
                            recorder.logImu(typed.getTimeStampUs().longValue(),
                                    v[0], v[1], v[2], csvType);
                        } catch (Exception ignore) {
                            // 时间戳读取失败不应影响开流
                        }
                    }
                    if (++count[0] % 20 == 0) {
                        String text = String.format(Locale.getDefault(),
                                "%s: x=%.3f y=%.3f z=%.3f %s", label, v[0], v[1], v[2], unit);
                        runOnUiThread(() -> infoView.setText(text));
                    }
                } finally {
                    typed.close();
                }
            });
            profile.close();
            if (sensorType == SensorType.ACCEL) mAccelSensor = sensor;
            else mGyroSensor = sensor;
            Log.i(TAG, label + " sensor started");
        } catch (Exception e) {
            Log.w(TAG, "startImuSensor " + label + " failed: " + e.getMessage());
            runOnUiThread(() -> infoView.setText(label + ": unavailable"));
        }
    }

    /**
     * 停止所有流与解码线程，但不关闭 Device/Pipeline（用于 onPause 或换配置前）。
     * 停流顺序：视频取帧 → 解码 → IMU → 音频 → Pipeline。
     */
    private synchronized void stopStreamOnly() {
        final long stopStartMs = System.currentTimeMillis();
        mIsStreamRunning = false;
        mColorBitrateRangeRefreshPending = false;
        if (mThread != null) {
            mThread.interrupt();
            try {
                mThread.join(300);
            } catch (InterruptedException e) {
                Log.e(TAG, "stopStreamOnly thread join: " + e.getMessage());
            }
            mThread = null;
        }

        if (mLeftDecodeThread != null) {
            mLeftDecodeThread.interrupt();
            try {
                mLeftDecodeThread.join(500);
            } catch (InterruptedException e) {
                Log.e(TAG, "stopStreamOnly left decode join: " + e.getMessage());
            }
            mLeftDecodeThread = null;
        }
        if (mRightDecodeThread != null) {
            mRightDecodeThread.interrupt();
            try {
                mRightDecodeThread.join(500);
            } catch (InterruptedException e) {
                Log.e(TAG, "stopStreamOnly right decode join: " + e.getMessage());
            }
            mRightDecodeThread = null;
        }
        if (mSideLeftDecodeThread != null) {
            mSideLeftDecodeThread.interrupt();
            try {
                mSideLeftDecodeThread.join(500);
            } catch (InterruptedException e) {
                Log.e(TAG, "stopStreamOnly side-left decode join: " + e.getMessage());
            }
            mSideLeftDecodeThread = null;
        }
        if (mSideRightDecodeThread != null) {
            mSideRightDecodeThread.interrupt();
            try {
                mSideRightDecodeThread.join(500);
            } catch (InterruptedException e) {
                Log.e(TAG, "stopStreamOnly side-right decode join: " + e.getMessage());
            }
            mSideRightDecodeThread = null;
        }
        mLeftDecodeQueue.clear();
        mRightDecodeQueue.clear();
        mSideLeftDecodeQueue.clear();
        mSideRightDecodeQueue.clear();

        // 解码线程已退出后再释放解码器；下次 openStream 会懒启动并重新等 SPS
        mLeftDecoder.release();
        mRightDecoder.release();
        mSideLeftDecoder.release();
        mSideRightDecoder.release();
        stopMp4Recording();

        mAudioFrameCount = 0;

        // 设备停流顺序：先 IMU，再音频，最后彩色 Pipeline（不可同时拆 IMU 与彩色）
        if (mAccelSensor != null) {
            try {
                mAccelSensor.stop();
            } catch (Exception e) {
                Log.e(TAG, "stopStreamOnly accel stop: " + e.getMessage());
            }
            mAccelSensor = null;
        }

        if (mGyroSensor != null) {
            try {
                mGyroSensor.stop();
            } catch (Exception e) {
                Log.e(TAG, "stopStreamOnly gyro stop: " + e.getMessage());
            }
            mGyroSensor = null;
        }

        if (mAudioSensor != null) {
            try {
                mAudioSensor.stop();
            } catch (Exception e) {
                Log.e(TAG, "stopStreamOnly audio stop: " + e.getMessage());
            }
            mAudioSensor = null;
        }
        stopWavRecording();

        // 所有生产者已停后再关 CSV（注意：正常 Collect 停止走 stopCollecting，此处为停流附带清理）
        StreamTimestampRecorder recorder = mCsvRecorder;
        mCsvRecorder = null;
        if (recorder != null) {
            recorder.close();
        }

        if (mPipeline != null) {
            try {
                mPipeline.stop();
            } catch (Exception e) {
                Log.e(TAG, "stopStreamOnly pipeline stop: " + e.getMessage());
            }
        }

        Log.i(TAG, "stopStreamOnly completed in "
                + (System.currentTimeMillis() - stopStartMs) + "ms");

        runOnUiThread(() -> {
            mCsvCollectButton.setEnabled(false);
            mCsvCollectButton.setText("Collect");
            mLeftColorInfoView.setText(mColorMode == ColorMode.MONO ? "Color" : "Left Color");
            mRightColorInfoView.setText(mColorMode == ColorMode.MONO ? "Not supported" : "Right Color");
            mSideLeftColorInfoView.setText("Side Left");
            mSideRightColorInfoView.setText("Side Right");
        });
    }

    /** 停流并关闭 Pipeline（设备断开或 Activity 销毁时）。 */
    private synchronized void closeStream() {
        stopCollecting();
        stopStreamOnly();
        if (mPipeline != null) {
            try {
                mPipeline.close();
            } catch (Exception ignore) {
            }
            mPipeline = null;
        }
    }

    // -------------------------------------------------------------------------
    // 视频取帧与渲染
    // -------------------------------------------------------------------------

    /**
     * 可复用的 H264/H265 帧缓冲（来自各路的 buffer 对象池）。
     * {@code length} 为有效字节数，{@code data} 可能更大。
     */
    private static final class FrameBuffer {
        byte[] data;
        int length;
        /** 设备时间戳（微秒），供解码器按源时钟平滑显示 */
        long tsUs;
    }

    /** 单路流的统计：帧序号跳变、FPS 窗口计数、上游丢帧次数 */
    private static final class StreamStat {
        long lastIndex;
        long fpsWindowStart;
        int fpsFrames;
        /** 通过 frame index 不连续检测到的上游丢帧次数 */
        int indexJumps;
        void reset() {
            lastIndex      = -1;
            fpsWindowStart = 0;
            fpsFrames      = 0;
            indexJumps     = 0;
        }
    }

    private final StreamStat mLeftStat = new StreamStat();
    private final StreamStat mRightStat = new StreamStat();
    private final StreamStat mSideLeftStat = new StreamStat();
    private final StreamStat mSideRightStat = new StreamStat();

    /**
     * 视频主循环：waitForFrameSet → 分发各路彩色帧到 {@link #handleColorFrame}。
     * 运行在 {@link #mThread}。
     */
    private final Runnable mVideoRunnable = () -> {
        mLeftStat.reset();
        mRightStat.reset();
        mSideLeftStat.reset();
        mSideRightStat.reset();
        while (mIsStreamRunning) {
            try (FrameSet frameSet = mPipeline.waitForFrameSet(100)) {
                if (frameSet == null) {
                    continue;
                }
                Frame leftFrame = null;
                Frame rightFrame = null;
                Frame sideLeftFrame = null;
                Frame sideRightFrame = null;
                try {
                    leftFrame = frameSet.getFrame(mColorMode == ColorMode.MONO
                            ? FrameType.COLOR : FrameType.COLOR_LEFT);
                    if (mColorMode == ColorMode.STEREO || mColorMode == ColorMode.QUAD) {
                        rightFrame = frameSet.getFrame(FrameType.COLOR_RIGHT);
                    }
                    if (mColorMode == ColorMode.QUAD) {
                        sideLeftFrame = frameSet.getFrame(FrameType.COLOR_SIDE_LEFT);
                        sideRightFrame = frameSet.getFrame(FrameType.COLOR_SIDE_RIGHT);
                    }
                    if (leftFrame != null || rightFrame != null
                            || sideLeftFrame != null || sideRightFrame != null) {
                        requestColorBitrateRangeRefreshAfterFirstFrame();
                    }
                    long now = System.currentTimeMillis();

                    // 一次读取 volatile 的采集门闩；MP4 是否写入还取决于录制开关
                    StreamTimestampRecorder csvSnap      = mCsvRecorder;
                    Mp4Recorder sideLeftMp4  = isVideoRecordingEnabled() && csvSnap != null
                            ? mSideLeftMp4Recorder : null;
                    Mp4Recorder leftMp4      = isVideoRecordingEnabled() && csvSnap != null
                            ? mLeftMp4Recorder : null;
                    Mp4Recorder rightMp4     = isVideoRecordingEnabled() && csvSnap != null
                            ? mRightMp4Recorder : null;
                    Mp4Recorder sideRightMp4 = isVideoRecordingEnabled() && csvSnap != null
                            ? mSideRightMp4Recorder : null;

                    if (sideLeftFrame != null) {
                        handleColorFrame(sideLeftFrame, mSideLeftStat, "SIDE_LEFT",
                                mSideLeftColorDesc, mSideLeftColorInfoView,
                                mSideLeftDecodeQueue, mSideLeftBufferPool,
                                StreamTimestampRecorder.Stream.SIDE_LEFT_COLOR,
                                sideLeftMp4, now, csvSnap);
                        sideLeftFrame.close(); sideLeftFrame = null;
                    }

                    if (leftFrame != null) {
                        handleColorFrame(leftFrame, mLeftStat,
                                mColorMode == ColorMode.MONO ? "COLOR" : "LEFT", mLeftColorDesc,
                                mLeftColorInfoView, mLeftDecodeQueue, mLeftBufferPool,
                                mColorMode == ColorMode.MONO ? StreamTimestampRecorder.Stream.COLOR
                                        : StreamTimestampRecorder.Stream.LEFT_COLOR,
                                leftMp4, now, csvSnap);
                        leftFrame.close();
                        leftFrame = null;
                    }

                    if (rightFrame != null) {
                        handleColorFrame(rightFrame, mRightStat, "RIGHT", mRightColorDesc,
                                mRightColorInfoView, mRightDecodeQueue, mRightBufferPool,
                                StreamTimestampRecorder.Stream.RIGHT_COLOR, rightMp4, now, csvSnap);
                        rightFrame.close();
                        rightFrame = null;
                    }

                    if (sideRightFrame != null) {
                        handleColorFrame(sideRightFrame, mSideRightStat, "SIDE_RIGHT",
                                mSideRightColorDesc, mSideRightColorInfoView,
                                mSideRightDecodeQueue, mSideRightBufferPool,
                                StreamTimestampRecorder.Stream.SIDE_RIGHT_COLOR,
                                sideRightMp4, now, csvSnap);
                        sideRightFrame.close(); sideRightFrame = null;
                    }
                } finally {
                    if (leftFrame != null) leftFrame.close();
                    if (rightFrame != null) rightFrame.close();
                    if (sideLeftFrame != null) sideLeftFrame.close();
                    if (sideRightFrame != null) sideRightFrame.close();
                }

            } catch (Exception e) {
                Log.e(TAG, "video loop error: " + e.getMessage());
            }
        }
    };

    /**
     * 处理一路彩色帧：写 CSV 时间戳、投递 MP4、复制到解码队列、更新 FPS 与丢帧统计。
     * 在取帧线程执行，队列满时丢弃帧（非阻塞 offer）。
     */
    private void handleColorFrame(Frame frame, StreamStat stat, String tag, String desc,
                                  TextView infoView,
                                  LinkedBlockingQueue<FrameBuffer> decodeQueue,
                                  LinkedBlockingQueue<FrameBuffer> pool,
                                  StreamTimestampRecorder.Stream csvStream,
                                  Mp4Recorder mp4Recorder, long now,
                                  StreamTimestampRecorder csvRecorder) {
        long idx = frame.getIndex();
        // 每路彩色有独立 frame index 序列，正常相邻帧 index 差 1
        final long expectedIndexStep = 1;
        if (stat.lastIndex >= 0 && idx != stat.lastIndex + expectedIndexStep) {
            Log.w(TAG, tag + " frame index jump: " + stat.lastIndex + " -> " + idx
                    + " (dropped upstream, expect mosaic until next IDR)");
            stat.indexJumps++;
        }
        stat.lastIndex = idx;

        // 设备时间戳用于 CSV 与 MP4 PTS；失败则退化为 wall clock 并打日志
        long tsUs;
        try {
            tsUs = frame.getTimeStampUs().longValue();
        } catch (Exception e) {
            tsUs = System.currentTimeMillis() * 1000L;
            Log.w(TAG, tag + " getTimeStampUs failed, using wall clock: " + e.getMessage());
        }

        if (csvRecorder != null) {
            try {
                csvRecorder.logVideoTimestamp(csvStream, tsUs, idx);
            } catch (Exception ignore) {
            }
        }

        // MP4：直接从 SDK Frame 写入录制器缓冲，减少一次拷贝
        if (mp4Recorder != null) {
            mp4Recorder.offer(frame, tsUs);
        }

        // 预览解码：另拷贝一份给 MediaCodec（与 MP4 路径分离）
        boolean decodeEnabled = isVideoDecodeEnabled();
        if (decodeEnabled) {
            int size = frame.getDataSize();
            FrameBuffer buf = pool.poll();
            if (buf == null || buf.data == null || buf.data.length < size) {
                buf = new FrameBuffer();
                buf.data = new byte[size];
            }
            buf.length = size;
            buf.tsUs   = tsUs;
            frame.getData(buf.data);
            if (!decodeQueue.offer(buf)) {
                Log.w(TAG, tag + " decode queue full, frame dropped"
                        + " -> expect mosaic until next IDR");
                pool.offer(buf);  // 队列满，归还 buffer 到对象池
            }
        }

        stat.fpsFrames++;
        if (stat.fpsWindowStart == 0) {
            // 首帧只作为 FPS 窗口起点，不计入帧数
            stat.fpsWindowStart = now;
            stat.fpsFrames = 0;
        } else if (now - stat.fpsWindowStart >= 1000) {
            long elapsed = now - stat.fpsWindowStart;
            float fps = stat.fpsFrames * 1000f / elapsed;
            stat.fpsFrames = 0;
            stat.fpsWindowStart = now;
            String mode = getDecoderStatus(infoView);
            String text = String.format(Locale.getDefault(), "%s\n%.1ffps%s", desc, fps,
                    mode.isEmpty() ? "" : "\n" + mode);
            runOnUiThread(() -> infoView.setText(text));
        }
    }

    /** 根据 info TextView 返回对应路的解码器状态字符串。 */
    private String getDecoderStatus(TextView infoView) {
        if (infoView == mLeftColorInfoView) return mLeftDecoderStatus;
        if (infoView == mRightColorInfoView) return mRightDecoderStatus;
        if (infoView == mSideLeftColorInfoView) return mSideLeftDecoderStatus;
        return mSideRightDecoderStatus;
    }

    /**
     * 单路解码工作线程：H264/H265 走 MediaCodec + TextureView；YUYV 走 OBGLView。
     * 处理完毕的 buffer 归还对象池。
     */
    private void decodeLoop(LinkedBlockingQueue<FrameBuffer> queue,
                            LinkedBlockingQueue<FrameBuffer> pool, VideoDecoder decoder,
                            TextureView view, OBGLView rawView, String tag) {
        while (mIsStreamRunning) {
            try {
                FrameBuffer buf = queue.poll(100, TimeUnit.MILLISECONDS);
                if (buf == null) {
                    continue;
                }
                try {
                    int w, h;
                    VideoCodec codec;
                    Format format;
                    if (view == mLeftColorView) {
                        w = mLeftColorW; h = mLeftColorH;
                        codec = mLeftColorCodec; format = mLeftColorFormat;
                    } else if (view == mRightColorView) {
                        w = mRightColorW; h = mRightColorH;
                        codec = mRightColorCodec; format = mRightColorFormat;
                    } else if (view == mSideLeftColorView) {
                        w = mSideLeftColorW; h = mSideLeftColorH;
                        codec = mSideLeftColorCodec; format = mSideLeftColorFormat;
                    } else {
                        w = mSideRightColorW; h = mSideRightColorH;
                        codec = mSideRightColorCodec; format = mSideRightColorFormat;
                    }
                    if (codec != null) {
                        decodeToView(decoder, codec, view, w, h, buf);
                    } else if (format == Format.YUYV) {
                        // YUYV 经 native ImageUtils 转 RGB 后上传 OpenGL 纹理
                        rawView.update(w, h, StreamType.COLOR, format, buf.data, 1.0f);
                    } else {
                        Log.w(TAG, "Unsupported raw color format: " + format);
                    }
                } finally {
                    pool.offer(buf);  // 无论解码成败都回收 buffer
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                Log.e(TAG, "decodeLoop " + tag + ": " + e.getMessage());
            }
        }
    }

    /**
     * 向 MediaCodec 送入一帧；Surface 可用前懒启动解码器，之前帧丢弃。
     */
    private void decodeToView(VideoDecoder decoder, VideoCodec codec, TextureView view,
                              int width, int height, FrameBuffer buf) {
        if (!decoder.isStarted()) {
            if (codec == null || width <= 0 || height <= 0 || !view.isAvailable()) {
                return;
            }
            Surface surface = new Surface(view.getSurfaceTexture());
            if (!decoder.start(codec, width, height, surface)) {
                return;
            }
        }
        decoder.decode(buf.data, buf.length, buf.tsUs);
        updateDecoderStatus(decoder, view);
    }

    /** 刷新某路预览 overlay 上的解码模式描述（硬解/软解等）。 */
    private void updateDecoderStatus(VideoDecoder decoder, TextureView view) {
        String status = decoder.getDecodeMode();
        final String desc;
        final TextView infoView;
        if (view == mLeftColorView) {
            if (status.equals(mLeftDecoderStatus)) return;
            mLeftDecoderStatus = status;
            desc = mLeftColorDesc; infoView = mLeftColorInfoView;
        } else if (view == mRightColorView) {
            if (status.equals(mRightDecoderStatus)) return;
            mRightDecoderStatus = status;
            desc = mRightColorDesc; infoView = mRightColorInfoView;
        } else if (view == mSideLeftColorView) {
            if (status.equals(mSideLeftDecoderStatus)) return;
            mSideLeftDecoderStatus = status;
            desc = mSideLeftColorDesc; infoView = mSideLeftColorInfoView;
        } else {
            if (status.equals(mSideRightDecoderStatus)) return;
            mSideRightDecoderStatus = status;
            desc = mSideRightColorDesc; infoView = mSideRightColorInfoView;
        }
        runOnUiThread(() -> infoView.setText(desc + "\n" + status));
    }

    /** 按帧宽高比调整 TextureView 尺寸，避免画面被拉伸。 */
    private void adjustViewAspect(TextureView view, int frameW, int frameH) {
        runOnUiThread(() -> {
            ViewGroup parent = (ViewGroup) view.getParent();
            if (parent == null || parent.getWidth() <= 0 || parent.getHeight() <= 0
                    || frameW <= 0 || frameH <= 0) {
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

    // -------------------------------------------------------------------------
    // 采集（CSV / MP4 / WAV / 标定）
    // -------------------------------------------------------------------------

    /** Collect 按钮：未采集则弹时长对话框；采集中则停止并保存。 */
    private void onCsvCollectClick() {
        if (mCsvRecorder == null) {
            showRecordingDurationDialog();
        } else {
            stopCollecting();
            showToast("Saved");
        }
    }

    /** 输入录制时长（分钟）后开始采集。 */
    private void showRecordingDurationDialog() {
        EditText durationInput = new EditText(this);
        durationInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        durationInput.setHint("For example: 10");
        durationInput.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("Recording duration")
                .setMessage("Enter the recording duration in whole minutes.")
                .setView(durationInput)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String value = durationInput.getText().toString().trim();
                    try {
                        long minutes = Long.parseLong(value);
                        if (minutes <= 0 || minutes > Long.MAX_VALUE / 60_000L) {
                            throw new NumberFormatException();
                        }
                        startCollecting(minutes * 60_000L);
                    } catch (NumberFormatException e) {
                        showToast("Enter a positive whole number of minutes.");
                    }
                })
                .show();
    }

    /**
     * 开始一次采集会话：创建时间戳目录，启动 CSV/MP4/WAV，导出标定，并设置自动停止定时器。
     */
    private void startCollecting(long recordingDurationMillis) {
        if (mCsvRecorder != null) {
            return;
        }
        if (!ensureCaptureStorageAccess()) {
            return;
        }
            if (!hasEnoughResources()) {
                return;
            }
            String session = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    .format(new Date());
            File dir = new File(getCaptureRootDir(), session);
            StreamTimestampRecorder r = new StreamTimestampRecorder(dir, session);
            if (!r.start()) {
                showToast("Collect: failed to start");
                return;
            }
            final File calibDir = dir;
            final String calibSession = session;
            new Thread(() -> saveCalibToDir(calibDir, calibSession), "CalibExport").start();
            // 先启动录制器，最后再 publish mCsvRecorder 作为采集门闩
            if (isVideoRecordingEnabled()) {
                startMp4Recording(dir, session);
            }
            startWavRecording(dir, session);
            mCurrentSessionDir = dir;
            mCurrentSessionId  = session;
            mCsvRecorder = r;
            mCsvPathView.setText("Data: " + dir.getAbsolutePath());
            mCsvCollectButton.setText("Stop");
        mCsvCollectButton.removeCallbacks(mAutoStopCollectRunnable);
        mCsvCollectButton.postDelayed(mAutoStopCollectRunnable, recordingDurationMillis);
        showToast("Collecting: " + session);
    }

    /**
     * 停止当前采集并异步 finalize 各录制器（可安全从任意线程调用，幂等）。
     * 先清空 volatile 门闩停止生产者写入，再在 CaptureFinalizer 线程 join 写盘。
     */
    private synchronized void stopCollecting() {
        if (mCsvCollectButton != null) {
            mCsvCollectButton.removeCallbacks(mAutoStopCollectRunnable);
        }
        StreamTimestampRecorder recorder = mCsvRecorder;
        if (recorder == null) {
            return;
        }
        mCsvRecorder = null;
        Mp4Recorder left      = mLeftMp4Recorder;
        Mp4Recorder right     = mRightMp4Recorder;
        Mp4Recorder sideLeft  = mSideLeftMp4Recorder;
        Mp4Recorder sideRight = mSideRightMp4Recorder;
        mLeftMp4Recorder      = null;
        mRightMp4Recorder     = null;
        mSideLeftMp4Recorder  = null;
        mSideRightMp4Recorder = null;
        WavRecorder wav = mWavRecorder;
        mWavRecorder    = null;
        mAudioProcessor = null;
        File   sessionDir = mCurrentSessionDir;
        String sessionId  = mCurrentSessionId;
        mCurrentSessionDir = null;
        mCurrentSessionId  = null;
        runOnUiThread(() -> mCsvCollectButton.setText("Collect"));
        new Thread(() -> {
            recorder.close();
            if (left      != null) left.stopAndWait();
            if (right     != null) right.stopAndWait();
            if (sideLeft  != null) sideLeft.stopAndWait();
            if (sideRight != null) sideRight.stopAndWait();
            if (wav       != null) wav.stop();
            writeCapturesSummary(sessionDir, sessionId, recorder,
                    left, right, sideLeft, sideRight);
        }, "CaptureFinalizer").start();
    }

    /**
     * 写入 capture_summary.json：各路写入/丢帧/队列峰值/上游跳号，以及 lossless 总标志。
     * 在 CaptureFinalizer 后台线程调用，不可碰 UI。
     */
    private void writeCapturesSummary(File dir, String sessionId,
                                      StreamTimestampRecorder csv,
                                      Mp4Recorder left, Mp4Recorder right,
                                      Mp4Recorder sideLeft, Mp4Recorder sideRight) {
        if (dir == null || sessionId == null) return;
        try {
            org.json.JSONObject root = new org.json.JSONObject();
            root.put("session",           sessionId);
            root.put("csv_dropped_rows",  csv != null ? csv.getDroppedRows() : 0);

            org.json.JSONObject streams = new org.json.JSONObject();
            addStreamStats(streams, "left",       left,      mLeftStat);
            addStreamStats(streams, "right",      right,     mRightStat);
            addStreamStats(streams, "side_left",  sideLeft,  mSideLeftStat);
            addStreamStats(streams, "side_right", sideRight, mSideRightStat);
            root.put("streams", streams);

            boolean lossless = (csv == null || csv.getDroppedRows() == 0)
                    && noDrop(left) && noDrop(right) && noDrop(sideLeft) && noDrop(sideRight)
                    && mLeftStat.indexJumps == 0 && mRightStat.indexJumps == 0
                    && mSideLeftStat.indexJumps == 0 && mSideRightStat.indexJumps == 0;
            root.put("lossless", lossless);

            File f = new File(dir, "capture_summary.json");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(root.toString(2).getBytes("UTF-8"));
            }
            Log.i(TAG, "capture summary written: lossless=" + lossless + " -> " + f.getPath());
        } catch (Exception e) {
            Log.e(TAG, "writeCapturesSummary: " + e.getMessage());
        }
    }

    /** 向 summary JSON 追加单路 MP4 统计与 StreamStat 跳号。 */
    private void addStreamStats(org.json.JSONObject parent, String key,
                                Mp4Recorder rec, StreamStat stat) throws Exception {
        if (rec == null) return;
        org.json.JSONObject s = new org.json.JSONObject();
        s.put("written_frames",          rec.getWrittenFrames());
        s.put("startup_skipped_frames",  rec.getStartupSkippedFrames());
        s.put("muxer_failed_frames",     rec.getMuxerFailedFrames());
        s.put("dropped_frames",          rec.getDroppedFrames());
        s.put("queue_dropped_frames",    rec.getDroppedFrames());
        s.put("raw_diagnostic_enabled",  rec.isRawDiagnosticEnabled());
        s.put("raw_written_frames",      rec.getRawWrittenFrames());
        s.put("raw_write_errors",        rec.getRawWriteErrors());
        s.put("peak_queue_kb",           rec.getPeakQueueBytes() / 1024);
        s.put("upstream_jumps",          stat.indexJumps);
        parent.put(key, s);
    }

    /** 判断该路 MP4 录制是否无丢帧/无封装失败。 */
    private static boolean noDrop(Mp4Recorder rec) {
        return rec == null
                || (rec.getDroppedFrames() == 0
                    && rec.getMuxerFailedFrames() == 0
                    && (!rec.isRawDiagnosticEnabled()
                        || (rec.getRawWriteErrors() == 0
                            && rec.getRawWrittenFrames() == rec.getWrittenFrames())));
    }

    /** 检查磁盘与内存是否满足采集门槛；不足则 Toast 提示并返回 false。 */
    private boolean hasEnoughResources() {
        File dir = Environment.getExternalStorageDirectory();
        if (dir == null || !Environment.MEDIA_MOUNTED.equals(
                Environment.getExternalStorageState())) {
            showToast("存储不可用，无法采集");
            Log.w(TAG, "collect blocked: shared external storage unavailable");
            return false;
        }
        long free = new StatFs(dir.getPath()).getAvailableBytes();
        if (free < MIN_FREE_DISK_BYTES) {
            showToast("存储空间不足，无法采集");
            Log.w(TAG, "collect blocked: free disk " + free + " < " + MIN_FREE_DISK_BYTES);
            return false;
        }
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            if (mi.lowMemory || mi.availMem < MIN_AVAIL_MEM_BYTES) {
                showToast("内存不足，无法采集");
                Log.w(TAG, "collect blocked: availMem " + mi.availMem
                        + " lowMemory=" + mi.lowMemory);
                return false;
            }
        }
        return true;
    }

    /** 采集根目录，通常为 /storage/emulated/0/Orbbec/Capture。 */
    private File getCaptureRootDir() {
        return new File(Environment.getExternalStorageDirectory(), ROOT_DIR);
    }

    /** Android 11+ 检查/引导「所有文件访问权限」，否则无法写公共目录。 */
    private boolean ensureCaptureStorageAccess() {
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            showToast("存储不可用，无法采集");
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                || Environment.isExternalStorageManager()) {
            return true;
        }
        showToast("请授予“所有文件访问权限”后再次点击采集");
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Log.w(TAG, "app storage settings unavailable; opening global settings", e);
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
        return false;
    }

    /** 为当前已启用的各路 H264/H265 彩色流创建并启动 MP4 录制器。 */
    private void startMp4Recording(File dir, String session) {
        if (mLeftColorCodec != null && mLeftColorW > 0 && mLeftColorH > 0) {
            String stem = (mColorMode == ColorMode.MONO ? "color_" : "left_color_") + session;
            Mp4Recorder rec = createMp4Recorder(
                    dir, stem, mLeftColorCodec, mLeftColorW, mLeftColorH);
            rec.start();
            mLeftMp4Recorder = rec;
        } else if (mLeftColorFormat == Format.YUYV) {
            Log.i(TAG, "Left/Color is YUYV; MP4 recording skipped (preview only)");
        }
        if ((mColorMode == ColorMode.STEREO || mColorMode == ColorMode.QUAD)
                && mRightColorCodec != null && mRightColorW > 0 && mRightColorH > 0) {
            Mp4Recorder rec = createMp4Recorder(
                    dir, "right_color_" + session,
                    mRightColorCodec, mRightColorW, mRightColorH);
            rec.start();
            mRightMp4Recorder = rec;
        } else if ((mColorMode == ColorMode.STEREO || mColorMode == ColorMode.QUAD)
                && mRightColorFormat == Format.YUYV) {
            Log.i(TAG, "Right color is YUYV; MP4 recording skipped (preview only)");
        }
        if (mColorMode == ColorMode.QUAD) {
            if (mSideLeftColorCodec != null && mSideLeftColorW > 0 && mSideLeftColorH > 0) {
                Mp4Recorder rec = createMp4Recorder(
                        dir, "side_left_color_" + session,
                        mSideLeftColorCodec, mSideLeftColorW, mSideLeftColorH);
                rec.start();
                mSideLeftMp4Recorder = rec;
            } else if (mSideLeftColorFormat == Format.YUYV) {
                Log.i(TAG, "Side-left color is YUYV; MP4 recording skipped");
            }
            if (mSideRightColorCodec != null && mSideRightColorW > 0 && mSideRightColorH > 0) {
                Mp4Recorder rec = createMp4Recorder(
                        dir, "side_right_color_" + session,
                        mSideRightColorCodec, mSideRightColorW, mSideRightColorH);
                rec.start();
                mSideRightMp4Recorder = rec;
            } else if (mSideRightColorFormat == Format.YUYV) {
                Log.i(TAG, "Side-right color is YUYV; MP4 recording skipped");
            }
        }
    }

    /** 创建单个 MP4 录制器；诊断模式下同时写裸码流文件。 */
    private Mp4Recorder createMp4Recorder(File dir, String stem, VideoCodec codec,
                                          int width, int height) {
        String mp4Path = new File(dir, stem + ".mp4").getAbsolutePath();
        String rawExtension = codec == VideoCodec.H265 ? ".h265" : ".h264";
        String rawPath = DUMP_ENCODED_RAW_FOR_DIAGNOSTIC
                ? new File(dir, stem + "_raw" + rawExtension).getAbsolutePath()
                : null;
        return new Mp4Recorder(codec, mp4Path, width, height, rawPath);
    }

    /** 停止所有 MP4 录制器（非阻塞 stop，完整等待在 stopCollecting 中）。 */
    private void stopMp4Recording() {
        Mp4Recorder left = mLeftMp4Recorder;
        Mp4Recorder right = mRightMp4Recorder;
        Mp4Recorder sideLeft = mSideLeftMp4Recorder;
        Mp4Recorder sideRight = mSideRightMp4Recorder;
        mLeftMp4Recorder = null;
        mRightMp4Recorder = null;
        mSideLeftMp4Recorder = null;
        mSideRightMp4Recorder = null;
        if (left != null) left.stop();
        if (right != null) right.stop();
        if (sideLeft != null) sideLeft.stop();
        if (sideRight != null) sideRight.stop();
    }

    /** 启动音频 WAV 录制；无音频 Sensor 时跳过。 */
    private void startWavRecording(File dir, String session) {
        if (mAudioSensor == null) {
            return;
        }
        WavRecorder rec = new WavRecorder(
                new File(dir, "audio_" + session + ".wav").getAbsolutePath(),
                mWavSampleRate, mWavChannels, mWavBits);
        if (rec.start()) {
            mAudioProcessor = new PcmProcessor(mWavChannels);
            mWavRecorder = rec;
        }
    }

    /** 停止并完成 WAV 写入；未录制时安全无操作。 */
    private void stopWavRecording() {
        WavRecorder rec = mWavRecorder;
        mWavRecorder = null;
        mAudioProcessor = null;
        if (rec != null) {
            rec.stop();
        }
    }

    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // 标定 YAML 导出
    // -------------------------------------------------------------------------

    /** 后台预读对齐/IMU 标定 YAML 到内存缓存，Collect 时直接写文件。 */
    private void prefetchCalibAsync() {
        final Device device = mDevice;
        if (device == null) {
            return;
        }
        new Thread(() -> {
            getAlignCalib();
            getImuCalib();
        }, "CalibPrefetch").start();
    }

    /** 将缓存的标定 YAML 写入采集目录（与 CSV 同 session 命名）。 */
    private void saveCalibToDir(File dir, String session) {
        int savedCount = 0;
        savedCount += writeCalibFile(getAlignCalib(),
                new File(dir, "align_calib_" + session + ".yaml"), "Align calibration");
        savedCount += writeCalibFile(getImuCalib(),
                new File(dir, "imu_calib_" + session + ".yaml"), "IMU calibration");
        if (savedCount > 0) {
            Log.i(TAG, "Saved " + savedCount + " calib file(s) to " + dir.getAbsolutePath());
        }
    }

    /** 获取对齐标定：优先缓存，否则加锁从设备读一次。 */
    private byte[] getAlignCalib() {
        byte[] data = mAlignCalibData;
        if (data != null) {
            return data;
        }
        synchronized (mCalibLock) {
            data = mAlignCalibData;
            if (data == null && mDevice != null) {
                data = readRawData(mDevice,
                        DeviceProperty.OB_RAW_DATA_ALIGN_CALIB_YAML.value(), "Align calibration");
                mAlignCalibData = data;
            }
            return data;
        }
    }

    /** 获取 IMU 标定：优先缓存，否则加锁从设备读一次。 */
    private byte[] getImuCalib() {
        byte[] data = mImuCalibData;
        if (data != null) {
            return data;
        }
        synchronized (mCalibLock) {
            data = mImuCalibData;
            if (data == null && mDevice != null) {
                data = readRawData(mDevice,
                        DeviceProperty.OB_RAW_DATA_IMU_CALIB_YAML.value(), "IMU calibration");
                mImuCalibData = data;
            }
            return data;
        }
    }

    /** 阻塞读取设备 raw 属性；失败或空数据返回 null。每个连接每类标定最多读一次。 */
    @Nullable
    private byte[] readRawData(Device device, int propertyId, String label) {
        try {
            byte[] data = device.getRawData(propertyId);
            if (data == null || data.length == 0) {
                Log.w(TAG, label + ": empty data");
                return null;
            }
            return data;
        } catch (OBException e) {
            Log.e(TAG, label + " read failed: " + e.getMessage());
            return null;
        }
    }

    /** 将标定字节写入文件；成功返回 1，否则 0。 */
    private int writeCalibFile(byte[] data, File file, String label) {
        if (data == null) {
            Log.w(TAG, label + ": no data to save");
            return 0;
        }
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
            Log.i(TAG, label + " saved: " + file.getAbsolutePath() + " (" + data.length + " bytes)");
            return 1;
        } catch (IOException e) {
            Log.e(TAG, label + " save failed: " + e.getMessage());
            showToast(label + ": save failed");
            return 0;
        }
    }

    // -------------------------------------------------------------------------

    /** 在主线程显示短 Toast。 */
    private void showToast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }
}
