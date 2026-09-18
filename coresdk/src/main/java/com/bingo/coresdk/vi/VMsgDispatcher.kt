package com.bingo.coresdk.vi

import android.os.Handler
import android.os.Message
import java.lang.ref.WeakReference

/**
 * 引擎消息分发。对标百度 `com.baidu.mapautosdk.vi.VMsgDispatcher`。
 *
 * JNI **只**通过 [dispatchMessage] 进 Java，不直接调业务 Controller。
 * 按 `what`（消息号）找到已 [registerMsgHandler] 的 Handler，[Handler.sendMessage] 切到该 Handler 的 Looper。
 *
 * 进程单例 Controller：构造时 register 一次，进程内不 unregister。
 * 会随页面开关的组件（地图、引导）才 unregister。
 */
object VMsgDispatcher {

    private val handlerMap = HashMap<Int, ArrayList<WeakReference<Handler>>>()

    fun registerMsgHandler(handler: MsgHandler) {
        registerMsgHandler(handler, handler.getInterests(), addHead = false)
    }

    fun registerMsgHandler(handler: Handler, observedMsgSet: Collection<Int>, addHead: Boolean) {
        for (msgId in observedMsgSet) {
            if (msgId < 0) continue
            synchronized(handlerMap) {
                val handlers = handlerMap.getOrPut(msgId) { ArrayList() }
                if (handlers.any { it.get() === handler }) return@synchronized
                if (addHead) {
                    handlers.add(0, WeakReference(handler))
                } else {
                    handlers.add(WeakReference(handler))
                }
            }
        }
    }

    fun unregisterMsgHandler(handler: MsgHandler) {
        unregisterMsgHandler(handler, handler.getInterests())
    }

    fun unregisterMsgHandler(handler: Handler, observedMsgSet: Collection<Int>) {
        for (msgId in observedMsgSet) {
            if (msgId < 0) continue
            synchronized(handlerMap) {
                val handlers = handlerMap[msgId] ?: return@synchronized
                val index = handlers.indexOfFirst { it.get() === handler }
                if (index >= 0) {
                    handlers.removeAt(index)
                }
                if (handlers.isEmpty()) {
                    handlerMap.remove(msgId)
                }
            }
        }
    }

    /**
     * JNI `CallStaticVoidMethod` 入口，必须 `@JvmStatic`。
     *
     * @param what 消息号，与 [VMsgConstant] / `vmsg_constant.h` 一致
     * @param errorCode 引擎错误码，进 `Message.arg1`
     * @param arg2 扩展 int，进 `Message.arg2`
     * @param obj 通常是 `Bundle`，进 `Message.obj`
     */
    @JvmStatic
    fun dispatchMessage(what: Int, errorCode: Int, arg2: Int, obj: Any?) {
        if (what < 0) return
        synchronized(handlerMap) {
            val handlers = handlerMap[what] ?: return
            val removeList = ArrayList<WeakReference<Handler>>()
            for (ref in handlers) {
                val handler = ref.get()
                if (handler != null) {
                    try {
                        val message: Message = handler.obtainMessage(what, errorCode, arg2, obj)
                        handler.sendMessage(message)
                    } catch (_: Exception) {
                    }
                } else {
                    removeList.add(ref)
                }
            }
            if (removeList.isNotEmpty()) {
                handlers.removeAll(removeList.toSet())
            }
        }
    }
}
