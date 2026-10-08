package com.grasp.device

/** 设备怎么连上手机。 */
enum class GLLinkKind {
    /** 仅 Orbbec USB。 */
    USB,

    /** E309 始终是网络；Orbbec 以太网枚举或指定 IP 时也是网络。 */
    NETWORK,
}

/**
 * 已知地址，跳过发现直接连。
 *
 * E309 只读 [host]，各业务端口由 SDK 自己拼。
 * Orbbec 使用 [host] 和 [port]；[port] 为 0 时用 8090。
 */
data class GLEndpoint(
    /** IP 或主机名。E309、Orbbec 都读这个字段。 */
    val host: String,
    /** Orbbec 端口。0 表示用默认 8090。E309 忽略。 */
    val port: Int = 0,
)

/**
 * [GLConnection.startDiscovery] 看到的一台设备。
 *
 * 交给 [GLConnection.connect] 选中。回调可能在后台线程，更新界面要切回主线程。
 */
data class GLCandidate(
    /** SDK 内部标识，不要自己拼。 */
    val id: String,
    /** 这台设备属于哪家厂商。交给不匹配的实现去 [GLConnection.connect] 会失败。 */
    val vendor: GLVendor,
    /** USB 还是网络。决定界面上要不要显示 [host]。 */
    val link: GLLinkKind,
    /** USB 设备可能为空串。 */
    val host: String,
    /** E309 为版本端口 9585；Orbbec 网络为 8090；USB 为 0。 */
    val port: Int,
    /** 可直接显示的名称。 */
    val label: String,
)

/**
 * 发现回调。
 *
 * E309 扫完本机 /24 后回调一次。Orbbec 在 USB 插拔或网络枚举变化时回调，列表是当前可见设备。
 */
interface GLDiscoveryListener {
    /**
     * 发现结果。
     *
     * [devices] 是这一轮能看到的设备，不是相对上次的增量。
     * E309 扫完本机 /24 后只回调一次，空列表表示没扫到。
     * Orbbec 在 USB 插拔或网络枚举变化时再次回调，列表是当前全部可见设备。
     * 可能在后台线程调用，更新界面要切回主线程。
     */
    fun onDiscovered(devices: List<GLCandidate>)

    /**
     * 扫描过程失败。
     *
     * 默认空实现，不处理也不会影响后续 [GLConnection.connect]。
     * E309 扫网段出错时会走到这里。连接单台设备失败不走这个回调，而是由 [GLConnection.connect] 抛 [GLDeviceException]。
     */
    fun onDiscoveryFailed(error: GLDeviceException) {}
}

/**
 * 发现设备、建立连接、断开连接。
 *
 * 从 [GLDeviceManager.connection] 取得。调用前要先 [GLDeviceManager.open]。
 * 尚未 open 时，这里的方法都会抛 [GLDeviceException]。
 */
interface GLConnection {
    /**
     * 开始发现，结果通过 [listener] 送出。
     *
     * 重复调用会先停掉上一轮扫描，再开始新的一轮。
     * E309：在后台线程扫本机所在 /24，并优先探测出厂热点 192.168.1.100 的 9585 端口。
     * Orbbec：注册 USB 插拔，并打开网络设备枚举；调用时已经插着的设备会立刻出现在回调里。
     */
    fun startDiscovery(listener: GLDiscoveryListener)

    /**
     * 停止发现。
     *
     * 已连接的设备不会因此断开。可以重复调用。
     * 停掉之后，晚到的 E309 扫描结果不会再通知 [GLDiscoveryListener]。
     */
    fun stopDiscovery()

    /**
     * 连接 [GLConnection.startDiscovery] 得到的一台设备。
     *
     * [candidate] 的 [GLCandidate.vendor] 必须和当前 [GLDeviceManager.open] 的厂商一致，否则抛 [GLDeviceException]。
     * E309 会再查一次版本接口，确认对端是 E309，失败时不保存地址。
     * Orbbec USB 使用发现时已经打开的设备句柄。这台设备拔掉或已经连过之后句柄会失效，需要重新发现。
     * 已知 IP 时用 [connect] 的另一个重载，不必先发现。
     */
    fun connect(candidate: GLCandidate)

    /**
     * 用已知地址直接连接，不经过发现。
     *
     * E309 只使用 [GLEndpoint.host]，连上后再查版本接口。
     * Orbbec 使用 [GLEndpoint.host] 和 [GLEndpoint.port]，这次调用会阻塞，不要放在主线程。
     * 地址不通或对端不是预期设备时抛 [GLDeviceException]。
     */
    fun connect(endpoint: GLEndpoint)

    /**
     * 断开当前设备。
     *
     * 会先停预览、再停止发现，然后释放设备句柄。可以重复调用。
     * 不会换掉 [GLDeviceManager] 里已经 [GLDeviceManager.open] 的厂商，之后还可以再次 [connect]。
     * 要连同厂商选择一起清掉，调用 [GLDeviceManager.close]。
     */
    fun disconnect()
}
