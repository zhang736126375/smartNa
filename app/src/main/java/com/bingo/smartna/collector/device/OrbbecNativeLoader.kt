package com.bingo.smartna.collector.device

import android.util.Log

/** 按 Orbbec SDK 要求预加载 libc++ 与 native 库，避免与 coresdk 的 STL 冲突。 */
object OrbbecNativeLoader {

    private const val TAG = "OrbbecNativeLoader"

    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        System.loadLibrary("c++_shared")
        System.loadLibrary("OrbbecSDK")
        System.loadLibrary("obsensor_jni")
        loaded = true
        Log.i(TAG, "Orbbec native libraries loaded")
    }
}
