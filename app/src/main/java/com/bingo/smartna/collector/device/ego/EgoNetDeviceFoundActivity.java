package com.bingo.smartna.collector.device.ego;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.bingo.smartna.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wizard-style Ego device provisioning Activity.
 *
 * Flow: Scan (Ego* devices only) → dropdown select → Connect → Wi-Fi config → Query IP.
 * UI sections are shown/hidden automatically based on state; no manual address entry needed.
 */
public class EgoNetDeviceFoundActivity extends AppCompatActivity {

    private static final String TAG = "EgoNetDeviceFoundAct";
    private static final int REQUEST_BLE_PERMISSIONS = 2001;
    private static final int REQUEST_WIFI_SSID_PERMISSIONS = 2002;
    private static final int SCAN_TIMEOUT_MS = 6000;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int WIFI_TIMEOUT_MS = 10000;
    private static final int IP_TIMEOUT_MS = 10000;
    private static final int IP_POLL_INTERVAL_MS = 2000;
    private static final int IP_MAX_POLLS = 15;

    // ── State machine ────────────────────────────────────────────────
    private enum State {
        IDLE,            // initial / disconnected
        SCANNING,        // BLE scan in progress
        SCANNED,         // scan complete, dropdown populated
        CONNECTING,      // GATT connect in progress
        CONNECTED,       // connected, waiting for Wi-Fi credentials
        CONFIGURING_WIFI,// Wi-Fi config in progress
        WIFI_DONE,       // Wi-Fi config succeeded, ready to query IP
        REQUESTING_IP    // IP query in progress
    }

    private State mState = State.IDLE;

    // ── Views ────────────────────────────────────────────────────────
    private TextView mTvStatus;
    private Spinner mSpinnerDevices;
    private Button mBtnScan;
    private Button mBtnConnect;
    private LinearLayout mSectionWifi;
    private EditText mEtWifiSsid;
    private EditText mEtWifiPassword;
    private Button mBtnConfigWifi;
    private LinearLayout mSectionIp;
    private TextView mTvIpResult;
    private Button mBtnRequestIp;
    private Button mBtnStartStream;
    private Button mBtnDisconnect;
    private TextView mTvLog;
    private ScrollView mSvLog;

    // ── Data ─────────────────────────────────────────────────────────
    private EgoLowBleClient mClient;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final List<EgoLowBleClient.ScanDevice> mDeviceList = new ArrayList<>();
    private DeviceAdapter mAdapter;
    private volatile String mLastSuccessIp = null;

