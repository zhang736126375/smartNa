package com.grasp.device

import android.content.Context
import com.grasp.device.impl.GLE309Impl
import com.grasp.device.impl.GLOrbbecImpl
import com.grasp.device.impl.GLVendorDevice

/**
 * SDK 入口。按厂商创建实现，再把能力拆开交给调用方。
 *
 * 调用方只用这里和四个接口，不接触 `impl` 包：
 *
 * - [connection] 发现、连接、断开
 * - [preview] 实时预览
 * - [recorder] 设备端录制
 * - [files] 可拉取的文件
 *
 * ```
 * val manager = GLDeviceManager(context)
 * manager.open(GLVendor.E309)
 * manager.connection.startDiscovery(listener)
 * manager.connection.connect(candidate)
 * manager.preview.start { frame -> }
 * manager.recorder.start()
 * manager.files.list()
 * manager.recorder.stop()
 * manager.preview.stop()
 * manager.connection.disconnect()
 * manager.close()
 * ```
 *
 * 同一时刻只管理一台。换厂商时会先断开上一台。
 * [context] 会转成 Application，Orbbec 用它注册 USB 监听。
 *
 * 可运行示例见 `:device-sdk-demo` 的 [com.grasp.devicesdk.demo.DemoActivity]。
 */
class GLDeviceManager(context: Context) {

    private val appContext = context.applicationContext
    private var active: GLVendorDevice? = null

    /** 当前厂商。尚未 [open]，或已经 [close] 时为 null。 */
    val vendor: GLVendor?
        get() = active?.vendor

    /** 当前厂商的能力。尚未 [open] 时为空。 */
    val capabilities: Set<GLCapability>
        get() = active?.capabilities.orEmpty()

    /** 发现和连接。 */
    val connection: GLConnection = object : GLConnection {
        override fun startDiscovery(listener: GLDiscoveryListener) = requireActive().startDiscovery(listener)
        override fun stopDiscovery() = requireActive().stopDiscovery()
        override fun connect(candidate: GLCandidate) = requireActive().connect(candidate)
        override fun connect(endpoint: GLEndpoint) = requireActive().connect(endpoint)
        override fun disconnect() = requireActive().disconnect()
    }

    /** 实时预览。 */
    val preview: GLPreview = object : GLPreview {
        override fun start(listener: GLPreviewListener) = requireActive().startPreview(listener)
        override fun stop() = requireActive().stopPreview()
    }

    /** 设备端录制。 */
    val recorder: GLRecorder = object : GLRecorder {
        override fun start(name: String?) = requireActive().startRecord(name)
        override fun stop() = requireActive().stopRecord()
    }

    /** 设备上可拉取的文件。 */
    val files: GLFiles = object : GLFiles {
        override fun list(): List<String> = requireActive().listFiles()
    }

    /**
     * 打开指定厂商。
     *
     * 已经是同一厂商时保持现有实例，避免发现和连接状态被丢掉。
     * 换厂商时先断开上一台。
     */
    fun open(vendor: GLVendor) {
        val existing = active
        if (existing != null && existing.vendor == vendor) {
            return
        }
        existing?.disconnect()
        active = when (vendor) {
            GLVendor.E309 -> GLE309Impl()
            GLVendor.ORBBEC -> GLOrbbecImpl(appContext)
        }
    }

    /** 断开当前设备并清空。可重复调用。 */
    fun close() {
        active?.disconnect()
        active = null
    }

    private fun requireActive(): GLVendorDevice {
        return active ?: throw GLDeviceException("请先调用 open 选择厂商")
    }
}
