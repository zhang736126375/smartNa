package com.bingo.coresdk.route

import android.os.Bundle

/**
 * JNI 声明。实现在 `jni_road_condition.cpp`。
 * 库已由 [com.bingo.coresdk.CoreSdk.init] 加载，这里不要 loadLibrary。
 */
internal object NativeRoadCondition {
    /** 方式一：当前栈返回 Bundle，不走 VMsgDispatcher。 */
    external fun nativeGetRoadCondition(cityId: Int, roadName: String): Bundle

    /** 方式二：立刻返回 taskId，结果经 MSG_SEARCH_ROAD_CONDITION_UPDATE 回调。 */
    external fun nativeRequestRoadCondition(cityId: Int, roadName: String): Int
}
