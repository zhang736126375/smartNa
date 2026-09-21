package com.bingo.smartna.collector.data.mock

import com.bingo.smartna.collector.data.model.HallCategory
import com.bingo.smartna.collector.data.model.Task
import com.bingo.smartna.collector.data.model.TaskKind
import com.bingo.smartna.collector.data.model.TaskPriority
import com.bingo.smartna.collector.data.model.TeamTaskRow
import com.bingo.smartna.collector.data.model.TeamTaskTab

/** 任务大厅 / 小组任务 Mock。后续接真接口时只换仓库实现。 */
object MockDataSource {

    val tasks: List<Task> = listOf(
        Task(
            id = "t1",
            title = "住宅整理收纳 定制采集",
            scene = "居住类-住宅",
            device = "MEgo",
            settle = "按有效片段结算",
            duration = "2-60分钟",
            quotaTotal = 19,
            reward = 8.50,
            category = HallCategory.LIFE,
            kind = TaskKind.CUSTOM,
            targetClips = 20,
            doneClips = 0,
            deadline = "9.30",
            priority = TaskPriority.HIGH
        ),
        Task(
            id = "t2",
            title = "宠物喂养自由采集",
            scene = "居住类-住宅",
            device = "MEgo",
            settle = "按有效采集时长结算",
            duration = "2-60分钟",
            quotaTotal = 198,
            reward = 7.50,
            category = HallCategory.LIFE,
            kind = TaskKind.FREE,
            targetClips = 0,
            doneClips = 12,
            deadline = "10.15",
            priority = TaskPriority.UNLIMITED
        ),
        Task(
            id = "t3",
            title = "产线质检巡检",
            scene = "生产类-工厂车间",
            device = "UMI",
            settle = "按有效片段结算",
            duration = "2-60分钟",
            quotaTotal = 36,
            reward = 9.80,
            category = HallCategory.PRODUCE,
            kind = TaskKind.CUSTOM,
            targetClips = 30,
            doneClips = 8,
            deadline = "9.28",
            priority = TaskPriority.IN_PROGRESS
        ),
        Task(
            id = "t4",
            title = "车间巡检自由采集",
            scene = "生产类-工厂车间",
            device = "MEgo",
            settle = "按有效采集时长结算",
            duration = "2-60分钟",
            quotaTotal = 195,
            reward = 8.20,
            category = HallCategory.PRODUCE,
            kind = TaskKind.FREE,
            targetClips = 0,
            doneClips = 4,
            deadline = "10.20",
            priority = TaskPriority.UNLIMITED
        ),
        Task(
            id = "t5",
            title = "果园采摘动作采集",
            scene = "农业类-果园",
            device = "MEgo",
            settle = "按有效片段结算",
            duration = "2-60分钟",
            quotaTotal = 24,
            reward = 7.20,
            category = HallCategory.AGRI,
            kind = TaskKind.CUSTOM,
            targetClips = 16,
            doneClips = 3,
            deadline = "10.08",
            priority = TaskPriority.HIGH
        ),
        Task(
            id = "t6",
            title = "场馆巡检自由采集",
            scene = "文娱类-场馆",
            device = "UMI",
            settle = "按有效采集时长结算",
            duration = "2-60分钟",
            quotaTotal = 42,
            reward = 6.80,
            category = HallCategory.ENTERTAIN,
            kind = TaskKind.FREE,
            targetClips = 0,
            doneClips = 6,
            deadline = "10.12",
            priority = TaskPriority.UNLIMITED
        ),
        Task(
            id = "t7",
            title = "货架盘点定制采集",
            scene = "仓储类-仓库",
            device = "MEgo",
            settle = "按有效片段结算",
            duration = "2-60分钟",
            quotaTotal = 18,
            reward = 9.20,
            category = HallCategory.WAREHOUSE,
            kind = TaskKind.CUSTOM,
            targetClips = 25,
            doneClips = 10,
            deadline = "9.26",
            priority = TaskPriority.IN_PROGRESS
        ),
        Task(
            id = "t9",
            title = "UMI丨室内整理收纳",
            scene = "居住类-公寓",
            device = "UMI",
            settle = "按有效采集时长结算",
            duration = "2-60分钟",
            quotaTotal = 42,
            reward = 9.20,
            category = HallCategory.LIFE,
            kind = TaskKind.FREE,
            targetClips = 0,
            doneClips = 0,
            deadline = "10.18",
            priority = TaskPriority.UNLIMITED
        ),
        Task(
            id = "t10",
            title = "MEgo丨View 头摄+Gripper夹爪",
            scene = "居住类-住宅",
            device = "MEgo",
            settle = "按有效采集时长结算",
            duration = "2-60分钟",
            quotaTotal = 19,
            reward = 8.50,
            category = HallCategory.LIFE,
            kind = TaskKind.CUSTOM,
            targetClips = 20,
            doneClips = 0,
            deadline = "9.30",
            priority = TaskPriority.HIGH
        ),
        Task(
            id = "t11",
            title = "MEgo丨View 头摄+腕摄",
            scene = "居住类-住宅",
            device = "MEgo",
            settle = "按有效采集时长结算",
            duration = "2-60分钟",
            quotaTotal = 20,
            reward = 7.80,
            category = HallCategory.LIFE,
            kind = TaskKind.CUSTOM,
            targetClips = 16,
            doneClips = 0,
            deadline = "10.05",
            priority = TaskPriority.HIGH
        ),
        Task(
            id = "t12",
            title = "住宅丨宠物喂养_9.17",
            scene = "居住类-住宅",
            device = "MEgo",
            settle = "按有效采集时长结算",
            duration = "2-60分钟",
            quotaTotal = 198,
            reward = 7.50,
            category = HallCategory.LIFE,
            kind = TaskKind.FREE,
            targetClips = 0,
            doneClips = 0,
            deadline = "10.20",
            priority = TaskPriority.UNLIMITED
        ),
        Task(
            id = "t13",
            title = "MEgo丨Gripper 夹爪",
            scene = "居住类-住宅",
            device = "MEgo",
            settle = "按有效采集时长结算",
            duration = "2-60分钟",
            quotaTotal = 20,
            reward = 6.60,
            category = HallCategory.LIFE,
            kind = TaskKind.CUSTOM,
            targetClips = 12,
            doneClips = 0,
            deadline = "10.12",
            priority = TaskPriority.IN_PROGRESS
        ),
        Task(
            id = "t8",
            title = "后厨备餐动作采集",
            scene = "餐饮类-后厨",
            device = "MEgo",
            settle = "按有效片段结算",
            duration = "2-60分钟",
            quotaTotal = 22,
            reward = 8.80,
            category = HallCategory.CATERING,
            kind = TaskKind.CUSTOM,
            targetClips = 18,
            doneClips = 0,
            deadline = "10.02",
            priority = TaskPriority.HIGH
        )
    )

    val teamTasks: List<TeamTaskRow> = listOf(
        TeamTaskRow("g1", "石家庄1区 住宅整理", "待领取", "定制 · 高优先级", TeamTaskTab.ASSIGNED),
        TeamTaskRow("g2", "石家庄1区 货架盘点", "待领取", "定制 · 进行中", TeamTaskTab.ASSIGNED),
        TeamTaskRow("g3", "产线质检巡检", "张三 · 1区28号", "已领 8/30 段", TeamTaskTab.CLAIMED),
        TeamTaskRow("g4", "后厨备餐动作采集", "李四 · 1区16号", "已领 0/18 段", TeamTaskTab.CLAIMED),
        TeamTaskRow("g5", "果园采摘动作采集", "王五 · 申请数采员", "石家庄1区28号", TeamTaskTab.APPLIED),
        TeamTaskRow("g6", "申请成为小组长", "赵六 · 申请小组长", "石家庄1区", TeamTaskTab.APPLIED)
    )
}
