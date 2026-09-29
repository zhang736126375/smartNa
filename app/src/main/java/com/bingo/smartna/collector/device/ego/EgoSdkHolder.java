package com.bingo.smartna.collector.device.ego;

import android.content.Context;
import android.content.res.AssetManager;
import android.text.TextUtils;
import android.util.Log;

import com.bingo.smartna.collector.device.OrbbecNativeLoader;
import com.orbbec.obsensor.DeviceChangedCallback;
import com.orbbec.obsensor.DeviceList;
import com.orbbec.obsensor.OBContext;
import com.orbbec.obsensor.OBException;
import com.orbbec.obsensor.types.LogSeverity;
import com.orbbec.obsensor.types.UvcBackendType;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Process-wide Orbbec {@link OBContext}. Only one instance is allowed. */
final class EgoSdkHolder {
    private static final String TAG = "EgoSdkHolder";
    private static final String XML_CONFIG_FILE_NAME = "OrbbecSDKConfig.xml";

    private static OBContext sContext;
    private static int sRefCount;

    private EgoSdkHolder() {
    }

    static synchronized OBContext acquire(Context appContext, DeviceChangedCallback callback)
            throws OBException {
        if (sContext != null) {
            sRefCount++;
            return sContext;
        }
        OrbbecNativeLoader.INSTANCE.ensureLoaded();
        OBContext.setLoggerSeverity(LogSeverity.DEBUG);
        OBContext.setLoggerToConsole(LogSeverity.DEBUG);
        DeviceChangedCallback cb = callback != null ? callback : emptyCallback();
        String configPath = copyXmlConfig(appContext.getApplicationContext());
        if (!TextUtils.isEmpty(configPath)) {
            sContext = new OBContext(appContext.getApplicationContext(), configPath, cb);
        } else {
            sContext = new OBContext(appContext.getApplicationContext(), cb);
        }
        sContext.setUvcBackendType(UvcBackendType.OB_UVC_BACKEND_TYPE_LIBUVC);
        sRefCount = 1;
        return sContext;
    }

    static synchronized void release() {
        if (sContext == null) {
            return;
        }
        sRefCount--;
        if (sRefCount > 0) {
            return;
        }
        try {
            sContext.close();
        } catch (Exception e) {
            Log.w(TAG, "close OBContext: " + e.getMessage());
        }
        sContext = null;
        sRefCount = 0;
    }

    private static DeviceChangedCallback emptyCallback() {
        return new DeviceChangedCallback() {
            @Override
            public void onDeviceAttach(DeviceList deviceList) {
                if (deviceList != null) {
                    deviceList.close();
                }
            }

            @Override
            public void onDeviceDetach(DeviceList deviceList) {
                if (deviceList != null) {
                    deviceList.close();
                }
            }
        };
    }

    private static String copyXmlConfig(Context context) {
        File dir = context.getExternalFilesDir("Orbbec");
        if (dir == null) {
            dir = new File(context.getFilesDir(), "Orbbec");
        }
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        File file = new File(dir, XML_CONFIG_FILE_NAME);
        if (file.exists()) {
            return file.getAbsolutePath();
        }
        try {
            AssetManager manager = context.getAssets();
            InputStream is = manager.open(XML_CONFIG_FILE_NAME);
            OutputStream os = new FileOutputStream(file);
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) > 0) {
                os.write(buffer, 0, length);
            }
            os.flush();
            os.close();
            is.close();
            return file.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "copyXmlConfig: " + e.getMessage());
            return null;
        }
    }
}
