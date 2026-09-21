package com.bingo.smartna.collector

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.bingo.smartna.base.ui.BaseViewModel
import com.bingo.smartna.collector.data.Prefs
import com.bingo.smartna.collector.data.mock.MockDataSource
import com.bingo.smartna.collector.data.model.Task
import com.bingo.smartna.collector.data.model.TaskStatus
import com.bingo.smartna.collector.data.model.TeamTaskRow
import com.bingo.smartna.collector.data.model.UserRole
import com.bingo.smartna.collector.data.model.UserTask
import com.bingo.smartna.collector.data.model.WalletEntry

data class CollectorUiState(
    val phone: String = "",
    val displayName: String = "",
    val role: UserRole = UserRole.CROWD,
    val staffUpgradePending: Boolean = false,
    val leadUpgradePending: Boolean = false,
    val quotaLeft: Map<String, Int> = emptyMap(),
    val claimedIds: Set<String> = emptySet(),
    val userTasks: List<UserTask> = emptyList(),
    val walletBalance: Double = 0.0,
    val walletEntries: List<WalletEntry> = emptyList()
) {
    val profileTitle: String
        get() = when {
            displayName.isNotBlank() && displayName != phone -> displayName
            phone.length == 11 -> phone.take(3) + "****" + phone.takeLast(4)
            displayName.isNotBlank() -> displayName
            else -> phone
        }

    val avatarLetter: String
        get() = when {
            displayName.isNotBlank() && displayName != phone -> displayName.first().toString()
            phone.isNotBlank() -> phone.takeLast(4).firstOrNull()?.toString().orEmpty()
            displayName.isNotBlank() -> displayName.first().toString()
            else -> ""
        }

    val maskedPhone: String
        get() = profileTitle

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
    val teamTasks: List<TeamTaskRow> = MockDataSource.teamTasks

    private val quotaLeft = MockDataSource.tasks.associate { it.id to it.quotaTotal }.toMutableMap()
    private val claimedIds = linkedSetOf<String>()
    private val userTasks = mutableListOf<UserTask>()
    private val walletEntries = mutableListOf<WalletEntry>()
    private var walletBalance = 0.0
    private var phone = prefs.phone.orEmpty()
    private var displayName = prefs.displayName
    private var role = prefs.role
    private var staffUpgradePending = prefs.staffUpgradePending
    private var leadUpgradePending = prefs.leadUpgradePending

    private val _ui = MutableLiveData(buildState())
    val ui: LiveData<CollectorUiState> = _ui

    private val _loggedOut = MutableLiveData<Boolean>()
    val loggedOut: LiveData<Boolean> = _loggedOut

    private val _upgraded = MutableLiveData<UserRole?>()
    val upgraded: LiveData<UserRole?> = _upgraded

    fun claim(task: Task) {
        if (claimedIds.contains(task.id)) return
        val left = quotaLeft[task.id] ?: return
        if (left <= 0) return
        quotaLeft[task.id] = left - 1
        claimedIds.add(task.id)
        userTasks.add(UserTask(task, TaskStatus.IN_PROGRESS, System.currentTimeMillis()))
        emit()
    }

    fun upgradeTo(target: UserRole, area: String) {
        if (role != UserRole.CROWD) return
        if (target != UserRole.STAFF && target != UserRole.LEAD) return
        launch(showLoading = true) {
            kotlinx.coroutines.delay(1000)
            prefs.upgradeTo(target, area)
            role = target
            displayName = area
            staffUpgradePending = false
            leadUpgradePending = false
            emit()
            _upgraded.value = target
        }
    }

    fun consumeUpgrade() {
        _upgraded.value = null
    }

    fun applyStaffUpgrade(area: String) {
        upgradeTo(UserRole.STAFF, area)
    }

    fun applyLeadUpgrade(area: String) {
        upgradeTo(UserRole.LEAD, area)
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
        displayName = ""
        role = UserRole.CROWD
        staffUpgradePending = false
        leadUpgradePending = false
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
        displayName = displayName,
        role = role,
        staffUpgradePending = staffUpgradePending,
        leadUpgradePending = leadUpgradePending,
        quotaLeft = quotaLeft.toMap(),
        claimedIds = claimedIds.toSet(),
        userTasks = userTasks.toList(),
        walletBalance = walletBalance,
        walletEntries = walletEntries.toList()
    )
}
