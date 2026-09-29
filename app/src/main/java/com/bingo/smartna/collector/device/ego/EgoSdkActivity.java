package com.bingo.smartna.collector.device.ego;

import android.content.res.AssetManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.bingo.smartna.collector.device.OrbbecNativeLoader;

import com.orbbec.obsensor.DeviceChangedCallback;
import com.orbbec.obsensor.OBContext;
import com.orbbec.obsensor.OBException;
import com.orbbec.obsensor.Pipeline;
import com.orbbec.obsensor.StreamProfileList;
import com.orbbec.obsensor.VideoStreamProfile;
import com.orbbec.obsensor.types.AlignMode;
import com.orbbec.obsensor.types.Format;
import com.orbbec.obsensor.types.LogSeverity;
import com.orbbec.obsensor.types.SensorType;
import com.orbbec.obsensor.types.StreamType;
import com.orbbec.obsensor.types.UvcBackendType;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Orbbec SDK 示例 Activity 的公共基类。
 * <p>
 * 职责：
 * <ul>
 *   <li>初始化 / 释放 {@link OBContext}（整个 App 只允许一个实例）</li>
 *   <li>从 assets 或外部存储加载 {@code OrbbecSDKConfig.xml}</li>
 *   <li>提供按传感器类型挑选合适 {@link VideoStreamProfile} 的工具方法</li>
 *   <li>提供深度-彩色 D2C 对齐所需的 profile 组合工具</li>
 * </ul>
 * 子类（如 {@link EgoSampleActivity}）只需实现 {@link #getDeviceChangedCallback()}，
 * 在设备插拔回调里完成各自的业务逻辑。
 */
public abstract class EgoSdkActivity extends AppCompatActivity {
    private final static String TAG = "EgoSdkActivity";
    /** SDK 配置文件名，会从 assets 复制到外部存储后供 OBContext 读取 */
    private final static String XML_CONFIG_FILE_NAME = "OrbbecSDKConfig.xml";

    /**
     * Orbbec SDK 的全局入口；一个 Application 内只能存在一个实例。
     */
    protected OBContext mOBContext;

    /**
     * 返回本 Activity 使用的设备插拔监听器，由子类提供具体实现。
     */
    protected abstract DeviceChangedCallback getDeviceChangedCallback();

    /**
     * 初始化 SDK：设置日志级别、加载 XML 配置、创建 {@link #mOBContext}。
     */
    private String sdkConfigDir() {
        File dir = getExternalFilesDir("Orbbec");
        if (dir == null) {
            dir = new File(getFilesDir(), "Orbbec");
        }
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir.getAbsolutePath();
    }

    protected void initSDK() {
        try {
            OrbbecNativeLoader.INSTANCE.ensureLoaded();
            OBContext.setLoggerSeverity(LogSeverity.DEBUG);
            OBContext.setLoggerToConsole(LogSeverity.DEBUG);

            Log.i(TAG, "initSDK CoreVersionName：" + OBContext.getCoreVersionName());
            DeviceChangedCallback deviceChangedCallback = getDeviceChangedCallback();

            // 1. 初始化 SDK Context 并监听设备插拔
            String configFilePath = initXmlConfigFile();
            if (!TextUtils.isEmpty(configFilePath)) {
                mOBContext = new OBContext(getApplicationContext(), configFilePath, deviceChangedCallback);
            } else {
                mOBContext = new OBContext(getApplicationContext(), deviceChangedCallback);
            }
            mOBContext.setUvcBackendType(UvcBackendType.OB_UVC_BACKEND_TYPE_LIBUVC);
        } catch (OBException e) {
            Log.e(TAG, "initSDK: " + e.getMessage());
        }
    }

    /** 释放 SDK Context，在 Activity 销毁时调用。 */
    protected void releaseSDK() {
        try {
            if (null != mOBContext) {
                mOBContext.close();
            }
        } catch (OBException e) {
            e.printStackTrace();
        }
    }

    /** 打印一条视频流 profile 的分辨率、帧率、格式，便于调试。 */
    protected final void printStreamProfile(VideoStreamProfile vsp) {
        Log.i(TAG, "printStreamProfile: "
                + vsp.getWidth() + "×" + vsp.getHeight()
                + "@" + vsp.getFps() + "fps " + vsp.getFormat());
    }

    /**
     * 从外部存储查找已存在的 SDK 配置文件路径。
     * 若不存在则返回 null（不会从 assets 复制）。
     */
    protected final String getXmlConfigFile() {
        File file = new File(sdkConfigDir() + File.separator + XML_CONFIG_FILE_NAME);
        if (file.exists()) {
            return file.getAbsolutePath();
        }
        return null;
    }

    /**
     * 若外部存储尚无配置文件，则从 assets 复制一份，并返回其绝对路径。
     */
    private String initXmlConfigFile() {
        String savePath = sdkConfigDir() + File.separator + XML_CONFIG_FILE_NAME;
        File file = new File(savePath);

        if (!file.exists()) {
            try {
                AssetManager manager = getAssets();
                InputStream is = manager.open(XML_CONFIG_FILE_NAME);

                File parentDir = file.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }

                OutputStream os = new FileOutputStream(file);
                byte[] buffer = new byte[1024];
                int length;
                while ((length = is.read(buffer)) > 0) {
                    os.write(buffer, 0, length);
                }

                os.flush();
                os.close();
                is.close();

            } catch (Exception e) {
                Log.e(TAG, "initXmlConfigFile: " + e.getMessage());
                return null;
            }
        }

        return file.getAbsolutePath();
    }

    /**
     * 从 Pipeline 支持的 profile 列表中，挑选最适合示例 App 渲染/保存的一条视频流。
     * <p>
     * 示例仅支持有限格式：彩色 RGB、红外 Y8、深度 Y16；宽度优先 640~1280。
     * 排序规则：帧率优先 → 宽度优先 → 高度优先。
     *
     * @param pipeline   已绑定设备的 Pipeline
     * @param sensorType 目标传感器类型
     * @return 成功返回选中的 profile；失败返回 null
     */
    protected final VideoStreamProfile getStreamProfile(Pipeline pipeline, SensorType sensorType) {
        // 按传感器类型选择首选像素格式
        Format format;
        if (sensorType == SensorType.COLOR) {
            format = Format.RGB;
        } else if (sensorType == SensorType.IR
                || sensorType == SensorType.IR_LEFT
                || sensorType == SensorType.IR_RIGHT) {
            format = Format.Y8;
        } else if (sensorType == SensorType.DEPTH) {
            format = Format.Y16;
        } else {
            Log.w(TAG, "getStreamProfile not support sensorType: " + sensorType);
            return null;
        }

        try {
            StreamProfileList profileList = pipeline.getStreamProfileList(sensorType);
            List<VideoStreamProfile> profiles = new ArrayList<>();
            for (int i = 0, N = profileList.getCount(); i < N; i++) {
                VideoStreamProfile profile = profileList.getProfile(i).as(StreamType.VIDEO);
                // 宽度 640~1280 被认为最适合示例渲染
                if ((profile.getWidth() >= 640 && profile.getWidth() <= 1280)
                        && profile.getHeight() >= 360
                        && profile.getFormat() == format) {
                    profiles.add(profile);
                } else {
                    profile.close();
                }
            }
            // 若没有匹配首选格式，则放宽条件（但不支持 MJPEG、RVL）
            if (profiles.isEmpty() && profileList.getCount() > 0) {
                for (int i = 0, N = profileList.getCount(); i < N; i++) {
                    VideoStreamProfile profile = profileList.getProfile(i).as(StreamType.VIDEO);
                    if ((profile.getWidth() >= 640 && profile.getWidth() <= 1280)
                            && profile.getHeight() >= 360
                            && (profile.getFormat() != Format.MJPG && profile.getFormat() != Format.RVL)) {
                        profiles.add(profile);
                    } else {
                        profile.close();
                    }
                }
            }
            profileList.close();

            // 排序优先级：帧率 > 宽度 > 高度（均为从高到低）
            Collections.sort(profiles, new Comparator<VideoStreamProfile>() {
                @Override
                public int compare(VideoStreamProfile o1, VideoStreamProfile o2) {
                    if (o1.getFps() != o2.getFps()) {
                        return o2.getFps() - o1.getFps();
                    }
                    if (o1.getWidth() != o2.getWidth()) {
                        return o2.getWidth() - o1.getWidth();
                    }
                    return o2.getHeight() - o1.getHeight();
                }
            });
            for (VideoStreamProfile p : profiles) {
                Log.d(TAG, sensorType + " getStreamProfile " + p.getWidth() + "x" + p.getHeight() + "--" + p.getFps());
            }

            if (profiles.isEmpty()) {
                return null;
            }

            VideoStreamProfile retProfile = profiles.get(0);

            // 释放未选中的 profile，避免 native 泄漏
            for (int i = 0; i < profiles.size(); i++) {
                if (profiles.get(i) != retProfile) {
                    profiles.get(i).close();
                }
            }
            return retProfile;
        } catch (Exception e) {
            Log.e(TAG, "getStreamProfile: " + e.getMessage());
        }
        return null;
    }

    /**
     * 生成 D2C（深度对齐到彩色）所需的彩色 + 深度 profile 组合。
     *
     * @param pipeline  Pipeline
     * @param alignMode 对齐模式（硬件/软件 D2C 或关闭）
     * @return 成功返回包含 color/depth 的 {@link D2CStreamProfile}；失败返回 null
     */
    protected D2CStreamProfile genD2CStreamProfile(Pipeline pipeline, AlignMode alignMode) {
        // 选择彩色 profile
        VideoStreamProfile colorProfile = null;
        List<VideoStreamProfile> colorProfiles = getAvailableColorProfiles(pipeline, alignMode);
        if (colorProfiles.isEmpty()) {
            Log.w(TAG, "genConfig failed. colorProfiles is empty");
            return null;
        }
        for (VideoStreamProfile profile : colorProfiles) {
            if (profile.getWidth() >= 640 && profile.getWidth() <= 1280 && profile.getFormat() == Format.RGB) {
                colorProfile = profile;
                break;
            }
        }
        if (null == colorProfile) {
            if (!colorProfiles.isEmpty()) {
                colorProfile = colorProfiles.get(0);
            } else {
                Log.w(TAG, "genConfig failed. not match color profile width >= 640 and width <= 1280");
                return null;
            }
        }
        // 释放未使用的彩色 profile
        for (VideoStreamProfile profile : colorProfiles) {
            if (profile != colorProfile) {
                profile.close();
            }
        }
        colorProfiles.clear();

        // 选择深度 profile
        VideoStreamProfile depthProfile = null;
        List<VideoStreamProfile> depthProfiles = getAvailableDepthProfiles(pipeline, colorProfile, alignMode);
        for (VideoStreamProfile profile : depthProfiles) {
            if (profile.getWidth() >= 640 && profile.getWidth() <= 1280 && profile.getFormat() == Format.Y16) {
                depthProfile = profile;
                break;
            }
        }
        if (null == depthProfile) {
            if (!depthProfiles.isEmpty()) {
                depthProfile = depthProfiles.get(0);
            } else {
                Log.w(TAG, "genConfig failed. not match depth profile width >= 640 and width <= 1280");
                colorProfile.close();
                colorProfile = null;
                return null;
            }
        }
        // 释放未使用的深度 profile
        for (VideoStreamProfile profile : depthProfiles) {
            if (depthProfile != profile) {
                profile.close();
            }
        }
        depthProfiles.clear();

        D2CStreamProfile d2CStreamProfile = new D2CStreamProfile();
        d2CStreamProfile.colorProfile = colorProfile;
        d2CStreamProfile.depthProfile = depthProfile;
        return d2CStreamProfile;
    }

    /**
     * 获取在当前对齐模式下可用的彩色 profile 列表。
     * 开启 D2C 时，会过滤掉没有匹配深度 profile 的彩色 profile。
     */
    private List<VideoStreamProfile> getAvailableColorProfiles(Pipeline pipeline, AlignMode alignMode) {
        List<VideoStreamProfile> colorProfiles = new ArrayList<>();
        StreamProfileList depthProfileList = null;
        try (StreamProfileList colorProfileList = pipeline.getStreamProfileList(SensorType.COLOR)) {
            final int profileCount = colorProfileList.getCount();
            for (int i = 0; i < profileCount; i++) {
                colorProfiles.add(colorProfileList.getProfile(i).as(StreamType.VIDEO));
            }
            sortVideoStreamProfiles(colorProfiles);

            // 关闭 D2C 时，所有彩色 profile 都可用
            if (alignMode == AlignMode.ALIGN_DISABLE) {
                return colorProfiles;
            }

            // 过滤没有对应深度 profile 的彩色 profile
            for (int i = colorProfiles.size() - 1; i >= 0; i--) {
                VideoStreamProfile colorProfile = colorProfiles.get(i);
                depthProfileList = pipeline.getD2CDepthProfileList(colorProfile, alignMode);
                if (null == depthProfileList || depthProfileList.getCount() == 0) {
                    colorProfiles.remove(i);
                    colorProfile.close();
                }
                depthProfileList.close();
                depthProfileList = null;
            }
            return colorProfiles;
        } catch (OBException e) {
            Log.e(TAG, "getAvailableColorProfiles: " + e.getMessage());
        } finally {
            if (null != depthProfileList) {
                depthProfileList.close();
                depthProfileList = null;
            }
        }
        return colorProfiles;
    }

    /**
     * 根据指定彩色 profile 和对齐模式，获取可配对的深度 profile 列表。
     */
    private List<VideoStreamProfile> getAvailableDepthProfiles(Pipeline pipeline, VideoStreamProfile colorProfile, AlignMode alignMode) {
        List<VideoStreamProfile> depthProfiles = new ArrayList<>();
        try (StreamProfileList depthProfileList = pipeline.getD2CDepthProfileList(colorProfile, alignMode)) {
            final int profileCount = depthProfileList.getCount();
            for (int i = 0; i < profileCount; i++) {
                depthProfiles.add(depthProfileList.getProfile(i).as(StreamType.VIDEO));
            }
            sortVideoStreamProfiles(depthProfiles);
        } catch (OBException e) {
            Log.e(TAG, "getAvailableDepthProfiles: " + e.getMessage());
        }
        return depthProfiles;
    }

    /** 对视频 profile 列表排序：格式 → 宽度升序 → 高度降序 → 帧率降序。 */
    private void sortVideoStreamProfiles(List<VideoStreamProfile> profiles) {
        Collections.sort(profiles, new Comparator<VideoStreamProfile>() {
            @Override
            public int compare(VideoStreamProfile o1, VideoStreamProfile o2) {
                if (o1.getFormat() != o2.getFormat()) {
                    return o1.getFormat().value() - o2.getFormat().value();
                }
                if (o1.getWidth() != o2.getWidth()) {
                    return o1.getWidth() - o2.getWidth();
                }
                if (o1.getHeight() != o2.getHeight()) {
                    return o2.getHeight() - o1.getHeight();
                }
                return o2.getFps() - o1.getFps();
            }
        });
    }

    /**
     * D2C 对齐用的数据容器，同时持有彩色与深度 {@link VideoStreamProfile}。
     * 实现 {@link AutoCloseable}，用完需 close 释放 native 资源。
     */
    protected static class D2CStreamProfile implements AutoCloseable {
        /** 彩色流 profile */
        private VideoStreamProfile colorProfile;
        /** 深度流 profile */
        private VideoStreamProfile depthProfile;

        public VideoStreamProfile getColorProfile() {
            return colorProfile;
        }

        public void setColorProfile(VideoStreamProfile colorProfile) {
            this.colorProfile = colorProfile;
        }

        public VideoStreamProfile getDepthProfile() {
            return depthProfile;
        }

        public void setDepthProfile(VideoStreamProfile depthProfile) {
            this.depthProfile = depthProfile;
        }

        @Override
        public void close() {
            if (null != colorProfile) {
                try {
                    colorProfile.close();
                } catch (Exception ignore) {
                }
                colorProfile = null;
            }
            if (null != depthProfile) {
                try {
                    depthProfile.close();
                } catch (Exception ignore) {
                }
                depthProfile = null;
            }
        }
    }
}
