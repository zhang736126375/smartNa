package com.bingo.smartna.base

import android.app.Application
import com.bingo.smartna.base.http.RetrofitClient
import com.blankj.utilcode.util.Utils

/**
 * Application 基类：初始化 UtilCodeX，子类在 [onCreate] 中调用 [initRetrofit] 配置网络。
 */
open class BaseApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Utils.init(this)
    }

    protected fun initRetrofit(baseUrl: String, debug: Boolean = true) {
        RetrofitClient.init(baseUrl, debug)
    }

    companion object {
        lateinit var instance: BaseApplication
            private set
    }
}
