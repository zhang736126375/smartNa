package com.bingo.smartna.collector.device

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bingo.smartna.R
import com.bingo.smartna.base.ui.BaseActivity
import com.bingo.smartna.base.ui.BaseViewModel
import com.bingo.smartna.databinding.ActivityEgoUsbDemoBinding
import com.blankj.utilcode.util.ClickUtils
import com.orbbec.obsensor.Config
import com.orbbec.obsensor.Device
import com.orbbec.obsensor.DeviceChangedCallback
import com.orbbec.obsensor.DeviceList
import com.orbbec.obsensor.Frame
import com.orbbec.obsensor.OBContext
import com.orbbec.obsensor.Pipeline
import com.orbbec.obsensor.types.FrameType
import com.orbbec.obsensor.types.SensorType

class EgoUsbDemoActivity : BaseActivity<ActivityEgoUsbDemoBinding, BaseViewModel>() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var obContext: OBContext? = null
    private var device: Device? = null
    private var pipeline: Pipeline? = null
    @Volatile private var running = false
    private var frameThread: Thread? = null

    private val deviceCallback = object : DeviceChangedCallback {
        override fun onDeviceAttach(deviceList: DeviceList) {
            try {
                synchronized(lock) {
                    if (device != null) return
                    if (deviceList.deviceCount <= 0) return
                    val opened = deviceList.getDevice(0)
                    device = opened
                    val info = opened.info
                    appendLog(
                        "DeviceInfo name=${info.name} sn=${info.serialNumber} " +
                            "fw=${info.firmwareVersion} uid=${info.uid}"
                    )
                    Log.i(TAG, "DeviceInfo name=${info.name} sn=${info.serialNumber} fw=${info.firmwareVersion}")
                    openStream(opened)
                }
            } catch (e: Exception) {
                appendLog("attach 失败: ${e.message}")
                Log.e(TAG, "onDeviceAttach", e)
            } finally {
                deviceList.close()
            }
        }

        override fun onDeviceDetach(deviceList: DeviceList) {
            try {
                appendLog("设备已拔出")
                stopStream()
            } finally {
                deviceList.close()
            }
        }
    }

    override fun inflateBinding() = ActivityEgoUsbDemoBinding.inflate(layoutInflater)

    override fun initData() {
        ClickUtils.applySingleDebouncing(binding.btnBack) { finish() }
        ClickUtils.applySingleDebouncing(binding.btnStop) { finish() }
        try {
            obContext = OBContext(applicationContext, deviceCallback)
            appendLog("OBContext 已创建，等待 USB 授权/接入")
        } catch (e: Exception) {
            appendLog("OBContext 创建失败: ${e.message}")
            Log.e(TAG, "create OBContext", e)
        }
    }

    override fun onDestroy() {
        releaseAll()
        super.onDestroy()
    }

    private fun openStream(opened: Device) {
        val pipe = Pipeline(opened)
        val config = Config()
        config.enableStream(SensorType.COLOR_LEFT)
        pipe.start(config)
        config.close()
        pipeline = pipe
        running = true
        frameThread = Thread({ frameLoop(pipe) }, "ego-usb-frames").also { it.start() }
        appendLog("Pipeline 已启动 COLOR_LEFT")
    }

    private fun frameLoop(pipe: Pipeline) {
        while (running) {
            try {
                pipe.waitForFrameSet(100)?.use { frameSet ->
                    val frame = frameSet.getFrame<Frame>(FrameType.COLOR_LEFT)
                    if (frame != null) {
                        try {
                            val index = frame.index
                            if (index <= 3L || index % 30L == 0L) {
                                val line = "COLOR_LEFT index=$index format=${frame.format}"
                                Log.i(TAG, line)
                                appendLog(line)
                            }
                        } finally {
                            frame.close()
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "waitForFrameSet", e)
                }
            }
        }
    }

    private fun stopStream() {
        running = false
        frameThread?.join(500)
        frameThread = null
        synchronized(lock) {
            try {
                pipeline?.stop()
                pipeline?.close()
            } catch (_: Exception) {
            }
            pipeline = null
            try {
                device?.close()
            } catch (_: Exception) {
            }
            device = null
        }
    }

    private fun releaseAll() {
        stopStream()
        try {
            obContext?.close()
        } catch (_: Exception) {
        }
        obContext = null
    }

    private fun appendLog(line: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val current = binding.tvLog.text?.toString().orEmpty()
            binding.tvLog.text = if (current == getString(R.string.ego_demo_waiting)) {
                line
            } else {
                "$current\n$line"
            }
        } else {
            mainHandler.post { appendLog(line) }
        }
    }

    companion object {
        private const val TAG = "EgoUsbDemo"
    }
}
