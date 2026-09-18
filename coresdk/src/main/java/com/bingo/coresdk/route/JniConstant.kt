package com.bingo.coresdk.route

/**
 * Native Bundle 字段名，必须与 `jni_road_condition.cpp` 里的 key 字符串一致。
 */
object JniConstant {
    const val ROAD_CONDITION_TASK_ID = "roadConditionTaskID"
    const val ROAD_CONDITION_CITY_ID = "roadConditionCityId"
    const val ROAD_CONDITION_ROAD_NAME = "roadConditionRoadName"
    const val ROAD_CONDITION_NATIVE_THREAD = "roadConditionNativeThread"
}
