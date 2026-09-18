package com.bingo.smartna.collector.data.model

/** 大厅第一层大分类：生活 / 工厂。后续加分类只加枚举项。 */
enum class HallCategory(val id: String) {
    LIFE("life"),
    FACTORY("factory");

    fun matches(task: Task): Boolean = of(task) == this

    companion object {
        fun fromId(id: String): HallCategory = entries.find { it.id == id } ?: LIFE

        fun of(task: Task): HallCategory = when {
            task.scene.startsWith("生产类") -> FACTORY
            else -> LIFE
        }
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
    val reward: Double
) {
    val category: HallCategory get() = HallCategory.of(this)
}

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
