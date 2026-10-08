package com.grasp.device

/**
 * 一帧压缩视频，不能直接画到 ImageView。
 *
 * 用 [mime] 创建 MediaCodec，把 [data] 送进解码器，输出到 Surface。
 * E309 为 `video/hevc`，[channel] 是 0–5，[keyframe] 有效。
 * Orbbec 左彩色为 `video/avc` 或 `video/hevc`，[channel] 固定 0，[keyframe] 这一版恒为 false。
 */
data class GLPreviewFrame(
    /** 画面通道。E309 为 0–5；Orbbec 左彩色固定 0。 */
    val channel: Int,
    /** MediaCodec 要用的 MIME。`video/hevc` 或 `video/avc`。 */
    val mime: String,
    /** 这一帧的压缩数据。交给解码器，不能直接当 Bitmap。 */
    val data: ByteArray,
    /** 是否为关键帧。E309 有效；Orbbec 这一版恒为 false。 */
    val keyframe: Boolean,
)

/**
 * 预览帧回调。
 *
 * 由 [GLPreview.start] 注册。在收流线程触发，不要在这里改界面或做耗时操作。
 */
fun interface GLPreviewListener {
    /**
     * 收到一帧压缩视频。
     *
     * [frame] 的 [GLPreviewFrame.data] 由 SDK 每次新建，回调返回后仍然可以读取。
     * 用 [GLPreviewFrame.mime] 创建 MediaCodec，把数据送进解码器，输出到 Surface。
     */
    fun onFrame(frame: GLPreviewFrame)
}

/**
 * 订阅实时画面。
 *
 * 从 [GLDeviceManager.preview] 取得。
 * 调用前设备需要已经 [GLConnection.connect]，并且 [GLDeviceManager.capabilities] 包含 [GLCapability.PREVIEW]。
 * 尚未 [GLDeviceManager.open] 或尚未连接时抛 [GLDeviceException]。
 */
interface GLPreview {
    /**
     * 开始预览，帧通过 [listener] 送出。
     *
     * 会在后台线程持续回调 [GLPreviewListener.onFrame]，直到 [stop] 或 [GLConnection.disconnect]。
     * 预览已经在进行时再次调用会抛 [GLDeviceException]，先 [stop] 再开。
     * E309 订阅 9580 上的 HEVC。Orbbec 打开左彩色的 H.264 或 H.265。
     */
    fun start(listener: GLPreviewListener)

    /**
     * 停止预览。
     *
     * E309 关掉视频订阅。Orbbec 停掉 Pipeline。可以重复调用。
     * 只停画面，不断开设备；要释放连接请调用 [GLConnection.disconnect]。
     */
    fun stop()
}
