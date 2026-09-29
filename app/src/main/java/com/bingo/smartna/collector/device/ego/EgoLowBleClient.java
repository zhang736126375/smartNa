package com.bingo.smartna.collector.device.ego;

import com.ego.egolowble.EgoLowBleNative;
import com.ego.egolowble.EgoLowBleScanDevice;
import com.ego.egolowble.EgoLowBleWifiConfigResponse;
import com.ego.egolowble.EgoLowBleIpResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * Java wrapper around the EgoLowBle AAR ({@link EgoLowBleNative}).
 *
 * <p>The native library exposes a C-style BLE API (provided by the official
 * EgoLowBle-0.1.0.aar, which already bundles libEgoLowBle.so / libEgoLowBleJni.so and
 * handles JVM initialization internally). This class wraps it with a small,
 * allocation-friendly Java surface so Activities never touch raw status codes or
 * JNI handles directly.</p>
 *
 * <p>All BLE operations are synchronous from the caller's perspective and MUST
 * be invoked from a background thread — the underlying GATT calls block.</p>
 */
public class EgoLowBleClient {

    // Status codes mirrored from egolowble.h
    public static final int STATUS_OK = 0;
    public static final int STATUS_INVALID_ARGUMENT = 1;
    public static final int STATUS_ADAPTER_NOT_FOUND = 2;
    public static final int STATUS_DEVICE_NOT_FOUND = 3;
    public static final int STATUS_NOT_CONNECTED = 4;
    public static final int STATUS_BUFFER_TOO_SMALL = 5;
    public static final int STATUS_BLUETOOTH_UNAVAILABLE = 6;
    public static final int STATUS_OPERATION_FAILED = 7;

    // IP query device-state result codes (EgoLowBle_RequestIp -> EgoLowBleIpResult)
    public static final int IP_RESULT_SUCCESS = 0;
    public static final int IP_RESULT_NOT_CONFIGURED = 1;
    public static final int IP_RESULT_CONFIGURING = 2;
    public static final int IP_RESULT_CONFIGURE_FAILED = 3;

    private static final int DEFAULT_SCAN_TIMEOUT_MS = 5000;
    private static final int DEFAULT_BUSINESS_TIMEOUT_MS = 10000;

    private long mHandle = 0;

    public static class ScanDevice {
        public final String deviceName;
        public final String deviceAddress;
        public final int rssi;

        public ScanDevice(String deviceName, String deviceAddress, int rssi) {
            this.deviceName = deviceName;
            this.deviceAddress = deviceAddress;
            this.rssi = rssi;
        }

        @Override
        public String toString() {
            return "ScanDevice{name=" + deviceName + ", addr=" + deviceAddress
                    + ", rssi=" + rssi + "}";
        }
    }

    public static class ScanResult {
        public final int status;
        public final List<ScanDevice> devices;
        public final String errorMessage;

        public ScanResult(int status, List<ScanDevice> devices, String errorMessage) {
            this.status = status;
            this.devices = devices != null ? devices : new ArrayList<>();
            this.errorMessage = errorMessage != null ? errorMessage : "";
        }

        public boolean isSuccess() {
            return status == STATUS_OK;
        }
    }

    public static class OperationResult {
        public final int status;
        public final String errorMessage;

        public OperationResult(int status, String errorMessage) {
            this.status = status;
            this.errorMessage = errorMessage != null ? errorMessage : "";
        }

        public boolean isSuccess() {
            return status == STATUS_OK;
        }
    }

    public static class WifiConfigResult {
        public final int status;
        public final int result;
        public final String reason;
        public final String errorMessage;

        public WifiConfigResult(int status, int result, String reason, String errorMessage) {
            this.status = status;
            this.result = result;
            this.reason = reason != null ? reason : "";
            this.errorMessage = errorMessage != null ? errorMessage : "";
        }

        public boolean isSuccess() {
            return status == STATUS_OK;
        }
    }

    public static class IpResult {
        public final int status;
        public final int result;
        public final String ip;
        public final String reason;
        public final String errorMessage;

        public IpResult(int status, int result, String ip, String reason, String errorMessage) {
            this.status = status;
            this.result = result;
            this.ip = ip != null ? ip : "";
            this.reason = reason != null ? reason : "";
            this.errorMessage = errorMessage != null ? errorMessage : "";
        }

        public boolean isSuccess() {
            return status == STATUS_OK;
        }
    }

    public EgoLowBleClient() {
        mHandle = EgoLowBleNative.INSTANCE.nativeCreate();
    }

    public boolean isValid() {
        return mHandle != 0;
    }

    public ScanResult scanDevices(int timeoutMs) {
        if (!isValid()) {
            return new ScanResult(STATUS_OPERATION_FAILED, null, "Invalid BLE handle");
        }
        int timeout = timeoutMs > 0 ? timeoutMs : DEFAULT_SCAN_TIMEOUT_MS;
        EgoLowBleScanDevice[] devices = EgoLowBleNative.INSTANCE.nativeScanDevices(mHandle, timeout);
        if (devices == null) {
            return new ScanResult(STATUS_OPERATION_FAILED, null, lastErrorOrFallback(STATUS_OPERATION_FAILED));
        }
        List<ScanDevice> list = new ArrayList<>(devices.length);
        for (EgoLowBleScanDevice d : devices) {
            list.add(new ScanDevice(d.getDeviceName(), d.getDeviceAddress(), d.getRssi()));
        }
        return new ScanResult(STATUS_OK, list, "");
    }

