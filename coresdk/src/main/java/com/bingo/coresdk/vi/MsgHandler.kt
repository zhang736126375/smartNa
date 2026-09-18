package com.bingo.coresdk.vi

import android.os.Handler
import android.os.Looper

/**
 * 引擎消息 Handler。对标百度 `com.baidu.mapautosdk.vi.MsgHandler`。
 *
 * 子类构造时会立刻调 [careAbout]，在里面 [observe] 本业务关心的消息号。
 * 进程级 SDK 单例用 `MsgHandler(Looper.getMainLooper())`，不要随 Activity 反复 register。
 */
abstract class MsgHandler : Handler {

    private val interests = HashSet<Int>()

    @Suppress("DEPRECATION")
    constructor() : super() {
        careAbout()
    }

    constructor(looper: Looper) : super(looper) {
        careAbout()
    }

    /** 声明关心哪些 [VMsgConstant]。只在这里 [observe]，不要放到别的生命周期里零散加。 */
    abstract fun careAbout()

    @Synchronized
    fun observe(msgId: Int) {
        interests.add(msgId)
    }

    @Synchronized
    fun ignore(msgId: Int): Boolean {
        return interests.remove(msgId)
    }

    @Synchronized
    fun isObserved(msgId: Int): Boolean {
        return interests.contains(msgId)
    }

    fun getInterests(): Set<Int> = interests
}
