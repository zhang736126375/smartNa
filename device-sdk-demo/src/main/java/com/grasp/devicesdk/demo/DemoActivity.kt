package com.grasp.devicesdk.demo

import android.app.Activity
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.grasp.device.GLCandidate
import com.grasp.device.GLCapability
import com.grasp.device.GLDeviceException
import com.grasp.device.GLDeviceManager
import com.grasp.device.GLDiscoveryListener
import com.grasp.device.GLEndpoint
import com.grasp.device.GLPreviewFrame
import com.grasp.device.GLVendor
import java.util.ArrayDeque

/**
 * GLDeviceManager 的调用示例。连接、预览、录制、文件各自一个接口。
 *
 * 顺序：选厂商 → 发现或直连 → 预览 / 录制 / 列文件 → 断开。
 * 预览帧是压缩数据，这里用 MediaCodec 画到 SurfaceView。解码失败只记日志，不代表连接失败。
 */
class DemoActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var hostInput: EditText
    private val lines = ArrayDeque<String>()
    private var vendor = GLVendor.E309
    private lateinit var manager: GLDeviceManager
    private var candidates: List<GLCandidate> = emptyList()
    private var renderer: SurfaceRenderer? = null
    private var previewFrames = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_demo)
        logView = findViewById(R.id.log)
        hostInput = findViewById(R.id.host)
        val surface = findViewById<SurfaceView>(R.id.preview_surface)
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                renderer = SurfaceRenderer(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                renderer?.release()
                renderer = null
            }
        })

        findViewById<Button>(R.id.vendor_e309).setOnClickListener { selectVendor(GLVendor.E309) }
        findViewById<Button>(R.id.vendor_orbbec).setOnClickListener { selectVendor(GLVendor.ORBBEC) }
        findViewById<Button>(R.id.discover).setOnClickListener { discover() }
        findViewById<Button>(R.id.connect_first).setOnClickListener { connectFirst() }
        findViewById<Button>(R.id.connect_host).setOnClickListener { connectHost() }
        findViewById<Button>(R.id.preview).setOnClickListener { startPreview() }
        findViewById<Button>(R.id.stop_preview).setOnClickListener { runIo("停预览") { manager.preview.stop() } }
        findViewById<Button>(R.id.record).setOnClickListener { record() }
        findViewById<Button>(R.id.stop_record).setOnClickListener { runIo("停止录制") { manager.recorder.stop() } }
        findViewById<Button>(R.id.list_files).setOnClickListener { listFiles() }
        findViewById<Button>(R.id.disconnect).setOnClickListener { disconnect() }
        manager = GLDeviceManager(this)
        selectVendor(GLVendor.E309)
    }

    override fun onDestroy() {
        if (::manager.isInitialized) {
            manager.close()
        }
        renderer?.release()
        super.onDestroy()
    }

    /** 换厂商。GLDeviceManager 会先断开上一台，再换成对应实现。 */
    private fun selectVendor(next: GLVendor) {
        if (manager.vendor == next) {
            return
        }
        manager.open(next)
        vendor = next
        candidates = emptyList()
        log("设备 ${next.name} 能力 ${manager.capabilities}")
    }

    /** connection.startDiscovery：E309 扫网段，Orbbec 等 USB / 网络枚举。 */
    private fun discover() {
        log("开始发现 ${manager.vendor}")
        manager.connection.startDiscovery(object : GLDiscoveryListener {
            override fun onDiscovered(devices: List<GLCandidate>) {
                candidates = devices
                val text = if (devices.isEmpty()) {
                    "没有发现设备"
                } else {
                    devices.joinToString("\n") { "${it.label}  ${it.link}  ${it.host}" }
                }
                log(text)
            }

            override fun onDiscoveryFailed(error: GLDeviceException) {
                log("发现失败 ${error.message}")
            }
        })
    }

    /** connection.connect(GLCandidate)：用发现列表里的第一台。 */
    private fun connectFirst() {
        val candidate = candidates.firstOrNull()
        if (candidate == null) {
            log("还没有设备，先点发现")
            return
        }
        runIo("连接 ${candidate.label}") { manager.connection.connect(candidate) }
    }

    /** connection.connect(GLEndpoint)：已知 IP 时跳过发现。Orbbec 会阻塞，放在后台线程。 */
    private fun connectHost() {
        val host = hostInput.text?.toString()?.trim().orEmpty()
        if (host.isEmpty()) {
            log("请填写 IP")
            return
        }
        runIo("直连 $host") { manager.connection.connect(GLEndpoint(host)) }
    }

    /** preview.start：压缩帧回调。能解码就画到 Surface，同时每 30 帧记一条日志。 */
    private fun startPreview() {
        if (GLCapability.PREVIEW !in manager.capabilities) {
            log("不支持预览")
            return
        }
        previewFrames = 0
        runIo("预览") {
            manager.preview.start { frame -> render(frame) }
        }
    }

    private fun render(frame: GLPreviewFrame) {
        renderer?.submit(frame)
        previewFrames++
        if (previewFrames % 30 == 1) {
            log("帧 ch=${frame.channel} ${frame.mime} ${frame.data.size}B key=${frame.keyframe}")
        }
    }

    /** recorder.start：E309 可传任务名；Orbbec 忽略名字，录到 SD 卡。 */
    private fun record() {
        if (GLCapability.RECORD !in manager.capabilities) {
            log("不支持录制")
            return
        }
        val name = if (manager.vendor == GLVendor.E309) "sdkdemo" else null
        runIo("开始录制") { manager.recorder.start(name) }
    }

    /** files.list：E309 返回任务名。Orbbec 会抛 GLDeviceException。 */
    private fun listFiles() {
        if (GLCapability.FILES !in manager.capabilities) {
            log("当前厂商没有文件列表")
            return
        }
        runIo("列文件") {
            val names = manager.files.list()
            log(if (names.isEmpty()) "没有任务" else names.joinToString())
        }
    }

    private fun disconnect() {
        manager.connection.disconnect()
        candidates = emptyList()
        log("已断开")
    }

    /** 连接和录制会阻塞，统一丢到后台，结果回到主线程打日志。 */
    private fun runIo(action: String, block: () -> Unit) {
        log(action)
        Thread({
            try {
                block()
                log("$action 完成")
            } catch (e: GLDeviceException) {
                log("$action 失败 ${e.message}")
            }
        }, "device-sdk-demo").start()
    }

    private fun log(text: String) {
        runOnUiThread {
            lines.addLast(text)
            while (lines.size > 40) {
                lines.removeFirst()
            }
            logView.text = lines.joinToString("\n")
        }
    }
}