    // ── Lifecycle ────────────────────────────────────────────────────

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ego_net_device_found);
        setTitle("Ego Device Provisioning");

        mTvStatus       = findViewById(R.id.tv_status);
        mSpinnerDevices = findViewById(R.id.spinner_devices);
        mBtnScan        = findViewById(R.id.btn_scan);
        mBtnConnect     = findViewById(R.id.btn_connect);
        mSectionWifi    = findViewById(R.id.section_wifi);
        mEtWifiSsid     = findViewById(R.id.et_wifi_ssid);
        mEtWifiPassword = findViewById(R.id.et_wifi_password);
        mBtnConfigWifi  = findViewById(R.id.btn_config_wifi);
        mSectionIp      = findViewById(R.id.section_ip);
        mTvIpResult     = findViewById(R.id.tv_ip_result);
        mBtnRequestIp   = findViewById(R.id.btn_request_ip);
        mBtnStartStream = findViewById(R.id.btn_start_stream);
        mBtnDisconnect  = findViewById(R.id.btn_disconnect);
        mTvLog          = findViewById(R.id.tv_log);
        mSvLog          = findViewById(R.id.sv_log);

        mAdapter = new DeviceAdapter();
        mSpinnerDevices.setAdapter(mAdapter);

        mBtnScan.setOnClickListener(v -> onScanClicked());
        mBtnConnect.setOnClickListener(v -> onConnectClicked());
        mBtnConfigWifi.setOnClickListener(v -> onConfigWifiClicked());
        mBtnRequestIp.setOnClickListener(v -> onRequestIpClicked());
        mBtnStartStream.setOnClickListener(v -> onStartStreamClicked());
        mBtnDisconnect.setOnClickListener(v -> onDisconnectClicked());
        findViewById(R.id.btn_clear_log).setOnClickListener(v -> mTvLog.setText(""));

        CheckBox cbShowPassword = findViewById(R.id.cb_show_password);
        cbShowPassword.setOnCheckedChangeListener((btn, checked) -> {
            int type = checked
                    ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
            mEtWifiPassword.setInputType(type);
            mEtWifiPassword.setSelection(mEtWifiPassword.getText().length());
        });

        mClient = new EgoLowBleClient();
        if (!mClient.isValid()) {
            setStatus("BLE init failed, check Bluetooth", false);
        }

        applyState(State.IDLE);
        requestBlePermissionsIfNeeded();
    }

    @Override
    protected void onDestroy() {
        try {
            mExecutor.execute(() -> {
                if (mClient != null) {
                    mClient.close();
                    mClient = null;
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {}
        mExecutor.shutdown();
        super.onDestroy();
    }

    // ── State Machine ────────────────────────────────────────────────

    private void applyState(State state) {
        mState = state;
        switch (state) {
            case IDLE:
                setStatus("Ready · Tap Scan to discover Ego devices", true);
                mBtnScan.setText("Scan");
                mBtnScan.setEnabled(true);
                mSpinnerDevices.setEnabled(true);
                mBtnConnect.setVisibility(View.GONE);
                mSectionWifi.setVisibility(View.GONE);
                mSectionIp.setVisibility(View.GONE);
                mBtnDisconnect.setVisibility(View.GONE);
                mBtnStartStream.setVisibility(View.GONE);
                mDeviceList.clear();
                mAdapter.notifyDataSetChanged();
                mEtWifiSsid.setText("");
                mEtWifiPassword.setText("");
                mTvIpResult.setText("--");
                mLastSuccessIp = null;
                break;

            case SCANNING:
                setStatus("Scanning for Ego devices...", true);
                mBtnScan.setText("Scanning...");
                mBtnScan.setEnabled(false);
                mSpinnerDevices.setEnabled(false);
                mBtnConnect.setVisibility(View.GONE);
                break;

            case SCANNED:
                if (mDeviceList.isEmpty()) {
                    setStatus("No Ego devices found · Move closer and rescan", false);
                    mBtnConnect.setVisibility(View.GONE);
                } else {
                    setStatus("Found " + mDeviceList.size() + " Ego device(s) · Select one and tap Connect", true);
                    mBtnConnect.setVisibility(View.VISIBLE);
                    mBtnConnect.setEnabled(true);
                    mBtnConnect.setText("Connect");
                }
                mBtnScan.setText("Rescan");
                mBtnScan.setEnabled(true);
                mSpinnerDevices.setEnabled(true);
                break;

            case CONNECTING:
                setStatus("Connecting...", true);
                mBtnScan.setEnabled(false);
                mSpinnerDevices.setEnabled(false);
                mBtnConnect.setEnabled(false);
                mBtnConnect.setText("Connecting...");
                break;

            case CONNECTED: {
                EgoLowBleClient.ScanDevice d = selectedDevice();
                String name = (d != null && !TextUtils.isEmpty(d.deviceName)) ? d.deviceName : "device";
                setStatus("Connected: " + name + " · Enter Wi-Fi credentials", true);
                mBtnScan.setEnabled(false);
                mSpinnerDevices.setEnabled(false);
                mBtnConnect.setVisibility(View.GONE);
                mSectionWifi.setVisibility(View.VISIBLE);
                mBtnConfigWifi.setEnabled(true);
                mBtnConfigWifi.setText("Configure Wi-Fi");
                mBtnDisconnect.setVisibility(View.VISIBLE);
                mBtnDisconnect.setEnabled(true);
                autoFillCurrentWifiSsid();
                break;
            }

            case CONFIGURING_WIFI:
                setStatus("Configuring Wi-Fi...", true);
                mBtnConfigWifi.setEnabled(false);
                mBtnConfigWifi.setText("Configuring...");
                mBtnDisconnect.setEnabled(false);
                break;

            case WIFI_DONE:
                setStatus("Wi-Fi configured · Tap Query Device IP", true);
                mBtnConfigWifi.setEnabled(true);
                mBtnConfigWifi.setText("Reconfigure Wi-Fi");
                mBtnDisconnect.setEnabled(true);
                mSectionIp.setVisibility(View.VISIBLE);
                mBtnRequestIp.setEnabled(true);
                mBtnRequestIp.setText("Query Device IP");
                break;

            case REQUESTING_IP:
                setStatus("Querying device IP...", true);
                mTvIpResult.setText("--");  // clear any prior result (e.g. Timeout) on retry
                mBtnRequestIp.setEnabled(false);
                mBtnRequestIp.setText("Querying...");
                mBtnStartStream.setVisibility(View.GONE);
                break;
        }
    }

    // ── Button Handlers ──────────────────────────────────────────────

    private void onScanClicked() {
        if (!ensurePermissions()) return;
        mDeviceList.clear();
        mAdapter.notifyDataSetChanged();
        applyState(State.SCANNING);

        submitBleAction("scan", () -> {
            EgoLowBleClient.ScanResult result = mClient.scanDevices(SCAN_TIMEOUT_MS);
            if (!result.isSuccess()) {
                appendLog("Scan failed: " + result.errorMessage);
                runOnUiThread(() -> applyState(State.IDLE));
                return;
            }
            int total = result.devices.size();
            mDeviceList.addAll(result.devices);
            appendLog("Scan done: " + total + " device(s)");
            for (EgoLowBleClient.ScanDevice d : mDeviceList) {
                appendLog("  " + d.deviceName + "  " + d.deviceAddress + "  " + d.rssi + "dBm");
            }
            runOnUiThread(() -> {
                mAdapter.notifyDataSetChanged();
                applyState(State.SCANNED);
            });
        });
    }

    private void onConnectClicked() {
        if (!ensurePermissions()) return;
        EgoLowBleClient.ScanDevice device = selectedDevice();
        if (device == null) {
            Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show();
            return;
        }
        applyState(State.CONNECTING);

        submitBleAction("connect", () -> {
            EgoLowBleClient.OperationResult result =
                    mClient.connectByAddress(device.deviceAddress, CONNECT_TIMEOUT_MS);
            if (result.isSuccess()) {
                int mtu = mClient.getMtu();
                appendLog("Connected: " + device.deviceName
                        + (mtu > 0 ? "  MTU=" + mtu : ""));
                runOnUiThread(() -> applyState(State.CONNECTED));
            } else {
                appendLog("Connect failed: " + result.errorMessage);
                runOnUiThread(() -> applyState(State.SCANNED));
            }
        });
    }

    private void onConfigWifiClicked() {
        String ssid = textOf(mEtWifiSsid).trim();
        String password = textOf(mEtWifiPassword);
        if (TextUtils.isEmpty(ssid)) {
            Toast.makeText(this, "Please enter Wi-Fi SSID", Toast.LENGTH_SHORT).show();
            return;
        }
        applyState(State.CONFIGURING_WIFI);

        submitBleAction("configWifi", () -> {
            EgoLowBleClient.WifiConfigResult result =
                    mClient.configureWifi(ssid, password, WIFI_TIMEOUT_MS);
            if (!result.isSuccess()) {
                appendLog("Wi-Fi config comm failed: " + result.errorMessage);
                runOnUiThread(() -> {
                    setStatus("Wi-Fi config failed · Please retry", false);
                    mBtnConfigWifi.setEnabled(true);
                    mBtnConfigWifi.setText("Retry Wi-Fi Config");
                    mBtnDisconnect.setEnabled(true);
                });
                return;
            }
            if (result.result == 0) {
                appendLog("Wi-Fi configured: " + result.reason);
                runOnUiThread(() -> applyState(State.WIFI_DONE));
            } else {
                appendLog("Device rejected config: " + result.reason);
                runOnUiThread(() -> {
                    setStatus("Device rejected: " + result.reason, false);
                    mBtnConfigWifi.setEnabled(true);
                    mBtnConfigWifi.setText("Retry Wi-Fi Config");
                    mBtnDisconnect.setEnabled(true);
                });
            }
        });
    }

    private void onRequestIpClicked() {
        applyState(State.REQUESTING_IP);

        submitBleAction("requestIp", () -> {
            for (int attempt = 1; attempt <= IP_MAX_POLLS; attempt++) {
                EgoLowBleClient.IpResult result = mClient.requestIp(IP_TIMEOUT_MS);
                final String rawLog = "requestIp[" + attempt + "] status=" + result.status
                        + " result=" + result.result
                        + " ip=" + result.ip
                        + " reason=" + result.reason;
                appendLog(rawLog);
                runOnUiThread(() -> setStatus(rawLog, result.status == EgoLowBleClient.STATUS_OK));

                if (!result.isSuccess()) {
                    appendLog("IP query failed: " + result.errorMessage);
                    runOnUiThread(() -> {
                        setStatus("IP query failed · Please retry", false);
                        mBtnRequestIp.setEnabled(true);
                        mBtnRequestIp.setText("Retry");
                    });
                    return;
                }

                if (result.result == EgoLowBleClient.IP_RESULT_CONFIGURING) {
                    appendLog("Device connecting (" + attempt + "/" + IP_MAX_POLLS + "): " + result.reason);
                    final int a = attempt;
                    runOnUiThread(() ->
                            setStatus("Device connecting... (" + a + "/" + IP_MAX_POLLS + ")", true));
                    if (attempt < IP_MAX_POLLS) {
                        try {
                            Thread.sleep(IP_POLL_INTERVAL_MS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        continue;
                    }
                    // Exceeded max polls
                    runOnUiThread(() -> {
                        mTvIpResult.setText("Timeout");
                        setStatus("Device networking timed out, check Wi-Fi password and reconfigure", false);
                        mBtnRequestIp.setEnabled(true);
                        mBtnRequestIp.setText("Retry");
                    });
                    return;
                }

                // Final state received
                final String ipText;
                final String statusMsg;
                final boolean success = (result.result == EgoLowBleClient.IP_RESULT_SUCCESS);
                switch (result.result) {
                    case EgoLowBleClient.IP_RESULT_SUCCESS:
                        ipText = result.ip;
                        statusMsg = "Success · Device IP: " + result.ip;
                        appendLog("Device IP: " + result.ip);
                        mLastSuccessIp = result.ip;
                        break;
                    case EgoLowBleClient.IP_RESULT_NOT_CONFIGURED:
                        ipText = "Not configured";
                        statusMsg = "Device Wi-Fi not configured";
                        appendLog("Device not configured: " + result.reason);
                        break;
                    case EgoLowBleClient.IP_RESULT_CONFIGURE_FAILED:
                        ipText = "Connect failed";
                        statusMsg = "Device failed to connect to Wi-Fi, please reconfigure";
                        appendLog("Device connect failed: " + result.reason);
                        break;
                    default:
                        ipText = "Unknown(" + result.result + ")";
                        statusMsg = "Unknown state";
                        appendLog("Unknown state (" + result.result + "): " + result.reason);
                        break;
                }
                runOnUiThread(() -> {
                    mTvIpResult.setText(ipText);
                    setStatus(statusMsg, success);
                    mBtnRequestIp.setEnabled(true);
                    mBtnRequestIp.setText(success ? "Refresh IP" : "Retry");
                    mBtnStartStream.setVisibility(success ? View.VISIBLE : View.GONE);
                });
                return;
            }
        });
    }

    private void onDisconnectClicked() {
        submitBleAction("disconnect", () -> {
            mClient.disconnect();
            appendLog("Disconnected");
            runOnUiThread(() -> applyState(State.IDLE));
        });
    }

    private void onStartStreamClicked() {
        String ip = mLastSuccessIp;
        if (TextUtils.isEmpty(ip)) {
            Toast.makeText(this, "Invalid IP address", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, EgoSampleNetActivity.class);
        intent.putExtra(EgoSampleNetActivity.EXTRA_IP, ip);
        startActivity(intent);
    }

    // ── BLE Executor ─────────────────────────────────────────────────

    private interface BleAction {
        void run() throws Exception;
    }

    private void submitBleAction(String label, BleAction action) {
        try {
            mExecutor.execute(() -> {
                try {
                    action.run();
                } catch (Throwable ex) {
                    String msg = label + " exception: "
                            + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
                    Log.w(TAG, label + " exception", ex);
                    runOnUiThread(() -> appendLog(msg));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    // ── Permissions ──────────────────────────────────────────────────

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            };
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasPermissions() {
        for (String p : requiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private boolean ensurePermissions() {
        if (hasPermissions()) return true;
        appendLog("Bluetooth permission required, please grant and retry");
        ActivityCompat.requestPermissions(this, requiredPermissions(), REQUEST_BLE_PERMISSIONS);
        return false;
    }

    private void requestBlePermissionsIfNeeded() {
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, requiredPermissions(), REQUEST_BLE_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_WIFI_SSID_PERMISSIONS) {
            if (hasWifiSsidPermissions()) {
                autoFillCurrentWifiSsid();
            } else {
                appendLog("Location permission denied; enter Wi-Fi SSID manually");
            }
            return;
        }
        if (requestCode != REQUEST_BLE_PERMISSIONS) return;
        boolean granted = grantResults.length > 0;
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) { granted = false; break; }
        }
        appendLog(granted ? "Bluetooth permission granted" : "Bluetooth permission denied, BLE unavailable");
        if (granted) {
            setStatus("Ready · Tap Scan to discover Ego devices", true);
        }
    }

    private boolean hasWifiSsidPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void autoFillCurrentWifiSsid() {
        if (!TextUtils.isEmpty(textOf(mEtWifiSsid))) return;
        if (!hasWifiSsidPermissions()) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQUEST_WIFI_SSID_PERMISSIONS);
            return;
        }
        String ssid = getCurrentWifiSsid();
        if (ssid != null) {
            mEtWifiSsid.setText(ssid);
            mEtWifiSsid.setSelection(ssid.length());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    @Nullable
    @SuppressWarnings("deprecation")
    private String getCurrentWifiSsid() {
        if (!hasWifiSsidPermissions()) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm == null || !lm.isLocationEnabled()) return null;
        }
        WifiManager wm = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wm == null || !wm.isWifiEnabled()) return null;
        WifiInfo info = wm.getConnectionInfo();
        if (info == null) return null;
        String ssid = info.getSSID();
        if (TextUtils.isEmpty(ssid) || "<unknown ssid>".equals(ssid)) return null;
        if (ssid.length() >= 2 && ssid.charAt(0) == '"' && ssid.charAt(ssid.length() - 1) == '"') {
            ssid = ssid.substring(1, ssid.length() - 1);
        }
        return TextUtils.isEmpty(ssid) ? null : ssid;
    }

    @Nullable
    private EgoLowBleClient.ScanDevice selectedDevice() {
        int pos = mSpinnerDevices.getSelectedItemPosition();
        if (pos < 0 || pos >= mDeviceList.size()) return null;
        return mDeviceList.get(pos);
    }

    private void setStatus(String text, boolean ok) {
        mTvStatus.setText(text);
        mTvStatus.setTextColor(ok ? 0xFF66BB6A : 0xFFEF5350);
        mTvStatus.setBackgroundColor(ok ? 0xFF1A2E1A : 0xFF2E1A1A);
    }

    private void appendLog(String message) {
        Log.i(TAG, message);
        final String line = "[" + timeStamp() + "] " + message;
        runOnUiThread(() -> {
            mTvLog.append(mTvLog.length() == 0 ? line : "\n" + line);
            mSvLog.post(() -> mSvLog.fullScroll(View.FOCUS_DOWN));
        });
    }

    private static String timeStamp() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    private static String textOf(EditText et) {
        CharSequence s = et.getText();
        return s == null ? "" : s.toString();
    }

    // ── Spinner Adapter ──────────────────────────────────────────────

    private class DeviceAdapter extends ArrayAdapter<EgoLowBleClient.ScanDevice> {

        DeviceAdapter() {
            super(EgoNetDeviceFoundActivity.this,
                    android.R.layout.simple_spinner_item, mDeviceList);
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            TextView tv = makeTextView(convertView);
            if (mDeviceList.isEmpty()) {
                tv.setText("No Ego devices, tap Scan first");
                tv.setTextColor(0xFF666666);
            } else {
                tv.setText(mDeviceList.get(position).deviceName);
                tv.setTextColor(0xFFFFFFFF);
            }
            tv.setBackgroundColor(0xFF1E1E1E);
            tv.setSingleLine(true);
            tv.setPadding(24, 0, 24, 0);
            return tv;
        }

        @Override
        public View getDropDownView(int position, @Nullable View convertView,
                                   @NonNull ViewGroup parent) {
            TextView tv = makeTextView(convertView);
            if (!mDeviceList.isEmpty()) {
                tv.setText(labelOf(mDeviceList.get(position)));
                tv.setTextColor(0xFFFFFFFF);
            }
            tv.setBackgroundColor(0xFF1E1E1E);
            tv.setPadding(32, 24, 32, 24);
            return tv;
        }

        @Override
        public int getCount() {
            // Keep 1 placeholder when empty; getView guards against empty list access
            return Math.max(mDeviceList.size(), 1);
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        private TextView makeTextView(@Nullable View convertView) {
            if (convertView instanceof TextView) return (TextView) convertView;
            TextView tv = new TextView(getContext());
            tv.setTextSize(14f);
            return tv;
        }

        private String labelOf(EgoLowBleClient.ScanDevice d) {
            return d.deviceName + "   " + d.deviceAddress + "  (" + d.rssi + "dBm)";
        }
    }
}
