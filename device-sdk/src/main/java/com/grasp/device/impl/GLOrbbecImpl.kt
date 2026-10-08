package com.grasp.device.impl

import android.content.Context
import com.grasp.device.GLCapability
import com.grasp.device.GLCandidate
import com.grasp.device.GLDeviceException
import com.grasp.device.GLDiscoveryListener
import com.grasp.device.GLEndpoint
import com.grasp.device.GLLinkKind
import com.grasp.device.GLPreviewFrame
import com.grasp.device.GLPreviewListener
import com.grasp.device.GLVendor
import com.orbbec.obsensor.ColorFrame
import com.orbbec.obsensor.Config
import com.orbbec.obsensor.Device
import com.orbbec.obsensor.DeviceChangedCallback
import com.orbbec.obsensor.DeviceList
import com.orbbec.obsensor.OBContext
import com.orbbec.obsensor.OBException
import com.orbbec.obsensor.Pipeline
import com.orbbec.obsensor.StreamProfile
import com.orbbec.obsensor.property.DeviceProperty
import com.orbbec.obsensor.types.Format
import com.orbbec.obsensor.types.FrameType
import com.orbbec.obsensor.types.PermissionType
import com.orbbec.obsensor.types.RecordingAction
import com.orbbec.obsensor.types.RecordingDestination
import com.orbbec.obsensor.types.SensorType
import java.util.concurrent.atomic.AtomicBoolean

