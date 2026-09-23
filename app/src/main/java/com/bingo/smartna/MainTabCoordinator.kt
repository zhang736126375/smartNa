package com.bingo.smartna

/** 跨 Activity 传递 MainActivity 目标 Tab（如任务详情页跳转设备 Tab）。 */
object MainTabCoordinator {
    @Volatile
    private var pendingTabId: Int? = null

    fun request(tabId: Int) {
        pendingTabId = tabId
    }

    fun consume(): Int? {
        val tabId = pendingTabId
        pendingTabId = null
        return tabId
    }

    fun clear() {
        pendingTabId = null
    }
}
