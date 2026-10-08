package com.grasp.device.impl

import com.grasp.device.GLCapability
import com.grasp.device.GLCandidate
import com.grasp.device.GLDiscoveryListener
import com.grasp.device.GLEndpoint
import com.grasp.device.GLPreviewFrame
import com.grasp.device.GLPreviewListener
import com.grasp.device.GLVendor

/**
 * 一个厂商的全部实现。对外不暴露。
 *
 * [GLDeviceManager] 把它拆成连接、预览、录制、文件四个接口。
 * 预览和录制都有 start/stop，所以这里的方法名保持区分，避免一个类实现两套同名方法。
 */
internal interface GLVendorDevice {
    /** 这个实现对应的厂商。由 [com.grasp.device.GLDeviceManager.open] 选定后不再变化。 */
    val vendor: GLVendor

    /** 这个实现实际提供的能力。调用方通过 [com.grasp.device.GLDeviceManager.capabilities] 读取。 */
    val capabilities: Set<GLCapability>

    /** 见 [com.grasp.device.GLConnection.startDiscovery]。 */
    fun startDiscovery(listener: GLDiscoveryListener)

    /** 见 [com.grasp.device.GLConnection.stopDiscovery]。 */
    fun stopDiscovery()

    /** 见 [com.grasp.device.GLConnection.connect]。 */
    fun connect(candidate: GLCandidate)

    /** 见 [com.grasp.device.GLConnection.connect]。 */
    fun connect(endpoint: GLEndpoint)

    /** 见 [com.grasp.device.GLConnection.disconnect]。 */
    fun disconnect()

    /** 见 [com.grasp.device.GLPreview.start]。 */
    fun startPreview(listener: GLPreviewListener)

    /** 见 [com.grasp.device.GLPreview.stop]。 */
    fun stopPreview()

    /** 见 [com.grasp.device.GLRecorder.start]。 */
    fun startRecord(name: String? = null)

    /** 见 [com.grasp.device.GLRecorder.stop]。 */
    fun stopRecord()

    /** 见 [com.grasp.device.GLFiles.list]。 */
    fun listFiles(): List<String>
}
