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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.bingo.smartna.R;
import com.bingo.smartna.collector.data.Prefs;
import com.bingo.smartna.collector.device.DevicePageActivity;
import com.bingo.smartna.collector.device.ScanDeviceActivity;

import org.json.JSONObject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ego 配网向导：扫码按名称连接，或 BLE 扫描按地址连接，再配 Wi-Fi 并查询 IP。
 */
public class EgoNetDeviceFoundActivity extends AppCompatActivity {

    private static final String TAG = "EgoNetDeviceFoundAct";
    private static final int REQUEST_BLE_PERMISSIONS = 2001;
    private static final int REQUEST_WIFI_SSID_PERMISSIONS = 2002;
    private static final int REQUEST_CAMERA = 2003;
    private static final int SCAN_TIMEOUT_MS = 6000;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int CONNECT_BY_NAME_TIMEOUT_MS = 14000;
    private static final int WIFI_TIMEOUT_MS = 10000;
    private static final int IP_TIMEOUT_MS = 10000;
    private static final int IP_POLL_INTERVAL_MS = 2000;
    private static final int IP_MAX_POLLS = 15;

    private enum State {
        IDLE,
        SCANNING,
        SCANNED,
        CONNECTING,
        CONNECTED,
        CONFIGURING_WIFI,
        REQUESTING_IP,
        IP_READY
    }

    private State mState = State.IDLE;

    private TextView mTvStatus;
    private Spinner mSpinnerDevices;
    private TextView mBtnScanQr;
    private TextView mBtnScan;
    private TextView mBtnConnect;
    private View mSectionWifi;
    private EditText mEtWifiSsid;
    private EditText mEtWifiPassword;
    private TextView mBtnConfigWifi;
    private View mSectionIp;
    private TextView mTvIpResult;
    private TextView mBtnRequestIp;
    private TextView mBtnStartStream;
    private TextView mBtnDisconnect;

    private EgoLowBleClient mClient;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final List<EgoLowBleClient.ScanDevice> mDeviceList = new ArrayList<>();
    private DeviceAdapter mAdapter;
    private volatile String mLastSuccessIp = null;