    public OperationResult connectByName(String deviceName, int timeoutMs) {
        if (!isValid()) {
            return new OperationResult(STATUS_OPERATION_FAILED, "Invalid BLE handle");
        }
        int timeout = timeoutMs > 0 ? timeoutMs : DEFAULT_SCAN_TIMEOUT_MS;
        int status = EgoLowBleNative.INSTANCE.nativeConnectByName(mHandle, deviceName, timeout);
        return new OperationResult(status, status == STATUS_OK ? "" : lastErrorOrFallback(status));
    }

    public OperationResult connectByAddress(String deviceAddress, int timeoutMs) {
        if (!isValid()) {
            return new OperationResult(STATUS_OPERATION_FAILED, "Invalid BLE handle");
        }
        int timeout = timeoutMs > 0 ? timeoutMs : DEFAULT_SCAN_TIMEOUT_MS;
        int status = EgoLowBleNative.INSTANCE.nativeConnectByAddress(mHandle, deviceAddress, timeout);
        return new OperationResult(status, status == STATUS_OK ? "" : lastErrorOrFallback(status));
    }

    public OperationResult disconnect() {
        if (!isValid()) {
            return new OperationResult(STATUS_OPERATION_FAILED, "Invalid BLE handle");
        }
        int status = EgoLowBleNative.INSTANCE.nativeDisconnect(mHandle);
        return new OperationResult(status, status == STATUS_OK ? "" : lastErrorOrFallback(status));
    }

    public int getMtu() {
        if (!isValid()) {
            return -1;
        }
        return EgoLowBleNative.INSTANCE.nativeGetMtu(mHandle);
    }

    public WifiConfigResult configureWifi(String ssid, String password, int timeoutMs) {
        if (!isValid()) {
            return new WifiConfigResult(STATUS_OPERATION_FAILED, -1, "", "Invalid BLE handle");
        }
        int timeout = timeoutMs > 0 ? timeoutMs : DEFAULT_BUSINESS_TIMEOUT_MS;
        EgoLowBleWifiConfigResponse resp =
                EgoLowBleNative.INSTANCE.nativeConfigureWifi(mHandle, ssid, password, timeout);
        if (resp == null) {
            return new WifiConfigResult(STATUS_OPERATION_FAILED, -1, "",
                    lastErrorOrFallback(STATUS_OPERATION_FAILED));
        }
        return new WifiConfigResult(STATUS_OK, resp.getResult(), resp.getReason(), "");
    }

    public IpResult requestIp(int timeoutMs) {
        if (!isValid()) {
            return new IpResult(STATUS_OPERATION_FAILED, -1, "", "", "Invalid BLE handle");
        }
        int timeout = timeoutMs > 0 ? timeoutMs : DEFAULT_BUSINESS_TIMEOUT_MS;
        EgoLowBleIpResponse resp = EgoLowBleNative.INSTANCE.nativeRequestIp(mHandle, timeout);
        if (resp == null) {
            return new IpResult(STATUS_OPERATION_FAILED, -1, "", "",
                    lastErrorOrFallback(STATUS_OPERATION_FAILED));
        }
        return new IpResult(STATUS_OK, resp.getResult(), resp.getIp(), resp.getReason(), "");
    }

    public void close() {
        if (mHandle != 0) {
            EgoLowBleNative.INSTANCE.nativeDestroy(mHandle);
            mHandle = 0;
        }
    }

    private String lastErrorOrFallback(int status) {
        if (isValid()) {
            String raw = EgoLowBleNative.INSTANCE.nativeGetLastError(mHandle);
            if (raw != null && !raw.trim().isEmpty()) {
                return raw.trim();
            }
        }
        return "BLE operation failed with status=" + statusName(status);
    }

    public static String statusName(int status) {
        switch (status) {
            case STATUS_OK:
                return "OK";
            case STATUS_INVALID_ARGUMENT:
                return "INVALID_ARGUMENT";
            case STATUS_ADAPTER_NOT_FOUND:
                return "ADAPTER_NOT_FOUND";
            case STATUS_DEVICE_NOT_FOUND:
                return "DEVICE_NOT_FOUND";
            case STATUS_NOT_CONNECTED:
                return "NOT_CONNECTED";
            case STATUS_BUFFER_TOO_SMALL:
                return "BUFFER_TOO_SMALL";
            case STATUS_BLUETOOTH_UNAVAILABLE:
                return "BLUETOOTH_UNAVAILABLE";
            case STATUS_OPERATION_FAILED:
                return "OPERATION_FAILED";
            default:
                return "UNKNOWN(" + status + ")";
        }
    }
}
