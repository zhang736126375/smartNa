package com.bingo.coresdk

/**
 * coresdk 入口。Application.onCreate 调一次 [init]，只 load so。
 * 业务单例（如 RoadConditionController）第一次被用到时自己 register Handler。
 */
object CoreSdk {
    @Volatile
    private var initialized = false

    @JvmStatic
    fun init() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            System.loadLibrary("core")
            System.loadLibrary("coresdk")
            initialized = true
        }
    }
}
