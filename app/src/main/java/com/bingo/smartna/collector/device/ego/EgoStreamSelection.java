package com.bingo.smartna.collector.device.ego;

import com.orbbec.obsensor.AccelStreamProfile;
import com.orbbec.obsensor.Device;
import com.orbbec.obsensor.GyroStreamProfile;
import com.orbbec.obsensor.Pipeline;
import com.orbbec.obsensor.Sensor;
import com.orbbec.obsensor.StreamProfile;
import com.orbbec.obsensor.StreamProfileList;
import com.orbbec.obsensor.VideoStreamProfile;
import com.orbbec.obsensor.types.AccelFullScaleRange;
import com.orbbec.obsensor.types.Format;
import com.orbbec.obsensor.types.GyroFullScaleRange;
import com.orbbec.obsensor.types.IMUSampleRate;
import com.orbbec.obsensor.types.SensorType;
import com.orbbec.obsensor.types.StreamType;

import java.util.ArrayList;
import java.util.List;

/** Value-only stream configuration. Native StreamProfile objects are deliberately not retained. */
final class EgoStreamSelection {
    /** Fixed display/recording order for a four-camera EgoPro device. */
    static final SensorType[] QUAD_COLOR_ORDER = {
            SensorType.COLOR_SIDE_LEFT,
            SensorType.COLOR_LEFT,
            SensorType.COLOR_RIGHT,
            SensorType.COLOR_SIDE_RIGHT
    };

    boolean leftEnabled = true;
    boolean rightEnabled = true;
    boolean sideLeftEnabled = true;
    boolean sideRightEnabled = true;
    boolean accelEnabled = true;
    boolean gyroEnabled = true;
    /** Whether color streams should be decoded and rendered for preview. */
    boolean videoDecodeEnabled = true;
    /** Whether Collect should record color streams to MP4 files. */
    boolean videoRecordingEnabled = true;
    /** Let device firmware select the color encoder bitrate. */
    boolean dynamicBitrateEnabled = true;
    /** Manual color encoder bitrate in bps; used only when dynamic bitrate is disabled. */
    int colorBitrate = 0;
    /** Color bitrate range reported after the currently selected video profile starts. */
    int colorBitrateMin = 0;
    int colorBitrateMax = 0;
    int colorBitrateStep = 0;
    VideoSpec leftVideo;
    VideoSpec rightVideo;
    VideoSpec sideLeftVideo;
    VideoSpec sideRightVideo;
    ImuSpec accel;
    ImuSpec gyro;

    static final class VideoSpec {
        final SensorType sensorType;
        final int width, height, fps;
        final Format format;
        VideoSpec(SensorType type, int width, int height, int fps, Format format) {
            this.sensorType = type; this.width = width; this.height = height;
            this.fps = fps; this.format = format;
        }
        boolean sameEncoding(VideoSpec other) {
            return other != null && width == other.width && height == other.height
                    && fps == other.fps && format == other.format;
        }
        @Override public boolean equals(Object obj) {
            if (!(obj instanceof VideoSpec)) return false;
            VideoSpec other = (VideoSpec) obj;
            return sensorType == other.sensorType && sameEncoding(other);
        }
        @Override public int hashCode() {
            return (((sensorType.ordinal() * 31 + width) * 31 + height) * 31 + fps) * 31 + format.ordinal();
        }
        String label() { return format + " · " + width + " × " + height + " · " + fps + " fps"; }
    }

    static final class ImuSpec {
        final SensorType sensorType;
        final IMUSampleRate rate;
        final Object range;
        ImuSpec(SensorType type, IMUSampleRate rate, Object range) {
            this.sensorType = type; this.rate = rate; this.range = range;
        }
        String label() { return rate + " · " + range; }
        @Override public boolean equals(Object obj) {
            if (!(obj instanceof ImuSpec)) return false;
            ImuSpec other = (ImuSpec) obj;
            return sensorType == other.sensorType && rate == other.rate && range == other.range;
        }
        @Override public int hashCode() {
            return ((sensorType.ordinal() * 31 + rate.ordinal()) * 31) + range.hashCode();
        }
    }

    static List<VideoSpec> readVideo(Pipeline pipeline, SensorType sensorType,
                                     boolean includeRawYuyv) {
        List<VideoSpec> result = new ArrayList<>();
        StreamProfileList list = pipeline.getStreamProfileList(sensorType);
        if (list == null) return result;
        try {
            for (int i = 0; i < list.getCount(); i++) {
                StreamProfile profile = list.getProfile(i);
                try {
                    // Raw YUYV needs a separate OpenGL rendering path. Only expose
                    // it to callers that explicitly support that path.
                    if (profile.getFormat() != Format.H264 && profile.getFormat() != Format.H265
                            && (!includeRawYuyv || profile.getFormat() != Format.YUYV)) continue;
                    VideoStreamProfile video = profile.as(toStreamType(sensorType));
                    result.add(new VideoSpec(sensorType, video.getWidth(), video.getHeight(),
                            video.getFps(), profile.getFormat()));
                } finally { profile.close(); }
            }
        } finally { list.close(); }
        return result;
    }

