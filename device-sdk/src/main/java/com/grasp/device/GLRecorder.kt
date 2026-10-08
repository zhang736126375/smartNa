package com.grasp.device

/**
 * 设备端录制。
 *
 * 从 [GLDeviceManager.recorder] 取得。录的是设备本地存储，不是手机上的文件。
 * 调用前设备需要已经 [GLConnection.connect]，并且 [GLDeviceManager.capabilities] 包含 [GLCapability.RECORD]。
 * 尚未 [GLDeviceManager.open] 或尚未连接时抛 [GLDeviceException]。
 */
interface GLRecorder {
    /**
     * 开始录制。
     *
     * [name] 只对 E309 有效，作为任务名；null 表示板端自动命名 `Task%04d`。
     * 没有 TF 卡、或板端拒绝开始时，E309 抛 [GLDeviceException]。
     * Orbbec 忽略 [name]，把录像写到设备 SD 卡。设备不支持 SD 卡录制时抛 [GLDeviceException]。
     */
    fun start(name: String? = null)

    /**
     * 停止录制。
     *
     * 设备尚未连接，或板端停止失败时抛 [GLDeviceException]。
     * 停的是设备上的录像，不会断开预览和连接。
     */
    fun stop()
}
