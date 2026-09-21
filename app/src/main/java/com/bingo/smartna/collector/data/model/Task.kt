package com.bingo.smartna.collector.data.model

import com.bingo.smartna.R

/** 大厅六分类。 */
enum class HallCategory(val id: String, val titleRes: Int, val bgRes: Int) {
    LIFE("life", R.string.hall_category_life, R.drawable.bg_hall_category_life),
    PRODUCE("produce", R.string.hall_category_produce, R.drawable.bg_hall_category_produce),
    AGRI("agri", R.string.hall_category_agri, R.drawable.bg_hall_category_agri),
    ENTERTAIN("entertain", R.string.hall_category_entertain, R.drawable.bg_hall_category_entertain),
    WAREHOUSE("warehouse", R.string.hall_category_warehouse, R.drawable.bg_hall_category_warehouse),
    CATERING("catering", R.string.hall_category_catering, R.drawable.bg_hall_category_catering);

    fun matches(task: Task): Boolean = task.category == this

    fun titleResFor(role: UserRole): Int {
        return if (role == UserRole.CROWD && this == PRODUCE) R.string.hall_category_factory else titleRes
    }

    fun bgResFor(role: UserRole): Int {
        return if (role == UserRole.CROWD && this == PRODUCE) R.drawable.bg_hall_category_factory else bgRes
    }

    companion object {
        fun fromId(id: String): HallCategory = entries.find { it.id == id } ?: LIFE

        fun homeEntries(role: UserRole): List<HallCategory> {
            return if (role == UserRole.CROWD) listOf(LIFE, PRODUCE) else entries
        }
    }
}

enum class TaskKind {
    CUSTOM,
    FREE;

    val label: String
        get() = when (this) {
            CUSTOM -> "定制"
            FREE -> "自由"
        }
}

enum class TaskPriority {
    HIGH,
    IN_PROGRESS,
    UNLIMITED;

    val label: String
        get() = when (this) {
            HIGH -> "高优先级"
            IN_PROGRESS -> "进行中"
            UNLIMITED -> "不限量"
        }
}

/** 任务大厅中的任务 */
data class Task(
    val id: String,
    val title: String,
    val scene: String,
    val device: String,
    val settle: String,
    val duration: String,
    val quotaTotal: Int,
    val reward: Double,
    val category: HallCategory,
    val kind: TaskKind,
    val targetClips: Int,
    val doneClips: Int,
    val deadline: String,
    val priority: TaskPriority
)

/** 用户领取后的任务状态：进行中 → 审核中 → 已完成 */
enum class TaskStatus {
    IN_PROGRESS,
    REVIEWING,
    DONE;

    val label: String
        get() = when (this) {
            IN_PROGRESS -> "进行中"
            REVIEWING -> "审核中"
            DONE -> "已完成"
        }
}

data class UserTask(
    val task: Task,
    val status: TaskStatus,
    val claimedAt: Long
)

data class WalletEntry(
    val title: String,
    val amount: Double,
    val time: Long
)

enum class TeamTaskTab {
    ASSIGNED,
    CLAIMED,
    APPLIED
}

/** 小组长三页签里的只读任务行。 */
data class TeamTaskRow(
    val id: String,
    val title: String,
    val member: String,
    val extra: String,
    val tab: TeamTaskTab
)
