package com.grasp.device.impl

import com.grasp.device.GLCapability
import com.grasp.device.GLCandidate
import com.grasp.device.GLDeviceException
import com.grasp.device.GLDiscoveryListener
import com.grasp.device.GLEndpoint
import com.grasp.device.GLLinkKind
import com.grasp.device.GLPreviewFrame
import com.grasp.device.GLPreviewListener
import com.grasp.device.GLVendor
import com.grasp.nng.CaptureControl
import com.grasp.nng.DeviceFinder
import com.grasp.nng.E309Exception
import com.grasp.nng.E309Ports
import com.grasp.nng.E309Wire
import com.grasp.nng.FileService
import com.grasp.nng.VersionService
import com.grasp.nng.VideoTopic
import java.util.concurrent.atomic.AtomicBoolean

/** E309 实现。TCP + NNG。预览走 9580，录制走 9591，文件列表走 9592。 */
internal class GLE309Impl : GLVendorDevice {

    override val vendor: GLVendor = GLVendor.E309

    override val capabilities: Set<GLCapability> = setOf(
        GLCapability.PREVIEW,
        GLCapability.RECORD,
        GLCapability.FILES,
    )

    private var host: String? = null
    private val previewing = AtomicBoolean(false)
    private var subscriber: E309Wire.Subscriber? = null
    private var previewThread: Thread? = null
    private var discoveryThread: Thread? = null
    private val discovering = AtomicBoolean(false)
    private var discoveryGeneration = 0

    override fun startDiscovery(listener: GLDiscoveryListener) {
        stopDiscovery()
        val generation = ++discoveryGeneration
        discovering.set(true)
        discoveryThread = Thread({
            try {
                val hosts = DeviceFinder.discover(listOf(E309Ports.DEFAULT_HOST))
                if (!discovering.get() || generation != discoveryGeneration) {
                    return@Thread
                }
                listener.onDiscovered(hosts.map { candidateOf(it) })
            } catch (e: E309Exception) {
                if (discovering.get() && generation == discoveryGeneration) {
                    listener.onDiscoveryFailed(GLDeviceException("E309 扫描设备失败", e))
                }
            }
        }, "e309-discovery").also { it.start() }
    }

    override fun stopDiscovery() {
        discovering.set(false)
        discoveryGeneration++
        discoveryThread = null
    }

    override fun connect(candidate: GLCandidate) {
        if (candidate.vendor != GLVendor.E309) {
            throw GLDeviceException("这不是 E309 设备")
        }
        connect(GLEndpoint(candidate.host))
    }

    override fun connect(endpoint: GLEndpoint) {
        try {
            VersionService.query(endpoint.host)
        } catch (e: E309Exception) {
            throw GLDeviceException("E309 连接失败 ${endpoint.host}", e)
        }
        host = endpoint.host
    }

    override fun disconnect() {
        stopDiscovery()
        stopPreview()
        host = null
    }

    override fun startPreview(listener: GLPreviewListener) {
        val target = host ?: throw GLDeviceException("E309 尚未连接")
        synchronized(this) {
            if (previewing.get()) {
                throw GLDeviceException("E309 预览已在进行")
            }
            val sub = try {
                VideoTopic.subscribe(target)
            } catch (e: E309Exception) {
                throw GLDeviceException("E309 订阅视频失败", e)
            }
            subscriber = sub
            previewing.set(true)
            previewThread = Thread({
                try {
                    while (previewing.get()) {
                        val payload = try {
                            sub.receive()
                        } catch (e: E309Exception) {
                            break
                        }
                        if (payload == null || !previewing.get()) {
                            continue
                        }
                        val frame = VideoTopic.parse(payload)
                        listener.onFrame(
                            GLPreviewFrame(
                                channel = frame.channel,
                                mime = "video/hevc",
                                data = frame.data,
                                keyframe = frame.keyframe,
                            )
                        )
                    }
                } finally {
                    sub.close()
                }
            }, "e309-preview").also { it.start() }
        }
    }

    override fun stopPreview() {
        previewing.set(false)
        synchronized(this) {
            subscriber?.close()
            subscriber = null
        }
        previewThread?.join(1500)
        previewThread = null
    }

    override fun startRecord(name: String?) {
        val target = host ?: throw GLDeviceException("E309 尚未连接")
        try {
            CaptureControl(target).start(name)
        } catch (e: E309Exception) {
            throw GLDeviceException("E309 开始录制失败", e)
        }
    }

    override fun stopRecord() {
        val target = host ?: throw GLDeviceException("E309 尚未连接")
        try {
            CaptureControl(target).stop()
        } catch (e: E309Exception) {
            throw GLDeviceException("E309 停止录制失败", e)
        }
    }

    private fun candidateOf(host: String): GLCandidate {
        return GLCandidate(
            id = "e309:$host",
            vendor = GLVendor.E309,
            link = GLLinkKind.NETWORK,
            host = host,
            port = E309Ports.VERSION,
            label = if (host == E309Ports.DEFAULT_HOST) "E309 热点 $host" else "E309 $host",
        )
    }

    override fun listFiles(): List<String> {
        val target = host ?: throw GLDeviceException("E309 尚未连接")
        return try {
            FileService(target).listTasks()
        } catch (e: E309Exception) {
            throw GLDeviceException("E309 列出任务失败", e)
        }
    }
}
