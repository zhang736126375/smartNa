package com.grasp.device

/**
 * 设备上可拉取的文件。
 *
 * 从 [GLDeviceManager.files] 取得。
 * 调用前先看 [GLDeviceManager.capabilities] 是否包含 [GLCapability.FILES]。
 * 尚未 [GLDeviceManager.open] 时抛 [GLDeviceException]。
 */
interface GLFiles {
    /**
     * 列出已就绪、可以拉取的条目名。
     *
     * E309 返回任务名，没有任务时返回空列表。尚未连接或板端查询失败时抛 [GLDeviceException]。
     * Orbbec 这一版没有统一文件列表，调用会抛 [GLDeviceException]。
     *
     * @return 条目名，可直接显示。空列表表示当前没有可拉取的任务。
     */
    fun list(): List<String>
}
