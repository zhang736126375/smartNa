package com.bingo.smartna

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.bingo.smartna.base.BaseApplication

class SmartNaApplication : BaseApplication(), ViewModelStoreOwner {

    private val appViewModelStore = ViewModelStore()

    override val viewModelStore: ViewModelStore
        get() = appViewModelStore

    override fun onCreate() {
        super.onCreate()
        preloadSharedCppRuntime()
        initRetrofit(BASE_URL, debug = BuildConfig.DEBUG)
    }

    /** 优先加载 app/jniLibs 中与 Orbbec SDK 匹配的 libc++。 */
    private fun preloadSharedCppRuntime() {
        try {
            System.loadLibrary("c++_shared")
        } catch (_: UnsatisfiedLinkError) {
            // 模拟器或无对应 ABI 时忽略，Orbbec 页会单独提示
        }
    }

    companion object {
        const val BASE_URL = "https://jsonplaceholder.typicode.com/"
    }
}
