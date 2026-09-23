package com.bingo.smartna.collector.data.mock

import com.bingo.smartna.collector.data.model.HallCategory
import com.bingo.smartna.collector.data.model.Task
import com.bingo.smartna.collector.data.model.TaskKind
import com.bingo.smartna.collector.data.model.TaskPriority
import com.bingo.smartna.collector.data.model.TeamTaskRow
import com.bingo.smartna.collector.data.model.TeamTaskTab
import com.bingo.smartna.collector.hall.CrowdDurations
import com.bingo.smartna.collector.hall.CrowdScenes

/** 任务大厅 / 小组任务 Mock。场景分类对齐《外采场景分类体系 V2.1》。 */
object MockDataSource {

    private val seedTasks: List<Task> = listOf(
        Task(
            id = "t_demo",
            title = "【Demo】家政收纳整理 Ego 采集",
            scene = "生活服务场景-家政",
            device = "MEgo",
            settle = "按有效采集时长结算",
            duration = "30~90分钟",
            quotaTotal = 999,
            reward = 14.0,
            category = HallCategory.LIFE,
            kind = TaskKind.CUSTOM,
            targetClips = 2,
            doneClips = 0,
            deadline = "12.31",
            priority = TaskPriority.HIGH,
            durationMax = 90
        ),
        Task(
            id = "t1",
            title = "住宅小区 | 收纳整理定制采集",
            scene = "居住场景-住宅小区",
            device = "MEgo",
            settle = "按有效采集时长结算",
            duration = "30~90分钟",
            quotaTotal = 19,
            reward = 8.50,
            category = HallCategory.LIFE,
            kind = TaskKind.CUSTOM,
            targetClips = 20,
            doneClips = 0,
            deadline = "9.30",
            priority = TaskPriority.HIGH,
            durationMax = 90
        ),
        Task(
            id = "t2",
            title = "家政 | 日常保洁自由采集",
            scene = "生活服务场景-家政",
            device = "MEgo",
            settle = "按有效采集时长结算",
            duration = "30~90分钟",
            quotaTotal = 198,
            reward = 7.50,
            category = HallCategory.LIFE,
            kind = TaskKind.FREE,
            targetClips = 0,
            doneClips = 12,
            deadline = "10.15",
            priority = TaskPriority.UNLIMITED,
            durationMax = 90
        ),
        Task(
            id = "t3",
            title = "流水线车间 | 装配组装",
            scene = "生产制造场景-流水线车间",
            device = "UMI",
            settle = "按有效采集时长结算",
            duration = "30~90分钟",
            quotaTotal = 36,
            reward = 9.80,
            category = HallCategory.PRODUCE,
            kind = TaskKind.CUSTOM,
            targetClips = 30,
            doneClips = 8,
            deadline = "9.28",
            priority = TaskPriority.IN_PROGRESS,
            durationMax = 90
        ),
        Task(
            id = "t4",
            title = "质检包装 | 包装贴标自由采集",
            scene = "生产制造场景-质检包装",
            device = "MEgo",
            settle = "按有效采集时长结算",
            duration = "10~30分钟",
            quotaTotal = 195,
            reward = 8.20,
            category = HallCategory.PRODUCE,
            kind = TaskKind.FREE,
            targetClips = 0,
            doneClips = 4,
            deadline = "10.20",
            priority = TaskPriority.UNLIMITED,
            durationMax = 30
        ),
        Task(
            id = "t5",
            title = "果园 | 果园采摘动作采集",
            scene = "农业生产场景-果园",
            device = "MEgo",
            settle = "按有效采集时长结算",
            duration = "30~90分钟",
            quotaTotal = 24,
            reward = 7.20,
            category = HallCategory.AGRI,
            kind = TaskKind.CUSTOM,
            targetClips = 16,
            doneClips = 3,
            deadline = "10.08",
            priority = TaskPriority.HIGH,
            durationMax = 90
        ),
        Task(
            id = "t6",
            title = "文娱休闲 | KTV 包厢服务",
            scene = "生活服务场景-文娱休闲",
            device = "UMI",
            settle = "按有效采集时长结算",
            duration = "30~90分钟",
            quotaTotal = 42,
            reward = 6.80,
            category = HallCategory.ENTERTAIN,
            kind = TaskKind.FREE,
            targetClips = 0,
            doneClips = 6,
            deadline = "10.12",
            priority = TaskPriority.UNLIMITED,
            durationMax = 90
        ),
        Task(
            id = "t7",
            title = "仓库 | 入库验收定制采集",
            scene = "物流仓储场景-仓库",
            device = "MEgo",
            settle = "按有效采集时长结算",
            duration = "30~90分钟",
            quotaTotal = 18,
            reward = 9.20,
            category = HallCategory.WAREHOUSE,
            kind = TaskKind.CUSTOM,
            targetClips = 25,
            doneClips = 10,
            deadline = "9.26",
            priority = TaskPriority.IN_PROGRESS,
            durationMax = 90
        ),
        Task(
            id = "t8",
            title = "中餐 | 热菜烹饪",
            scene = "餐饮场景-中餐",
            device = "MEgo",
            settle = "按有效采集时长结算",
            duration = "30~90分钟",
            quotaTotal = 22,
            reward = 8.80,
            category = HallCategory.CATERING,
            kind = TaskKind.CUSTOM,
            targetClips = 18,
            doneClips = 0,
            deadline = "10.02",
            priority = TaskPriority.HIGH,
            durationMax = 90
        ),
        Task(
            id = "t9",
            title = "公寓管理 | 快递代收",
            scene = "居住场景-公寓管理",
            device = "UMI",
            settle = "按有效采集时长结算",
            duration = "30~90分钟",
            quotaTotal = 42,
            reward = 9.20,
            category = HallCategory.LIFE,
            kind = TaskKind.FREE,
            targetClips = 0,
            doneClips = 0,
            deadline = "10.18",
            priority = TaskPriority.UNLIMITED,
            durationMax = 90
        ),
        Task(
            id = "t10",
            title = "超市 | 理货补货",
            scene = "零售购物场景-超市",
            device = "MEgo",
            settle = "按有效采集时长结算",
            duration = "30~90分钟",
            quotaTotal = 19,
            reward = 8.50,
            category = HallCategory.LIFE,
            kind = TaskKind.CUSTOM,
            targetClips = 20,
            doneClips = 0,
            deadline = "9.30",
            priority = TaskPriority.HIGH,
            durationMax = 90
        ),
        Task(
            id = "t11",
            title = "个人护理 | 发型洗吹",
            scene = "生活服务场景-个人护理",
            device = "MEgo",
            settle = "按有效采集时长结算",
            duration = "10~30分钟",
            quotaTotal = 20,
            reward = 7.80,
            category = HallCategory.LIFE,
            kind = TaskKind.CUSTOM,
            targetClips = 16,
            doneClips = 0,
            deadline = "10.05",
            priority = TaskPriority.HIGH,
            durationMax = 30
        ),
        Task(
            id = "t12",
            title = "宠物服务 | 宠物洗澡吹干",
            scene = "生活服务场景-宠物服务",
            device = "MEgo",
            settle = "按有效采集时长结算",
            duration = "30~90分钟",
            quotaTotal = 198,
            reward = 7.50,
            category = HallCategory.LIFE,
            kind = TaskKind.FREE,
            targetClips = 0,
            doneClips = 0,
            deadline = "10.20",
            priority = TaskPriority.UNLIMITED,
            durationMax = 90
        ),
        Task(
            id = "t13",
            title = "末端驿站 | 入库扫描",
            scene = "物流仓储场景-末端驿站",
            device = "MEgo",
            settle = "按有效采集时长结算",
            duration = "30~90分钟",
            quotaTotal = 20,
            reward = 6.60,
            category = HallCategory.WAREHOUSE,
            kind = TaskKind.CUSTOM,
            targetClips = 12,
            doneClips = 0,
            deadline = "10.12",
            priority = TaskPriority.IN_PROGRESS,
            durationMax = 90
        ),
        Task(
            id = "t14",
            title = "制药工厂 | 压片",
            scene = "医疗医药场景-制药工厂",
            device = "MEgo",
            settle = "按有效采集时长结算",
            duration = "10~30分钟",
            quotaTotal = 1000,
            reward = 14.0,
            category = HallCategory.LIFE,
            kind = TaskKind.FREE,
            targetClips = 0,
            doneClips = 0,
            deadline = "10.30",
            priority = TaskPriority.UNLIMITED,
            durationMax = 30
        ),
        Task(
            id = "t15",
            title = "大学 | 实验操作",
            scene = "教育场景-大学",
            device = "MEgo",
            settle = "按有效采集时长结算",
            duration = "30~90分钟",
            quotaTotal = 300,
            reward = 14.0,
            category = HallCategory.LIFE,
            kind = TaskKind.FREE,
            targetClips = 0,
            doneClips = 0,
            deadline = "10.30",
            priority = TaskPriority.UNLIMITED,
            durationMax = 90
        ),
        Task(
            id = "t16",
            title = "咖啡 | 咖啡制作",
            scene = "餐饮场景-咖啡",
            device = "MEgo",
            settle = "按有效采集时长结算",
            duration = "5~10分钟",
            quotaTotal = 300,
            reward = 14.0,
            category = HallCategory.CATERING,
            kind = TaskKind.FREE,
            targetClips = 0,
            doneClips = 0,
            deadline = "10.30",
            priority = TaskPriority.UNLIMITED,
            durationMax = 10
        ),
        Task(
            id = "t17",
            title = "充电站 | 插拔枪充电",
            scene = "交通出行场景-充电站",
            device = "MEgo",
            settle = "按有效采集时长结算",
            duration = "5~10分钟",
            quotaTotal = 300,
            reward = 14.0,
            category = HallCategory.LIFE,
            kind = TaskKind.FREE,
            targetClips = 0,
            doneClips = 0,
            deadline = "10.30",
            priority = TaskPriority.UNLIMITED,
            durationMax = 10
        ),
        Task(
            id = "t18",
            title = "汽车服务 | 保养维修",
            scene = "户外与公共场景-汽车服务",
            device = "MEgo",
            settle = "按有效采集时长结算",
            duration = "30~90分钟",
            quotaTotal = 80,
            reward = 12.0,
            category = HallCategory.ENTERTAIN,
            kind = TaskKind.CUSTOM,
            targetClips = 15,
            doneClips = 0,
            deadline = "10.25",
            priority = TaskPriority.HIGH,
            durationMax = 90
        )
    )

