package com.bingo.smartna.collector

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.bingo.smartna.base.ui.BaseViewModel
import com.bingo.smartna.collector.data.Prefs
import com.bingo.smartna.collector.data.mock.MockDataSource
import com.bingo.smartna.collector.data.model.Task
import com.bingo.smartna.collector.data.model.TaskStatus
import com.bingo.smartna.collector.data.model.UserTask
import com.bingo.smartna.collector.data.model.WalletEntry

data class CollectorUiState(
    val phone: String = "",
    val quotaLeft: Map<String, Int> = emptyMap(),
    val claimedIds: Set<String> = emptySet(),
    val userTasks: List<UserTask> = emptyList(),
    val walletBalance: Double = 0.0,
    val walletEntries: List<WalletEntry> = emptyList()
) {
    val maskedPhone: String
        get() = if (phone.length == 11) {
            phone.take(3) + "****" + phone.takeLast(4)
        } else {
            phone
        }

    val avatarLetter: String
        get() = phone.takeLast(4).firstOrNull()?.toString() ?: ""

    fun inProgressCount() = userTasks.count { it.status == TaskStatus.IN_PROGRESS }
    fun reviewingCount() = userTasks.count { it.status == TaskStatus.REVIEWING }
    fun doneCount() = userTasks.count { it.status == TaskStatus.DONE }
}

/**
 * 采集员主流程状态：登录、大厅领取、任务流转、钱包。
 * 数据先走 Mock，接口形状保持可替换。
 */
class CollectorViewModel(application: Application) : BaseViewModel(application) {

    private val prefs = Prefs(application)
    val hallTasks: List<Task> = MockDataSource.tasks

    private val quotaLeft = MockDataSource.tasks.associate { it.id to it.quotaTotal }.toMutableMap()
    private val claimedIds = linkedSetOf<String>()
    private val userTasks = mutableListOf<UserTask>()
    private val walletEntries = mutableListOf<WalletEntry>()
    private var walletBalance = 0.0
    private var phone = prefs.phone.orEmpty()

    private val _ui = MutableLiveData(buildState())
    val ui: LiveData<CollectorUiState> = _ui

    private val _loggedOut = MutableLiveData<Boolean>()
    val loggedOut: LiveData<Boolean> = _loggedOut

    fun claim(task: Task) {
        if (claimedIds.contains(task.id)) return
        val left = quotaLeft[task.id] ?: return
        if (left <= 0) return
        quotaLeft[task.id] = left - 1
        claimedIds.add(task.id)
        userTasks.add(UserTask(task, TaskStatus.IN_PROGRESS, System.currentTimeMillis()))
        emit()
    }

    fun submitForReview(userTask: UserTask) {
        replaceStatus(userTask, TaskStatus.REVIEWING)
    }

    fun approve(userTask: UserTask) {
        if (userTask.status != TaskStatus.REVIEWING) return
        val index = userTasks.indexOfFirst { it.task.id == userTask.task.id }
        if (index < 0) return
        userTasks[index] = userTasks[index].copy(status = TaskStatus.DONE)
        walletBalance += userTask.task.reward
        walletEntries.add(
            0,
            WalletEntry(
                title = userTask.task.title,
                amount = userTask.task.reward,
                time = System.currentTimeMillis()
            )
        )
        emit()
    }

    fun logout() {
        prefs.clearLogin()
        phone = ""
        userTasks.clear()
        claimedIds.clear()
        walletEntries.clear()
        walletBalance = 0.0
        MockDataSource.tasks.forEach { quotaLeft[it.id] = it.quotaTotal }
        emit()
        _loggedOut.value = true
    }

    private fun replaceStatus(userTask: UserTask, newStatus: TaskStatus) {
        val index = userTasks.indexOfFirst { it.task.id == userTask.task.id }
        if (index < 0) return
        userTasks[index] = userTasks[index].copy(status = newStatus)
        emit()
    }

    private fun emit() {
        _ui.value = buildState()
    }

    private fun buildState() = CollectorUiState(
        phone = phone,
        quotaLeft = quotaLeft.toMap(),
        claimedIds = claimedIds.toSet(),
        userTasks = userTasks.toList(),
        walletBalance = walletBalance,
        walletEntries = walletEntries.toList()
    )
}
