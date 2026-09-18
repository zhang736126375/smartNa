package com.bingo.coresdk.route

/** 路况结果。在主线程回调。页面销毁时 [RoadConditionController.removeListener]。 */
fun interface IRoadConditionListener {
    fun onRoadConditionUpdate(
        errorCode: Int,
        taskId: Int,
        cityId: Int,
        roadName: String,
        nativeThread: String
    )
}