    val tasks: List<Task> = seedTasks + generatedTasks()

    val allTasks: List<Task> = tasks

    val teamTasks: List<TeamTaskRow> = listOf(
        TeamTaskRow("g1", "石家庄1区 收纳整理", "待领取", "定制 · 高优先级", TeamTaskTab.ASSIGNED),
        TeamTaskRow("g2", "石家庄1区 仓库验收", "待领取", "定制 · 进行中", TeamTaskTab.ASSIGNED),
        TeamTaskRow("g3", "流水线装配组装", "张三 · 1区28号", "已领 8/30 段", TeamTaskTab.CLAIMED),
        TeamTaskRow("g4", "中餐热菜烹饪", "李四 · 1区16号", "已领 0/18 段", TeamTaskTab.CLAIMED),
        TeamTaskRow("g5", "果园采摘动作采集", "王五 · 申请数采员", "石家庄1区28号", TeamTaskTab.APPLIED),
        TeamTaskRow("g6", "申请成为小组长", "赵六 · 申请小组长", "石家庄1区", TeamTaskTab.APPLIED)
    )

    private fun generatedTasks(): List<Task> {
        val result = mutableListOf<Task>()
        var seq = 0
        CrowdScenes.groups.forEach { group ->
            group.children.forEach { child ->
                CrowdDurations.buckets.forEach { bucket ->
                    seq++
                    val reward = 8.0 + (seq % 15) * 0.6
                    val quota = 80 + (seq % 9) * 40
                    result += Task(
                        id = "gen_${group.id}_${child}_${bucket.max}_$seq",
                        title = sampleTitle(group.name, child, bucket.label),
                        scene = "${group.name}-$child",
                        device = if (seq % 2 == 0) "MEgo" else "UMI",
                        settle = "按有效采集时长结算",
                        duration = bucket.label,
                        quotaTotal = quota,
                        reward = reward,
                        category = categoryOf(group.name),
                        kind = if (seq % 3 == 0) TaskKind.CUSTOM else TaskKind.FREE,
                        targetClips = if (seq % 3 == 0) 20 else 0,
                        doneClips = 0,
                        deadline = "10.${(seq % 28) + 1}",
                        priority = when (seq % 3) {
                            0 -> TaskPriority.HIGH
                            1 -> TaskPriority.IN_PROGRESS
                            else -> TaskPriority.UNLIMITED
                        },
                        durationMax = bucket.max
                    )
                }
            }
        }
        return result
    }

    private fun sampleTitle(groupName: String, child: String, durationLabel: String): String {
        return when (groupName) {
            "餐饮场景" -> "$child | 后厨/前厅操作 $durationLabel"
            "生产制造场景" -> "$child | 产线操作 $durationLabel"
            "物流仓储场景" -> "$child | 仓储作业 $durationLabel"
            "农业生产场景" -> "$child | 农作采集 $durationLabel"
            else -> "$child | ${groupName.replace("场景", "")} $durationLabel"
        }
    }

    private fun categoryOf(groupName: String): HallCategory {
        return when (groupName) {
            "生产制造场景" -> HallCategory.PRODUCE
            "餐饮场景" -> HallCategory.CATERING
            "物流仓储场景" -> HallCategory.WAREHOUSE
            "农业生产场景" -> HallCategory.AGRI
            "户外与公共场景", "生活服务场景" -> HallCategory.ENTERTAIN
            else -> HallCategory.LIFE
        }
    }
}
