package com.bingo.coresdk.route

import android.os.Bundle
import android.os.Looper
import android.os.Message
import com.bingo.coresdk.vi.MsgHandler
import com.bingo.coresdk.vi.VMsgConstant
import com.bingo.coresdk.vi.VMsgDispatcher
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 路况业务单例。对标百度 `BMARouteServiceController` + `RouteServiceController`。
 *
 * ## JNI 两种方式都可以用，先看这次调用能不能在当前栈拿齐结果
 *
 * **方式一：[getRoadCondition]**
 * 同步 native，函数返回时结果已经在手里。不要 Handler、不要 listener。
 * 对照 [com.bingo.coresdk.CryptoSdk.encrypt]、百度 `getEvRangeOnRouteInfoByIdx`。
 *
 * **方式二：[requestRoadCondition] + [IRoadConditionListener]**
 * 立刻只拿到 taskId，引擎工作线程稍后 `dispatch_vi_msg`，主线程 Handler 再回调 listener。
 * 对照百度 `asyncRequestRoadCondition` + `MSG_SEARCH_ROAD_CONDITION_UPDATE`。
 * 引擎自己推、Java 没 request 的事件（引导态、定位）也走方式二。
 *
 * 判断：当前 JNI 栈能拿齐 → 方式一；不能、还可能在非主线程到 → 方式二。
 * 完整步骤见 `coresdk/JNI接入指南.md`。Demo 两个按钮各打一条。
 *
 * - 进程级 `object`，第一次用到时 `init` 里 register Handler，不 unregister。
 * - App 只 [addListener] / [removeListener]（方式二才需要），不要 new、不要 release。
 * - 改 UI 从返回值或 listener 出，不要在 Activity 里自己 observe 消息号。
 */
object RoadConditionController {

    private val listeners = CopyOnWriteArrayList<IRoadConditionListener>()

    private val handler = object : MsgHandler(Looper.getMainLooper()) {
        override fun careAbout() {
            // 只订阅方式二的异步结果；[getRoadCondition] 不会进这里
            observe(VMsgConstant.MSG_SEARCH_ROAD_CONDITION_UPDATE)
        }

        override fun handleMessage(msg: Message) {
            if (msg.what == VMsgConstant.MSG_SEARCH_ROAD_CONDITION_UPDATE) {
                dispatchRoadConditionUpdate(msg)
            }
        }
    }

    init {
        VMsgDispatcher.registerMsgHandler(handler)
    }

    fun addListener(listener: IRoadConditionListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: IRoadConditionListener) {
        listeners.remove(listener)
    }

    /**
     * 方式一：同步 JNI，当前栈返回完整结果，不走 [VMsgDispatcher]、不回调 listener。
     */
    fun getRoadCondition(cityId: Int, roadName: String): RoadConditionResult {
        val bundle = NativeRoadCondition.nativeGetRoadCondition(cityId, roadName)
        return bundleToResult(errorCode = 0, bundle)
    }

    /**
     * 方式二：同步 JNI 只返回 taskId；路况正文稍后经
     * MSG_SEARCH_ROAD_CONDITION_UPDATE → [IRoadConditionListener]（主线程）。
     */
    fun requestRoadCondition(cityId: Int, roadName: String): Int {
        return NativeRoadCondition.nativeRequestRoadCondition(cityId, roadName)
    }

    private fun dispatchRoadConditionUpdate(message: Message) {
        val bundle = message.obj as? Bundle ?: return
        val result = bundleToResult(message.arg1, bundle)
        for (listener in listeners) {
            listener.onRoadConditionUpdate(
                result.errorCode,
                result.taskId,
                result.cityId,
                result.roadName,
                result.nativeThread
            )
        }
    }

    private fun bundleToResult(errorCode: Int, bundle: Bundle): RoadConditionResult {
        return RoadConditionResult(
            errorCode = errorCode,
            taskId = bundle.getInt(JniConstant.ROAD_CONDITION_TASK_ID),
            cityId = bundle.getInt(JniConstant.ROAD_CONDITION_CITY_ID),
            roadName = bundle.getString(JniConstant.ROAD_CONDITION_ROAD_NAME).orEmpty(),
            nativeThread = bundle.getString(JniConstant.ROAD_CONDITION_NATIVE_THREAD).orEmpty()
        )
    }
}
