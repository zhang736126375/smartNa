package com.bingo.coresdk.route

/**
 * 路况查询结果。方式一由 [RoadConditionController.getRoadCondition] 当场返回；
 * 方式二由 [IRoadConditionListener] 回调里带同样字段。
 */
data class RoadConditionResult(
    val errorCode: Int,
    val taskId: Int,
    val cityId: Int,
    val roadName: String,
    val nativeThread: String
)
