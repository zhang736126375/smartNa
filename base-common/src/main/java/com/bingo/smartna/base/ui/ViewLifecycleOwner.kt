package com.bingo.smartna.base.ui

import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * 一块面板 View 自己的 LifecycleOwner，对标百度 `ViewLifecycleOwner`。
 *
 * - 挂到窗口 → RESUMED，LiveData 会分发
 * - 从窗口卸下、或面板 [BaseViewManager.hide] → DESTROYED，LiveData 卸观察
 * - 同时跟 Manager 自己的 Lifecycle：页面进后台时随 Manager pause
 */
internal class ViewLifecycleOwner(private val view: View) : LifecycleOwner, DefaultLifecycleObserver {

    private val registry = LifecycleRegistry(this)

    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            moveToResumed()
        }

        override fun onViewDetachedFromWindow(v: View) {
            destroy()
        }
    }

    override val lifecycle: Lifecycle
        get() = registry

    init {
        if (view.isAttachedToWindow) {
            moveToResumed()
        }
        view.addOnAttachStateChangeListener(attachListener)
    }

    fun destroy() {
        if (registry.currentState == Lifecycle.State.DESTROYED ||
            registry.currentState == Lifecycle.State.INITIALIZED
        ) {
            view.removeOnAttachStateChangeListener(attachListener)
            return
        }
        when {
            registry.currentState.isAtLeast(Lifecycle.State.RESUMED) -> {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
            registry.currentState.isAtLeast(Lifecycle.State.STARTED) -> {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
            registry.currentState.isAtLeast(Lifecycle.State.CREATED) -> {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
        }
        view.removeOnAttachStateChangeListener(attachListener)
    }

    override fun onPause(owner: LifecycleOwner) {
        if (registry.currentState == Lifecycle.State.RESUMED) {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        if (registry.currentState == Lifecycle.State.STARTED) {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        destroy()
    }

    private fun moveToResumed() {
        when (registry.currentState) {
            Lifecycle.State.INITIALIZED -> {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
            Lifecycle.State.CREATED -> {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
            Lifecycle.State.STARTED -> {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
            else -> Unit
        }
    }
}
