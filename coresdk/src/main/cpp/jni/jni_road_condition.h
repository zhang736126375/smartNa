#ifndef JNI_ROAD_CONDITION_H
#define JNI_ROAD_CONDITION_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * 方式一：当前 JNI 栈返回 Bundle，不走 dispatch_vi_msg。
 * 对应 Kotlin NativeRoadCondition.nativeGetRoadCondition。
 */
JNIEXPORT jobject JNICALL
Java_com_bingo_coresdk_route_NativeRoadCondition_nativeGetRoadCondition(
    JNIEnv* env, jobject thiz, jint cityId, jstring roadName);

/**
 * 方式二：立刻返回 taskId；真正结果走 MSG_SEARCH_ROAD_CONDITION_UPDATE。
 * 对应 Kotlin NativeRoadCondition.nativeRequestRoadCondition。
 */
JNIEXPORT jint JNICALL
Java_com_bingo_coresdk_route_NativeRoadCondition_nativeRequestRoadCondition(
    JNIEnv* env, jobject thiz, jint cityId, jstring roadName);

#ifdef __cplusplus
}
#endif

#endif
