package com.bingo.smartna.collector.device.ego;

import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.orbbec.obsensor.Device;
import com.orbbec.obsensor.Pipeline;
import com.orbbec.obsensor.property.DeviceProperty;
import com.orbbec.obsensor.types.IMUSampleRate;
import com.orbbec.obsensor.types.PermissionType;
import com.orbbec.obsensor.types.SensorType;
import com.bingo.smartna.R;

import java.util.ArrayList;
import java.util.List;

final class EgoStreamConfigDialog {
    interface Callback { void onApply(EgoStreamSelection selection); }

    static void show(Context context, Device device, Pipeline pipeline, boolean stereo,
                     boolean hasAccel, boolean hasGyro, boolean includeRawYuyv,
                     boolean showVideoRecordingControl,
                     EgoStreamSelection current, Callback callback) {
        List<EgoStreamSelection.VideoSpec> left = EgoStreamSelection.readVideo(pipeline,
                stereo ? SensorType.COLOR_LEFT : SensorType.COLOR, includeRawYuyv);
        List<EgoStreamSelection.VideoSpec> right = stereo
                ? EgoStreamSelection.readVideo(pipeline, SensorType.COLOR_RIGHT, includeRawYuyv)
                : new ArrayList<>();
        List<EgoStreamSelection.ImuSpec> accel = hasAccel
                ? EgoStreamSelection.readImu(device, SensorType.ACCEL) : new ArrayList<>();
        List<EgoStreamSelection.ImuSpec> gyro = hasGyro
                ? EgoStreamSelection.readImu(device, SensorType.GYRO) : new ArrayList<>();
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_ego_stream_config);
        Switch leftSwitch = dialog.findViewById(R.id.switch_left_color);
        Switch rightSwitch = dialog.findViewById(R.id.switch_right_color);
        Switch videoDecodeSwitch = dialog.findViewById(R.id.switch_video_decode);
        Switch videoRecordingSwitch = dialog.findViewById(R.id.switch_video_recording);
        Switch dynamicBitrateSwitch = dialog.findViewById(R.id.switch_dynamic_bitrate);
        SeekBar colorBitrateSeekBar = dialog.findViewById(R.id.seekbar_color_bitrate);
        TextView colorBitrateView = dialog.findViewById(R.id.tv_color_bitrate);
        Switch accelSwitch = dialog.findViewById(R.id.switch_accel);
        Switch gyroSwitch = dialog.findViewById(R.id.switch_gyro);
        Spinner leftSpinner = dialog.findViewById(R.id.spinner_left_color);
        Spinner rightSpinner = dialog.findViewById(R.id.spinner_right_color);
        Spinner accelSpinner = dialog.findViewById(R.id.spinner_accel);
        Spinner gyroSpinner = dialog.findViewById(R.id.spinner_gyro);
        TextView error = dialog.findViewById(R.id.tv_stream_config_error);
        bind(context, leftSpinner, left, current == null ? null : current.leftVideo);
        bind(context, rightSpinner, right, current == null ? null : current.rightVideo);
        bind(context, accelSpinner, accel, current == null ? null : current.accel);
        bind(context, gyroSpinner, gyro, current == null ? null : current.gyro);
        dialog.findViewById(R.id.right_color_group).setVisibility(stereo ? View.VISIBLE : View.GONE);
        accelSwitch.setVisibility(hasAccel ? View.VISIBLE : View.GONE); accelSpinner.setVisibility(hasAccel ? View.VISIBLE : View.GONE);
        gyroSwitch.setVisibility(hasGyro ? View.VISIBLE : View.GONE); gyroSpinner.setVisibility(hasGyro ? View.VISIBLE : View.GONE);
        leftSwitch.setText(stereo ? "Left Color" : "Color");
        videoDecodeSwitch.setChecked(current == null || current.videoDecodeEnabled);
        videoRecordingSwitch.setVisibility(showVideoRecordingControl ? View.VISIBLE : View.GONE);
        videoRecordingSwitch.setChecked(current == null || current.videoRecordingEnabled);
        dynamicBitrateSwitch.setChecked(current == null || current.dynamicBitrateEnabled);
        // Prefer the range refreshed after the active profile started. Reading it
        // again here could return the previous profile's range while a restart is
        // being prepared.
        BitrateRange cachedBitrateRange = BitrateRange.fromSelection(current);
        final BitrateRange bitrateRange = cachedBitrateRange.available
                ? cachedBitrateRange : readBitrateRange(device);
        int initialBitrate = current == null ? bitrateRange.min : current.colorBitrate;
        // The range belongs to the active profile and is cached after start, but
        // dynamic bitrate may change while streaming. Read only the current value
        // here so the slider label reflects the device state without refreshing a
        // range for a profile that is about to be changed.
        initialBitrate = readCurrentBitrate(device, initialBitrate);
        if (initialBitrate <= 0) initialBitrate = bitrateRange.min;
        if (bitrateRange.available) {
            colorBitrateSeekBar.setMax(bitrateRange.steps);
            colorBitrateSeekBar.setProgress(bitrateRange.toProgress(initialBitrate));
            colorBitrateView.setText(bitrateRange.label(bitrateRange.toValue(colorBitrateSeekBar.getProgress())));
        } else {
            colorBitrateView.setText("Manual color bitrate unavailable on this device");
        }
        leftSwitch.setChecked(current == null || current.leftEnabled); rightSwitch.setChecked(current == null || current.rightEnabled);
        accelSwitch.setChecked(current == null || current.accelEnabled); gyroSwitch.setChecked(current == null || current.gyroEnabled);
        View.OnClickListener refresh = v -> { leftSpinner.setEnabled(leftSwitch.isChecked()); rightSpinner.setEnabled(rightSwitch.isChecked()); accelSpinner.setEnabled(accelSwitch.isChecked()); gyroSpinner.setEnabled(gyroSwitch.isChecked()); };
        leftSwitch.setOnClickListener(refresh); rightSwitch.setOnClickListener(refresh); accelSwitch.setOnClickListener(refresh); gyroSwitch.setOnClickListener(refresh); refresh.onClick(null);
        colorBitrateSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (bitrateRange.available) colorBitrateView.setText(bitrateRange.label(bitrateRange.toValue(progress)));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        View.OnClickListener refreshBitrate = v -> colorBitrateSeekBar.setEnabled(
                !dynamicBitrateSwitch.isChecked() && bitrateRange.available);
        dynamicBitrateSwitch.setOnClickListener(refreshBitrate); refreshBitrate.onClick(null);
        final boolean[] syncing = {false};
        leftSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
                if (syncing[0] || !stereo || !rightSwitch.isChecked()) return;
                int match = matchingVideoIndex(right, item(leftSpinner, left));
                if (match >= 0) { syncing[0] = true; rightSpinner.setSelection(match); syncing[0] = false; }
                else error.setText("No matching right Color profile.");
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        rightSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
                if (syncing[0] || !stereo || !leftSwitch.isChecked()) return;
                int match = matchingVideoIndex(left, item(rightSpinner, right));
                if (match >= 0) { syncing[0] = true; leftSpinner.setSelection(match); syncing[0] = false; }
                else error.setText("No matching left Color profile.");
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        accelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
                if (syncing[0] || !gyroSwitch.isChecked()) return;
                int match = matchingRateIndex(gyro, item(accelSpinner, accel).rate);
                if (match >= 0) { syncing[0] = true; gyroSpinner.setSelection(match); syncing[0] = false; }
                else error.setText("No matching Gyro sample rate.");
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        gyroSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
                if (syncing[0] || !accelSwitch.isChecked()) return;
                int match = matchingRateIndex(accel, item(gyroSpinner, gyro).rate);
                if (match >= 0) { syncing[0] = true; accelSpinner.setSelection(match); syncing[0] = false; }
                else error.setText("No matching Accel sample rate.");
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        ((Button) dialog.findViewById(R.id.btn_apply_stream_config)).setOnClickListener(v -> {
            EgoStreamSelection selected = new EgoStreamSelection();
            selected.leftEnabled = leftSwitch.isChecked(); selected.rightEnabled = stereo && rightSwitch.isChecked();
            selected.videoDecodeEnabled = videoDecodeSwitch.isChecked();
            selected.videoRecordingEnabled = showVideoRecordingControl && videoRecordingSwitch.isChecked();
            selected.dynamicBitrateEnabled = dynamicBitrateSwitch.isChecked();
            if (!selected.dynamicBitrateEnabled) {
                if (!bitrateRange.available) {
                    error.setText("Manual color bitrate is unsupported on this device.");
                    return;
                }
                selected.colorBitrate = bitrateRange.toValue(colorBitrateSeekBar.getProgress());
            } else if (current != null) {
                selected.colorBitrate = current.colorBitrate;
            }
            selected.accelEnabled = hasAccel && accelSwitch.isChecked(); selected.gyroEnabled = hasGyro && gyroSwitch.isChecked();
            selected.leftVideo = item(leftSpinner, left); selected.rightVideo = item(rightSpinner, right);
            selected.accel = item(accelSpinner, accel); selected.gyro = item(gyroSpinner, gyro);
            if (!selected.leftEnabled && !selected.rightEnabled && !selected.accelEnabled && !selected.gyroEnabled) { error.setText("Select at least one stream."); return; }
            if (selected.leftEnabled && selected.leftVideo == null || selected.rightEnabled && selected.rightVideo == null || selected.accelEnabled && selected.accel == null || selected.gyroEnabled && selected.gyro == null) { error.setText("Selected profile is unavailable."); return; }
            if (selected.leftEnabled && selected.rightEnabled && !selected.leftVideo.sameEncoding(selected.rightVideo)) selected.rightVideo = matchingVideo(right, selected.leftVideo);
            if (selected.leftEnabled && selected.rightEnabled && selected.rightVideo == null) { error.setText("No matching right Color profile."); return; }
            if (selected.accelEnabled && selected.gyroEnabled && selected.accel.rate != selected.gyro.rate) selected.gyro = matchingRate(gyro, selected.accel.rate);
            if (selected.accelEnabled && selected.gyroEnabled && selected.gyro == null) { error.setText("No matching Gyro sample rate."); return; }
            callback.onApply(selected); dialog.dismiss();
        });
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(-1, -2);
    }
    private static <T> void bind(Context c, Spinner s, List<T> values, T selected) { List<String> labels = new ArrayList<>(); for (T value : values) labels.add(value instanceof EgoStreamSelection.VideoSpec ? ((EgoStreamSelection.VideoSpec) value).label() : ((EgoStreamSelection.ImuSpec) value).label()); s.setAdapter(new ArrayAdapter<>(c, android.R.layout.simple_spinner_dropdown_item, labels)); if (selected != null) for (int i = 0; i < values.size(); i++) if (values.get(i).equals(selected)) s.setSelection(i); }
    private static <T> T item(Spinner s, List<T> values) { int p = s.getSelectedItemPosition(); return p >= 0 && p < values.size() ? values.get(p) : null; }
    private static EgoStreamSelection.VideoSpec matchingVideo(List<EgoStreamSelection.VideoSpec> values, EgoStreamSelection.VideoSpec wanted) { for (EgoStreamSelection.VideoSpec value : values) if (value.sameEncoding(wanted)) return value; return null; }
    private static EgoStreamSelection.ImuSpec matchingRate(List<EgoStreamSelection.ImuSpec> values, IMUSampleRate wanted) { for (EgoStreamSelection.ImuSpec value : values) if (value.rate == wanted) return value; return null; }
    private static int matchingVideoIndex(List<EgoStreamSelection.VideoSpec> values, EgoStreamSelection.VideoSpec wanted) { for (int i = 0; i < values.size(); i++) if (values.get(i).sameEncoding(wanted)) return i; return -1; }
    private static int matchingRateIndex(List<EgoStreamSelection.ImuSpec> values, IMUSampleRate wanted) { for (int i = 0; i < values.size(); i++) if (values.get(i).rate == wanted) return i; return -1; }

    private static BitrateRange readBitrateRange(Device device) {
        try {
            DeviceProperty property = DeviceProperty.OB_PROP_COLOR_BITRATE_INT;
            if (!device.isPropertySupported(property, PermissionType.OB_PERMISSION_READ_WRITE)) {
                return BitrateRange.unavailable();
            }
            int min = device.getMinRangeI(property);
            int max = device.getMaxRangeI(property);
            int step = device.getStepI(property);
            if (min <= 0 || max < min || step <= 0) return BitrateRange.unavailable();
            long steps = ((long) max - min) / step;
            if (steps > Integer.MAX_VALUE) return BitrateRange.unavailable();
            return new BitrateRange(min, max, step, (int) steps);
        } catch (Exception e) {
            return BitrateRange.unavailable();
        }
    }

    private static int readCurrentBitrate(Device device, int fallback) {
        try {
            DeviceProperty property = DeviceProperty.OB_PROP_COLOR_BITRATE_INT;
            return device.isPropertySupported(property, PermissionType.OB_PERMISSION_READ)
                    ? device.getPropertyValueI(property) : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static final class BitrateRange {
        final int min, max, step, steps;
        final boolean available;
        BitrateRange(int min, int max, int step, int steps) {
            this.min = min; this.max = max; this.step = step; this.steps = steps; this.available = true;
        }
        private BitrateRange() { min = max = step = steps = 0; available = false; }
        static BitrateRange unavailable() { return new BitrateRange(); }
        static BitrateRange fromSelection(EgoStreamSelection selection) {
            if (selection == null || selection.colorBitrateMin <= 0
                    || selection.colorBitrateMax < selection.colorBitrateMin
                    || selection.colorBitrateStep <= 0) return unavailable();
            long steps = ((long) selection.colorBitrateMax - selection.colorBitrateMin)
                    / selection.colorBitrateStep;
            return steps > Integer.MAX_VALUE ? unavailable() : new BitrateRange(
                    selection.colorBitrateMin, selection.colorBitrateMax,
                    selection.colorBitrateStep, (int) steps);
        }
        int toProgress(int value) {
            long bounded = Math.max(min, Math.min(max, value));
            return (int) ((bounded - min) / step);
        }
        int toValue(int progress) { return min + Math.max(0, Math.min(steps, progress)) * step; }
        String label(int value) { return "Current color bitrate: " + value + " bps (" + min + "-" + max + ", step " + step + ")"; }
    }
}