/** Orbbec 实现。网络设备默认端口 8090。预览取左彩色，录制写设备 SD 卡。 */
internal class GLOrbbecImpl(
    private val appContext: Context,
) : GLVendorDevice {

    override val vendor: GLVendor = GLVendor.ORBBEC

    override val capabilities: Set<GLCapability> = setOf(
        GLCapability.PREVIEW,
        GLCapability.RECORD,
    )

    private var context: OBContext? = null
    private var device: Device? = null
    private var activeId: String? = null
    private var pipeline: Pipeline? = null
    private val previewing = AtomicBoolean(false)
    private var previewThread: Thread? = null
    private val discovering = AtomicBoolean(false)
    private var listener: GLDiscoveryListener? = null
    private val pending = LinkedHashMap<String, Device>()
    private val known = LinkedHashMap<String, GLCandidate>()

    private val discoveryCallback = object : DeviceChangedCallback {
        override fun onDeviceAttach(deviceList: DeviceList?) {
            if (deviceList == null) {
                return
            }
            try {
                ingest(deviceList)
            } finally {
                deviceList.close()
            }
        }

        override fun onDeviceDetach(deviceList: DeviceList?) {
            if (deviceList == null) {
                return
            }
            try {
                detach(deviceList)
            } finally {
                deviceList.close()
            }
        }
    }

    override fun startDiscovery(listener: GLDiscoveryListener) {
        this.listener = listener
        discovering.set(true)
        val ctx = ensureContext()
        ctx.setDevicesChangedCallback(discoveryCallback)
        ctx.enableNetDeviceEnumeration(true)
        val current = ctx.queryDevices()
        if (current != null) {
            try {
                ingest(current)
            } finally {
                current.close()
            }
        } else {
            publish()
        }
    }

    override fun stopDiscovery() {
        discovering.set(false)
        listener = null
        synchronized(this) {
            context?.setDevicesChangedCallback(EMPTY_CALLBACK)
            val active = device
            val dropped = pending.values.filter { it !== active }
            pending.clear()
            known.clear()
            dropped.forEach { runCatching { it.close() } }
            if (device == null) {
                context?.close()
                context = null
            }
        }
    }

    override fun connect(candidate: GLCandidate) {
        if (candidate.vendor != GLVendor.ORBBEC) {
            throw GLDeviceException("这不是 Orbbec 设备")
        }
        if (candidate.link == GLLinkKind.NETWORK && candidate.host.isNotEmpty() && !pending.containsKey(candidate.id)) {
            connect(GLEndpoint(candidate.host, candidate.port))
            return
        }
        val opened = synchronized(this) { pending.remove(candidate.id) }
            ?: throw GLDeviceException("设备已失效，请重新发现")
        adopt(opened, candidate.id)
    }

    override fun connect(endpoint: GLEndpoint) {
        val port = if (endpoint.port > 0) endpoint.port else DEFAULT_PORT
        try {
            val ctx = ensureContext()
            val opened = ctx.createNetDevice(endpoint.host, port)
                ?: throw GLDeviceException("Orbbec 未连上 ${endpoint.host}:$port")
            adopt(opened, "net:${endpoint.host}:$port")
        } catch (e: GLDeviceException) {
            throw e
        } catch (e: RuntimeException) {
            throw GLDeviceException("Orbbec 连接失败 ${endpoint.host}:$port", e)
        }
    }

    override fun disconnect() {
        stopDiscovery()
        stopPreview()
        releaseDevice()
        context?.close()
        context = null
    }

    override fun startPreview(listener: GLPreviewListener) {
        val opened = device ?: throw GLDeviceException("Orbbec 尚未连接")
        synchronized(this) {
            if (previewing.get()) {
                throw GLDeviceException("Orbbec 预览已在进行")
            }
            val pipe = pipeline ?: Pipeline(opened).also { pipeline = it }
            val config = Config()
            var profile: StreamProfile? = null
            try {
                profile = pickColorProfile(pipe)
                config.enableStream(profile)
                pipe.start(config)
            } catch (e: RuntimeException) {
                profile?.close()
                config.close()
                throw GLDeviceException("Orbbec 开流失败", e)
            }
            profile.close()
            config.close()
            previewing.set(true)
            previewThread = Thread({
                while (previewing.get()) {
                    val frameSet = try {
                        pipe.waitForFrameSet(1000)
                    } catch (e: RuntimeException) {
                        if (previewing.get()) {
                            break
                        }
                        null
                    } ?: continue
                    try {
                        val color = frameSet.getFrame<ColorFrame>(FrameType.COLOR_LEFT) ?: continue
                        try {
                            val size = color.dataSize
                            if (size <= 0) {
                                continue
                            }
                            val bytes = ByteArray(size)
                            color.getData(bytes)
                            listener.onFrame(
                                GLPreviewFrame(
                                    channel = 0,
                                    mime = mimeOf(color.format),
                                    data = bytes,
                                    keyframe = false,
                                )
                            )
                        } finally {
                            color.close()
                        }
                    } finally {
                        frameSet.close()
                    }
                }
            }, "orbbec-preview").also { it.start() }
        }
    }

    override fun stopPreview() {
        previewing.set(false)
        previewThread?.join(1500)
        previewThread = null
        try {
            pipeline?.stop()
        } catch (_: RuntimeException) {
            // 未开流时 stop 可能失败，断开连接仍要继续释放
        }
    }

    override fun startRecord(name: String?) {
        val opened = device ?: throw GLDeviceException("Orbbec 尚未连接")
        try {
            opened.setPropertyValueI(
                DeviceProperty.OB_PROP_RECORDING_DESTINATION_INT,
                RecordingDestination.OB_RECORDING_DESTINATION_SD_CARD.value(),
            )
            if (!opened.isPropertySupported(
                    DeviceProperty.OB_PROP_RECORDING_ACTION_INT,
                    PermissionType.OB_PERMISSION_WRITE,
                )
            ) {
                throw GLDeviceException("Orbbec 不支持 SD 卡录制")
            }
            opened.executeRecordingAction(RecordingAction.OB_RECORDING_ACTION_START)
        } catch (e: GLDeviceException) {
            throw e
        } catch (e: OBException) {
            throw GLDeviceException("Orbbec 开始录制失败", e)
        }
    }

    override fun stopRecord() {
        val opened = device ?: throw GLDeviceException("Orbbec 尚未连接")
        try {
            opened.executeRecordingAction(RecordingAction.OB_RECORDING_ACTION_STOP)
        } catch (e: OBException) {
            throw GLDeviceException("Orbbec 停止录制失败", e)
        }
    }

    override fun listFiles(): List<String> {
        throw GLDeviceException("Orbbec 这一版没有统一的文件列表")
    }

    private fun ensureContext(): OBContext {
        val existing = context
        if (existing != null) {
            return existing
        }
        return OBContext(appContext, discoveryCallback).also { context = it }
    }

    private fun adopt(opened: Device, id: String) {
        stopPreview()
        releaseDevice()
        device = opened
        activeId = id
    }

    private fun releaseDevice() {
        pipeline?.close()
        pipeline = null
        device?.close()
        device = null
        activeId = null
    }

    private fun ingest(list: DeviceList) {
        synchronized(this) {
            for (i in 0 until list.deviceCount) {
                val uid = list.getUid(i) ?: continue
                if (uid == activeId || pending.containsKey(uid)) {
                    known[uid] = describe(list, i, uid)
                    continue
                }
                val opened = list.getDevice(i) ?: continue
                pending[uid] = opened
                known[uid] = describe(list, i, uid)
            }
        }
        publish()
    }

    private fun detach(list: DeviceList) {
        val lostActive = synchronized(this) {
            var activeLost = false
            for (i in 0 until list.deviceCount) {
                val uid = list.getUid(i) ?: continue
                pending.remove(uid)?.let { runCatching { it.close() } }
                known.remove(uid)
                if (uid == activeId) {
                    activeLost = true
                }
            }
            activeLost
        }
        if (lostActive) {
            stopPreview()
            releaseDevice()
        }
        publish()
    }

    private fun describe(list: DeviceList, index: Int, uid: String): GLCandidate {
        val type = list.getConnectionType(index) ?: "USB"
        val ip = list.getIpAddress(index) ?: "0.0.0.0"
        val serial = list.getDeviceSerialNumber(index)?.takeIf { it.isNotEmpty() } ?: uid
        val network = type.contains("Ethernet", ignoreCase = true) || (ip.isNotEmpty() && ip != "0.0.0.0")
        return GLCandidate(
            id = uid,
            vendor = GLVendor.ORBBEC,
            link = if (network) GLLinkKind.NETWORK else GLLinkKind.USB,
            host = if (ip == "0.0.0.0") "" else ip,
            port = if (network) DEFAULT_PORT else 0,
            label = "Orbbec $serial ($type)",
        )
    }

    private fun publish() {
        val snapshot = synchronized(this) { known.values.toList() }
        listener?.onDiscovered(snapshot)
    }

    private fun pickColorProfile(pipe: Pipeline): StreamProfile {
        val profiles = pipe.getStreamProfileList(SensorType.COLOR_LEFT)
            ?: throw GLDeviceException("Orbbec 没有左彩色流")
        var chosen: StreamProfile? = null
        try {
            for (i in 0 until profiles.count) {
                val profile = profiles.getProfile(i)
                val format = profile.format
                if (chosen == null && (format == Format.H264 || format == Format.H265 || format == Format.HEVC)) {
                    chosen = profile
                } else {
                    profile.close()
                }
            }
        } finally {
            profiles.close()
        }
        return chosen ?: throw GLDeviceException("Orbbec 左彩色没有 H.264/H.265")
    }

    private fun mimeOf(format: Format): String {
        return if (format == Format.H265 || format == Format.HEVC) "video/hevc" else "video/avc"
    }

    private companion object {
        const val DEFAULT_PORT = 8090

        val EMPTY_CALLBACK = object : DeviceChangedCallback {
            override fun onDeviceAttach(deviceList: DeviceList?) {
                deviceList?.close()
            }

            override fun onDeviceDetach(deviceList: DeviceList?) {
                deviceList?.close()
            }
        }
    }
}
