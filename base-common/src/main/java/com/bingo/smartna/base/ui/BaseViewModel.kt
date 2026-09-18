package com.bingo.smartna.base.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/**
 * ViewModel 基类：loading、协程启动，并作为页面 [DefaultLifecycleObserver]。
 *
 * 注意：ViewModel 在配置变更后仍然存活，[onCreate] 等会随新页面再走一遍，
 * 不要把只应执行一次的初始化写在生命周期回调里。
 */
open class BaseViewModel(application: Application) :
    AndroidViewModel(application),
    DefaultLifecycleObserver {

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    private var loadingCount = 0

    protected fun launch(
        showLoading: Boolean = false,
        block: suspend () -> Unit
    ) {
        viewModelScope.launch {
            if (showLoading) incrementLoading()
            try {
                block()
            } finally {
                if (showLoading) decrementLoading()
            }
        }
    }

    private fun incrementLoading() {
        loadingCount++
        if (loadingCount == 1) {
            _loading.value = true
        }
    }

    private fun decrementLoading() {
        loadingCount = (loadingCount - 1).coerceAtLeast(0)
        if (loadingCount == 0) {
            _loading.value = false
        }
    }

    override fun onCreate(owner: LifecycleOwner) {}

    override fun onStart(owner: LifecycleOwner) {}

    override fun onResume(owner: LifecycleOwner) {}

    override fun onPause(owner: LifecycleOwner) {}

    override fun onStop(owner: LifecycleOwner) {}

    override fun onDestroy(owner: LifecycleOwner) {}
}