    /** Returns the profiles available with identical encoding on every four-camera stream. */
    static List<VideoSpec> readQuadVideo(Pipeline pipeline, boolean includeRawYuyv) {
        List<VideoSpec> common = readVideo(pipeline, QUAD_COLOR_ORDER[0], includeRawYuyv);
        for (int i = 1; i < QUAD_COLOR_ORDER.length; i++) {
            List<VideoSpec> candidates = readVideo(pipeline, QUAD_COLOR_ORDER[i], includeRawYuyv);
            for (int j = common.size() - 1; j >= 0; j--) {
                boolean found = false;
                for (VideoSpec candidate : candidates) {
                    if (common.get(j).sameEncoding(candidate)) { found = true; break; }
                }
                if (!found) common.remove(j);
            }
        }
        return common;
    }

    static List<ImuSpec> readImu(Device device, SensorType sensorType) {
        List<ImuSpec> result = new ArrayList<>();
        Sensor sensor = device.getSensor(sensorType);
        if (sensor == null) return result;
        StreamProfileList list = sensor.getStreamProfileList();
        if (list == null) return result;
        try {
            for (int i = 0; i < list.getCount(); i++) {
                StreamProfile profile = list.getProfile(i);
                try {
                    if (sensorType == SensorType.ACCEL) {
                        AccelStreamProfile p = profile.as(StreamType.ACCEL);
                        result.add(new ImuSpec(sensorType, p.getSampleRate(), p.getFullScaleRange()));
                    } else {
                        GyroStreamProfile p = profile.as(StreamType.GYRO);
                        result.add(new ImuSpec(sensorType, p.getSampleRate(), p.getFullScaleRange()));
                    }
                } finally { profile.close(); }
            }
        } finally { list.close(); }
        return result;
    }

    static StreamType toStreamType(SensorType type) {
        if (type == SensorType.COLOR_LEFT) return StreamType.COLOR_LEFT;
        if (type == SensorType.COLOR_RIGHT) return StreamType.COLOR_RIGHT;
        if (type == SensorType.COLOR_SIDE_LEFT) return StreamType.COLOR_SIDE_LEFT;
        if (type == SensorType.COLOR_SIDE_RIGHT) return StreamType.COLOR_SIDE_RIGHT;
        return StreamType.COLOR;
    }

    static boolean isQuadColorDevice(Device device) {
        for (SensorType type : QUAD_COLOR_ORDER) {
            if (!device.hasSensor(type)) return false;
        }
        return true;
    }

    VideoSpec getVideo(SensorType type) {
        if (type == SensorType.COLOR_LEFT || type == SensorType.COLOR) return leftVideo;
        if (type == SensorType.COLOR_RIGHT) return rightVideo;
        if (type == SensorType.COLOR_SIDE_LEFT) return sideLeftVideo;
        if (type == SensorType.COLOR_SIDE_RIGHT) return sideRightVideo;
        return null;
    }

    boolean isVideoEnabled(SensorType type) {
        if (type == SensorType.COLOR_LEFT || type == SensorType.COLOR) return leftEnabled;
        if (type == SensorType.COLOR_RIGHT) return rightEnabled;
        if (type == SensorType.COLOR_SIDE_LEFT) return sideLeftEnabled;
        return type == SensorType.COLOR_SIDE_RIGHT && sideRightEnabled;
    }

    static boolean matches(StreamProfile profile, VideoSpec wanted) {
        if (wanted == null || profile.getFormat() != wanted.format) return false;
        VideoStreamProfile p = profile.as(toStreamType(wanted.sensorType));
        return p.getWidth() == wanted.width && p.getHeight() == wanted.height && p.getFps() == wanted.fps;
    }

    static boolean matches(StreamProfile profile, ImuSpec wanted) {
        if (wanted == null) return false;
        if (wanted.sensorType == SensorType.ACCEL) {
            AccelStreamProfile p = profile.as(StreamType.ACCEL);
            return p.getSampleRate() == wanted.rate && p.getFullScaleRange() == wanted.range;
        }
        GyroStreamProfile p = profile.as(StreamType.GYRO);
        return p.getSampleRate() == wanted.rate && p.getFullScaleRange() == wanted.range;
    }
}