/** 把 GLPreviewFrame.data 送进解码器，输出到 Surface。宽高未知时用 1920x1080 占位。 */
private class SurfaceRenderer(surface: Surface) {
    private var codec: MediaCodec? = null
    private var mime: String? = null
    private val surface = surface

    fun submit(frame: GLPreviewFrame) {
        if (frame.data.isEmpty() || !surface.isValid) {
            return
        }
        try {
            ensure(frame.mime)
            val decoder = codec ?: return
            val index = decoder.dequeueInputBuffer(10_000)
            if (index < 0) {
                return
            }
            val input = decoder.getInputBuffer(index) ?: return
            input.clear()
            if (frame.data.size > input.capacity()) {
                return
            }
            input.put(frame.data)
            decoder.queueInputBuffer(index, 0, frame.data.size, System.nanoTime() / 1000, 0)
            val info = MediaCodec.BufferInfo()
            var output = decoder.dequeueOutputBuffer(info, 0)
            while (output >= 0) {
                decoder.releaseOutputBuffer(output, true)
                output = decoder.dequeueOutputBuffer(info, 0)
            }
        } catch (_: Exception) {
            release()
        }
    }

    fun release() {
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        codec?.release()
        codec = null
        mime = null
    }

    private fun ensure(nextMime: String) {
        if (codec != null && mime == nextMime) {
            return
        }
        release()
        val format = MediaFormat.createVideoFormat(nextMime, 1920, 1080)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
        val decoder = MediaCodec.createDecoderByType(nextMime)
        decoder.configure(format, surface, null, 0)
        decoder.start()
        codec = decoder
        mime = nextMime
    }
}