    private final ActivityResultLauncher<Intent> mQrLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    this::onQrScanResult);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ego_net_device_found);

        mTvStatus = findViewById(R.id.tv_status);
        mSpinnerDevices = findViewById(R.id.spinner_devices);
        mBtnScanQr = findViewById(R.id.btn_scan_qr);
        mBtnScan = findViewById(R.id.btn_scan);
        mBtnConnect = findViewById(R.id.btn_connect);
        mSectionWifi = findViewById(R.id.section_wifi);
        mEtWifiSsid = findViewById(R.id.et_wifi_ssid);
        mEtWifiPassword = findViewById(R.id.et_wifi_password);
        mBtnConfigWifi = findViewById(R.id.btn_config_wifi);
        mSectionIp = findViewById(R.id.section_ip);
        mTvIpResult = findViewById(R.id.tv_ip_result);
        mBtnRequestIp = findViewById(R.id.btn_request_ip);
        mBtnStartStream = findViewById(R.id.btn_start_stream);
        mBtnDisconnect = findViewById(R.id.btn_disconnect);

        mAdapter = new DeviceAdapter();
        mSpinnerDevices.setAdapter(mAdapter);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        mBtnScanQr.setOnClickListener(v -> onScanQrClicked());
        mBtnScan.setOnClickListener(v -> onScanClicked());
        mBtnConnect.setOnClickListener(v -> onConnectClicked());
        mBtnConfigWifi.setOnClickListener(v -> onConfigWifiClicked());
        mBtnRequestIp.setOnClickListener(v -> onRequestIpClicked());
        mBtnStartStream.setOnClickListener(v -> onStartStreamClicked());
        mBtnDisconnect.setOnClickListener(v -> onDisconnectClicked());

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
            setStatus(getString(R.string.ego_found_status_ble_fail), false);
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
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
        mExecutor.shutdown();
        super.onDestroy();
    }

    private void applyState(State state) {
        mState = state;
        switch (state) {
            case IDLE:
                setStatus(getString(R.string.ego_found_status_idle), true);
                setDiscoveryEnabled(true);
                mBtnScan.setText(R.string.ego_found_scan_ble);
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
                mTvIpResult.setText(R.string.ego_found_ip_placeholder);
                mLastSuccessIp = null;
                break;

            case SCANNING:
                setStatus(getString(R.string.ego_found_status_scanning), true);
                setDiscoveryEnabled(false);
                mBtnScan.setText(R.string.ego_found_scanning);
                mSpinnerDevices.setEnabled(false);
                mBtnConnect.setVisibility(View.GONE);
                break;

            case SCANNED:
                if (mDeviceList.isEmpty()) {
                    setStatus(getString(R.string.ego_found_status_empty), false);
                    mBtnConnect.setVisibility(View.GONE);
                } else {
                    setStatus(getString(R.string.ego_found_status_found, mDeviceList.size()), true);
                    mBtnConnect.setVisibility(View.VISIBLE);
                    setPrimaryEnabled(mBtnConnect, true);
                    mBtnConnect.setText(R.string.ego_found_connect);
                }
                setDiscoveryEnabled(true);
                mBtnScan.setText(R.string.ego_found_scan_ble);
                mSpinnerDevices.setEnabled(true);
                break;

            case CONNECTING:
                setStatus(getString(R.string.ego_found_status_connecting), true);
                setDiscoveryEnabled(false);
                mSpinnerDevices.setEnabled(false);
                setPrimaryEnabled(mBtnConnect, false);
                mBtnConnect.setText(R.string.ego_found_connecting);
                break;

            case CONNECTED:
                setStatus(getString(R.string.ego_found_status_need_wifi), false);
                setDiscoveryEnabled(false);
                mSpinnerDevices.setEnabled(false);
                mBtnConnect.setVisibility(View.GONE);
                mSectionWifi.setVisibility(View.VISIBLE);
                setPrimaryEnabled(mBtnConfigWifi, true);
                mBtnConfigWifi.setText(R.string.ego_found_config_wifi);
                mBtnDisconnect.setVisibility(View.VISIBLE);
                mBtnDisconnect.setEnabled(true);
                mSectionIp.setVisibility(View.GONE);
                mBtnStartStream.setVisibility(View.GONE);
                autoFillCurrentWifiSsid();
                break;

            case CONFIGURING_WIFI:
                setStatus(getString(R.string.ego_found_status_wifi), true);
                setPrimaryEnabled(mBtnConfigWifi, false);
                mBtnConfigWifi.setText(R.string.ego_found_configuring);
                mBtnDisconnect.setEnabled(false);
                break;

            case REQUESTING_IP:
                setStatus(getString(R.string.ego_found_status_ip), true);
                setDiscoveryEnabled(false);
                mSpinnerDevices.setEnabled(false);
                mBtnConnect.setVisibility(View.GONE);
                mBtnDisconnect.setVisibility(View.VISIBLE);
                mBtnDisconnect.setEnabled(false);
                mSectionIp.setVisibility(View.VISIBLE);
                mTvIpResult.setText(R.string.ego_found_ip_placeholder);
                setPrimaryEnabled(mBtnRequestIp, false);
                mBtnRequestIp.setText(R.string.ego_found_querying);
                mBtnStartStream.setVisibility(View.GONE);
                break;

            case IP_READY:
                setDiscoveryEnabled(false);
                mSpinnerDevices.setEnabled(false);
                mBtnConnect.setVisibility(View.GONE);
                mSectionWifi.setVisibility(View.GONE);
                mSectionIp.setVisibility(View.VISIBLE);
                mBtnDisconnect.setVisibility(View.VISIBLE);
                mBtnDisconnect.setEnabled(true);
                setPrimaryEnabled(mBtnRequestIp, true);
                mBtnRequestIp.setText(R.string.ego_found_refresh_ip);
                mBtnStartStream.setVisibility(View.VISIBLE);
                break;
        }
    }

    private void onScanQrClicked() {
        if (!ensurePermissions()) return;
        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
            return;
        }
        launchQrScanner();
    }

    private void launchQrScanner() {
        Intent intent = new Intent(this, ScanDeviceActivity.class);
        intent.putExtra(ScanDeviceActivity.EXTRA_RETURN_QR, true);
        mQrLauncher.launch(intent);
    }

    private void onQrScanResult(ActivityResult result) {
        if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
        String raw = result.getData().getStringExtra(ScanDeviceActivity.EXTRA_QR_VALUE);
        String name = parseDeviceName(raw);
        if (TextUtils.isEmpty(name)) {
            appendLog("QR has no device name: " + raw);
            Toast.makeText(this, R.string.ego_found_qr_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        appendLog("QR device name: " + name);
        mDeviceList.clear();
        mDeviceList.add(new EgoLowBleClient.ScanDevice(name, "", 0));
        mAdapter.notifyDataSetChanged();
        mSpinnerDevices.setSelection(0);
        applyState(State.SCANNED);
        onConnectClicked();
    }

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
            appendLog("BLE scan done: " + total + " device(s)");
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
            Toast.makeText(this, R.string.ego_found_select_device, Toast.LENGTH_SHORT).show();
            return;
        }
        applyState(State.CONNECTING);

        final boolean byName = TextUtils.isEmpty(device.deviceAddress);
        submitBleAction("connect", () -> {
            EgoLowBleClient.OperationResult result = byName
                    ? mClient.connectByName(device.deviceName, CONNECT_BY_NAME_TIMEOUT_MS)
                    : mClient.connectByAddress(device.deviceAddress, CONNECT_TIMEOUT_MS);
            if (result.isSuccess()) {
                int mtu = mClient.getMtu();
                appendLog((byName ? "Connected by name: " : "Connected: ") + device.deviceName
                        + (mtu > 0 ? "  MTU=" + mtu : ""));
                final String connectedName = !TextUtils.isEmpty(device.deviceName)
                        ? device.deviceName : "Ego";
                runOnUiThread(() -> {
                    applyState(State.REQUESTING_IP);
                    setStatus(getString(R.string.ego_found_status_connected, connectedName), true);
                });
                queryDeviceIp();
            } else {
                appendLog((byName ? "ConnectByName failed: " : "Connect failed: ")
                        + result.errorMessage);
                runOnUiThread(() -> applyState(State.SCANNED));
            }
        });
    }

    private void onConfigWifiClicked() {
        String ssid = textOf(mEtWifiSsid).trim();
        String password = textOf(mEtWifiPassword);
        if (TextUtils.isEmpty(ssid)) {
            Toast.makeText(this, R.string.ego_found_ssid_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        applyState(State.CONFIGURING_WIFI);

        submitBleAction("configWifi", () -> {
            EgoLowBleClient.WifiConfigResult result =
                    mClient.configureWifi(ssid, password, WIFI_TIMEOUT_MS);
            if (!result.isSuccess()) {
                appendLog("Wi-Fi config comm failed: " + result.errorMessage);
                runOnUiThread(() -> {
                    setStatus(getString(R.string.ego_found_status_wifi_fail), false);
                    setPrimaryEnabled(mBtnConfigWifi, true);
                    mBtnConfigWifi.setText(R.string.ego_found_retry_wifi);
                    mBtnDisconnect.setEnabled(true);
                });
                return;
            }
            if (result.result == 0) {
                appendLog("Wi-Fi configured: " + result.reason);
                runOnUiThread(() -> applyState(State.REQUESTING_IP));
                queryDeviceIp();
            } else {
                appendLog("Device rejected config: " + result.reason);
                runOnUiThread(() -> {
                    setStatus(getString(R.string.ego_found_status_wifi_reject, result.reason), false);
                    setPrimaryEnabled(mBtnConfigWifi, true);
                    mBtnConfigWifi.setText(R.string.ego_found_retry_wifi);
                    mBtnDisconnect.setEnabled(true);
                });
            }
        });
    }

    private void onRequestIpClicked() {
        applyState(State.REQUESTING_IP);
        submitBleAction("requestIp", this::queryDeviceIp);
    }

    /**
     * 查询设备当前 IP。已配网则直接可用；未配置或失败再回到 Wi-Fi 表单。
     * 必须在 BLE 工作线程调用。
     */
    private void queryDeviceIp() throws Exception {
        for (int attempt = 1; attempt <= IP_MAX_POLLS; attempt++) {
            EgoLowBleClient.IpResult result = mClient.requestIp(IP_TIMEOUT_MS);
            appendLog("requestIp[" + attempt + "] status=" + result.status
                    + " result=" + result.result
                    + " ip=" + result.ip
                    + " reason=" + result.reason);

            if (!result.isSuccess()) {
                appendLog("IP query failed: " + result.errorMessage);
                runOnUiThread(() -> applyNeedWifi(getString(R.string.ego_found_status_ip_fail)));
                return;
            }

            if (result.result == EgoLowBleClient.IP_RESULT_CONFIGURING) {
                appendLog("Device connecting (" + attempt + "/" + IP_MAX_POLLS + "): " + result.reason);
                final int a = attempt;
                runOnUiThread(() ->
                        setStatus(getString(R.string.ego_found_status_ip_wait, a, IP_MAX_POLLS), true));
                if (attempt < IP_MAX_POLLS) {
                    try {
                        Thread.sleep(IP_POLL_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    continue;
                }
                runOnUiThread(() -> applyNeedWifi(getString(R.string.ego_found_status_ip_timeout)));
                return;
            }

            if (result.result == EgoLowBleClient.IP_RESULT_SUCCESS
                    && !TextUtils.isEmpty(result.ip)) {
                appendLog("Device IP: " + result.ip);
                final String ip = result.ip;
                mLastSuccessIp = ip;
                runOnUiThread(() -> persistAndOpenMyDevice(ip));
                return;
            }

            final String statusMsg;
            switch (result.result) {
                case EgoLowBleClient.IP_RESULT_NOT_CONFIGURED:
                    statusMsg = getString(R.string.ego_found_status_ip_unconfigured);
                    appendLog("Device not configured: " + result.reason);
                    break;
                case EgoLowBleClient.IP_RESULT_CONFIGURE_FAILED:
                    statusMsg = getString(R.string.ego_found_status_ip_wifi_fail);
                    appendLog("Device connect failed: " + result.reason);
                    break;
                default:
                    statusMsg = getString(R.string.ego_found_status_ip_unknown);
                    appendLog("Unknown state (" + result.result + "): " + result.reason);
                    break;
            }
            runOnUiThread(() -> applyNeedWifi(statusMsg));
            return;
        }
    }

    private void applyNeedWifi(String status) {
        applyState(State.CONNECTED);
        if (!TextUtils.isEmpty(status)) {
            setStatus(status, false);
        }
        mSectionIp.setVisibility(View.VISIBLE);
        setPrimaryEnabled(mBtnRequestIp, true);
        mBtnRequestIp.setText(R.string.ego_found_retry);
    }

    private void persistAndOpenMyDevice(String ip) {
        EgoLowBleClient.ScanDevice device = selectedDevice();
        String name = device != null ? device.deviceName : null;
        String ble = device != null ? device.deviceAddress : null;
        new Prefs(this).saveConnectedNetDevice(ip, EgoSampleNetActivity.DEFAULT_NET_PORT, name, ble);
        appendLog("Saved device " + name + " " + ip + ":" + EgoSampleNetActivity.DEFAULT_NET_PORT);
        setResult(RESULT_OK);
        DevicePageActivity.start(this);
        finish();
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
            Toast.makeText(this, R.string.ego_found_ip_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        EgoSampleNetActivity.start(this, ip, EgoSampleNetActivity.DEFAULT_NET_PORT);
        finish();
    }

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
                    appendLog(msg);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

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
        if (requestCode == REQUEST_CAMERA) {
            if (hasCameraPermission()) {
                launchQrScanner();
            } else {
                appendLog("Camera permission denied");
                Toast.makeText(this, R.string.device_camera_denied, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (requestCode != REQUEST_BLE_PERMISSIONS) return;
        boolean granted = grantResults.length > 0;
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                granted = false;
                break;
            }
        }
        appendLog(granted ? "Bluetooth permission granted" : "Bluetooth permission denied, BLE unavailable");
        if (granted) {
            setStatus(getString(R.string.ego_found_status_idle), true);
        }
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
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
        mTvStatus.setTextColor(ContextCompat.getColor(this,
                ok ? R.color.money_green_deep : R.color.error));
        mTvStatus.setBackgroundResource(R.drawable.bg_status_banner);
    }

    private void setDiscoveryEnabled(boolean enabled) {
        setPrimaryEnabled(mBtnScanQr, enabled);
        mBtnScan.setEnabled(enabled);
        mBtnScan.setAlpha(enabled ? 1f : 0.45f);
    }

    private void setPrimaryEnabled(TextView btn, boolean enabled) {
        btn.setEnabled(enabled);
        btn.setBackgroundResource(enabled ? R.drawable.bg_btn_primary : R.drawable.bg_btn_disabled);
        btn.setTextColor(ContextCompat.getColor(this,
                enabled ? R.color.white : R.color.btn_disabled_text));
    }

    private void appendLog(String message) {
        Log.i(TAG, message);
    }

    private static String textOf(EditText et) {
        CharSequence s = et.getText();
        return s == null ? "" : s.toString();
    }

    @Nullable
    static String parseDeviceName(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) return null;
        if (value.startsWith("{")) {
            try {
                JSONObject obj = new JSONObject(value);
                String[] keys = {"deviceName", "device_name", "name", "sn", "serial", "devName"};
                for (String key : keys) {
                    String found = obj.optString(key, "").trim();
                    if (!found.isEmpty()) return found;
                }
            } catch (Exception ignored) {
            }
        }
        int q = value.indexOf('?');
        String query = q >= 0 ? value.substring(q + 1) : value;
        if (query.contains("=")) {
            String[] pairs = query.split("[&;]");
            for (String pair : pairs) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String key = pair.substring(0, eq).trim();
                String found = pair.substring(eq + 1).trim();
                if (found.isEmpty()) continue;
                try {
                    found = URLDecoder.decode(found, StandardCharsets.UTF_8.name());
                } catch (Exception ignored) {
                }
                if (key.equalsIgnoreCase("name")
                        || key.equalsIgnoreCase("deviceName")
                        || key.equalsIgnoreCase("device_name")
                        || key.equalsIgnoreCase("sn")) {
                    return found;
                }
            }
        }
        int newline = value.indexOf('\n');
        if (newline > 0) {
            value = value.substring(0, newline).trim();
        }
        return value.isEmpty() ? null : value;
    }

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
                tv.setText(R.string.ego_found_spinner_empty);
                tv.setTextColor(ContextCompat.getColor(getContext(), R.color.text_tertiary));
            } else {
                tv.setText(mDeviceList.get(position).deviceName);
                tv.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
            }
            tv.setBackgroundColor(0x00000000);
            tv.setSingleLine(true);
            tv.setGravity(Gravity.CENTER_VERTICAL);
            tv.setPadding(dp(12), 0, dp(12), 0);
            return tv;
        }

        @Override
        public View getDropDownView(int position, @Nullable View convertView,
                                   @NonNull ViewGroup parent) {
            TextView tv = makeTextView(convertView);
            if (!mDeviceList.isEmpty()) {
                tv.setText(labelOf(mDeviceList.get(position)));
                tv.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
            }
            tv.setBackgroundColor(ContextCompat.getColor(getContext(), R.color.surface_card));
            tv.setPadding(dp(16), dp(12), dp(16), dp(12));
            return tv;
        }

        @Override
        public int getCount() {
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
            if (TextUtils.isEmpty(d.deviceAddress)) {
                return d.deviceName + "   (" + getString(R.string.ego_found_qr_tag) + ")";
            }
            return d.deviceName + "   " + d.deviceAddress + "  (" + d.rssi + "dBm)";
        }

        private int dp(int value) {
            return Math.round(value * getResources().getDisplayMetrics().density);
        }
    }
}
