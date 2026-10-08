package com.grasp.device

/** 设备厂商。连接方式和帧格式不同，操作接口相同。 */
enum class GLVendor {
    /** TCP + NNG。出厂热点地址 192.168.1.100，切 STA 后需重新发现。 */
    E309,

    /** USB 插拔，或网络端口 8090。 */
    ORBBEC,
}

/**
 * 当前厂商实际具备的能力。
 *
 * 调用前先看 [GLDeviceManager.capabilities]。E309 有预览、录制、文件列表；
 * Orbbec 有预览和录制。[CONFIG] 尚未开放。
 */
enum class GLCapability {
    /** [GLPreview] */
    PREVIEW,

    /** [GLRecorder] */
    RECORD,

    /** [GLFiles] */
    FILES,

    /** 曝光、Wi-Fi 等配置，这一版没有对应方法。 */
    CONFIG,
}

/** 连接、开流、录制或协议错误。[cause] 保留厂商原始异常。 */
class GLDeviceException(message: String, cause: Throwable? = null) : Exception(message, cause)
