package com.bingo.coresdk.vi

/**
 * 引擎消息号，必须与 C++ `vmsg_constant.h` 数字一致。JNI 只传 int。
 *
 * 新增一条异步回调：这里加常量，C++ 同名同值，业务 Controller 的 `observe(该常量)`。
 */
object VMsgConstant {
    /** 路况查询结果。对照百度 MSG_SEARCH_ROAD_CONDITION_UPDATE。 */
    const val MSG_SEARCH_ROAD_CONDITION_UPDATE = 12009
}
