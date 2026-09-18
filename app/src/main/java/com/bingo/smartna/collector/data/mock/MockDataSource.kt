package com.bingo.smartna.collector.data.mock

import com.bingo.smartna.collector.data.model.Task

/** 任务大厅 Mock 数据。后续接真接口时只换仓库实现。 */
object MockDataSource {

    val tasks: List<Task> = listOf(
        Task(
            id = "t1",
            title = "MEgo丨View 头摄+Gripper夹爪",
            scene = "居住类-住宅",
            settle = "按有效采集时长结算",
            duration = "2-60分钟",
            quotaTotal = 19,
            reward = 8.50
        ),
        Task(
            id = "t2",
            title = "MEgo丨View 头摄+腕摄",
            scene = "居住类-住宅",
            settle = "按有效采集时长结算",
            duration = "2-60分钟",
            quotaTotal = 20,
            reward = 7.80
        ),
        Task(
            id = "t3",
            title = "MEgo丨Gripper 夹爪",
            scene = "居住类-住宅",
            settle = "按有效采集时长结算",
            duration = "2-60分钟",
            quotaTotal = 20,
            reward = 6.60
        ),
        Task(
            id = "t4",
            title = "MEgo丨View 头摄",
            scene = "居住类-住宅",
            settle = "按有效采集时长结算",
            duration = "2-60分钟",
            quotaTotal = 18,
            reward = 6.00
        ),
        Task(
            id = "t5",
            title = "洗碗质检 9.17",
            scene = "生产类-工厂车间",
            settle = "按有效采集时长结算",
            duration = "2-60分钟",
            quotaTotal = 195,
            reward = 8.20
        ),
        Task(
            id = "t6",
            title = "住宅丨宠物喂养_9.17",
            scene = "居住类-住宅",
            settle = "按有效采集时长结算",
            duration = "2-60分钟",
            quotaTotal = 198,
            reward = 7.50
        )
    )
}
