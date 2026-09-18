package com.bingo.smartna

import com.bingo.coresdk.CoreSdk
import com.bingo.smartna.base.BaseApplication

class SmartNaApplication : BaseApplication() {

    override fun onCreate() {
        super.onCreate()
        // 进程里只做一次 loadLibrary；消息 Handler 由各业务单例自己挂
        CoreSdk.init()
        initRetrofit(BASE_URL, debug = BuildConfig.DEBUG)
    }

    companion object {
        const val BASE_URL = "https://jsonplaceholder.typicode.com/"
    }
}
