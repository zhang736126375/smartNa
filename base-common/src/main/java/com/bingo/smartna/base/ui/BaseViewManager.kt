package com.bingo.smartna.base.ui

import android.view.View
import android.view.ViewGroup
import androidx.annotation.IdRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * 一块业务 UI 的管理器，本身不是 View。
 *
 * 只绑 **Fragment**（对标百度 `LifecycleBaseViewManager(fragment)`）。
 * 槽在 Fragment 的布局里找；不单独存 Activity。
 *
 * 子类命名 XxxView，配 XxxViewModel。LiveData 按「还活着的范围」选下面三个方法之一：
 *
 * | 方法 | 跟谁 | 面板 hide | 页面 onDestroyView |
 * | [observePage] | Fragment | 仍收 | 仍收（Fragment 还在） |
 * | [observePageView] | 页面布局 viewLifecycleOwner | 仍收 | 停 |
 * | [observePanel] | 本面板 | 停 | 停 |
 *
 * 改本面板 TextView → 只用 [observePanel]。
 * VM 通知 show/hide → 用 [observePage]。
 */
abstract class BaseViewManager : DefaultLifecycleObserver, LifecycleOwner {

    enum class Status {
        HIDDEN,
        SHOWN,
        DISPOSED
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private var boundOwner: LifecycleOwner? = null
    private var hostFragment: Fragment? = null
    private var needLoad = true
    private var pendingShow = false
    private var containerRef: ViewGroup? = null
    private var panelViewOwner: ViewLifecycleOwner? = null

    protected var parentId: Int = View.NO_ID
        private set

    /** 页面上的槽，对标百度 `mRootViewGroup`。[onViewLoad] 之后可用。 */
    protected val container: ViewGroup
        get() = containerRef ?: error("${javaClass.simpleName} 槽位未绑定，parentId=$parentId")

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    var status: Status = Status.HIDDEN
        private set

    constructor(fragment: Fragment, @IdRes parentId: Int = View.NO_ID) {
        this.parentId = parentId
        hostFragment = fragment
        fragment.viewLifecycleOwnerLiveData.observe(fragment) { owner ->
            if (owner != null) {
                attachTo(owner)
                if (pendingShow) {
                    pendingShow = false
                    show()
                }
            }
        }
        val register = {
            if (status != Status.DISPOSED) observePage(fragment)
        }
        fragment.view?.post(register) ?: register()
    }

    val isShowing: Boolean
        get() = status == Status.SHOWN

    fun show() {
        if (status == Status.DISPOSED) return
        if (boundOwner == null) {
            pendingShow = true
            return
        }
        if (status == Status.SHOWN) return
        if (needLoad) {
            needLoad = false
            bindContainer()
            onViewLoad()
        }
        moveToResumed()
        bindPanelViewOwner()
        onViewVisibleSetting()
        onViewResume()
        onViewShow()
        status = Status.SHOWN
    }

    fun hide() {
        if (status != Status.SHOWN) return
        moveToStarted()
        onViewPause()
        onViewHide()
        clearPanelViewOwner()
        onViewHideSetting()
        status = Status.HIDDEN
    }

    fun dispose() {
        if (status == Status.DISPOSED) return
        if (status == Status.SHOWN) {
            moveToStarted()
            onViewHideSetting()
        }
        onViewDestroy()
        moveToDestroyed()
        containerRef = null
        detach()
        hostFragment = null
        status = Status.DISPOSED
        needLoad = true
    }

    override fun onResume(owner: LifecycleOwner) {
        if (owner === boundOwner && status == Status.SHOWN) {
            moveToResumed()
            onViewResume()
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        if (owner === boundOwner && status == Status.SHOWN) {
            moveToStarted()
            onViewPause()
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        if (owner !== boundOwner) return
        if (status == Status.SHOWN) {
            moveToStarted()
            onViewHideSetting()
            status = Status.HIDDEN
        }
        onViewDestroy()
        clearPanelViewOwner()
        containerRef = null
        needLoad = true
        detach()
    }

    protected open fun onViewLoad() {
        initFindView()
        initViewListener()
        boundOwner?.let { observePageView(it) }
    }

    protected open fun initFindView() {}

    protected open fun initViewListener() {}

    /**
     * 对标百度 `registerFragmentOwnerObserver`。
     * [owner] = Fragment，跟页面实例，不跟这块面板。
     * 面板 hide 了也会回调。用来听「该 show / hide 了」，不要改本面板控件。
     */
    protected open fun observePage(owner: LifecycleOwner) {}

    /**
     * 对标百度 `registerFragmentViewOwnerObserver`。
     * [owner] = 页面 viewLifecycleOwner，跟布局，不跟这块面板。
     * 面板 hide 了仍回调；Fragment.onDestroyView 后停止。
     */
    protected open fun observePageView(owner: LifecycleOwner) {}

    /**
     * 对标百度 `registerViewOwnerObserver`。
     * [owner] = 本面板。show 时挂上，hide 时 DESTROY。
     * **改本面板 TextView 的 LiveData 只在这里 observe。**
     * 再 show 会重新订阅，sticky 补上隐藏期间的新值。
     */
    protected open fun observePanel(owner: LifecycleOwner) {}

    protected open fun onViewVisibleSetting() {
        container.visibility = View.VISIBLE
    }

    protected open fun onViewHideSetting() {
        containerRef?.visibility = View.GONE
    }

    protected open fun onViewShow() {}

    protected open fun onViewHide() {}

    protected open fun onViewResume() {}

    protected open fun onViewPause() {}

    protected open fun onViewDestroy() {}

    private fun bindPanelViewOwner() {
        if (panelViewOwner != null) return
        val owner = ViewLifecycleOwner(container)
        panelViewOwner = owner
        lifecycle.addObserver(owner)
        observePanel(owner)
    }

    private fun clearPanelViewOwner() {
        panelViewOwner?.let { owner ->
            lifecycle.removeObserver(owner)
            owner.destroy()
        }
        panelViewOwner = null
    }

    private fun bindContainer() {
        val host = hostFragment?.view
            ?: error("${javaClass.simpleName} 页面 View 不存在")
        val found = if (parentId != View.NO_ID) {
            host.findViewById<View>(parentId)
                ?: error("${javaClass.simpleName} 找不到槽位 parentId=$parentId")
        } else {
            host
        }
        containerRef = found as? ViewGroup
            ?: error("${javaClass.simpleName} 槽位不是 ViewGroup: parentId=$parentId")
    }

    private fun attachTo(owner: LifecycleOwner) {
        if (boundOwner === owner) return
        boundOwner?.lifecycle?.removeObserver(this)
        boundOwner = owner
        owner.lifecycle.addObserver(this)
        needLoad = true
    }

    private fun detach() {
        boundOwner?.lifecycle?.removeObserver(this)
        boundOwner = null
    }

    private fun moveToResumed() {
        when (lifecycleRegistry.currentState) {
            Lifecycle.State.INITIALIZED -> {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
            Lifecycle.State.CREATED -> {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
            Lifecycle.State.STARTED -> {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
            else -> Unit
        }
    }

    private fun moveToStarted() {
        if (lifecycleRegistry.currentState == Lifecycle.State.RESUMED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
    }

    private fun moveToDestroyed() {
        clearPanelViewOwner()
        when {
            lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED) -> {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
            lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED) -> {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
            lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.CREATED) -> {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
        }
    }
}
