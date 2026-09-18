#include "jni_road_condition.h"
#include "jni_utils.h"

#include <android/log.h>
#include <pthread.h>

#include <atomic>
#include <chrono>
#include <string>
#include <thread>

#define LOG_TAG "coresdk-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Bundle key 必须与 Kotlin JniConstant 字符串一致
static const char kTaskId[] = "roadConditionTaskID";
static const char kCityId[] = "roadConditionCityId";
static const char kRoadName[] = "roadConditionRoadName";
static const char kNativeThread[] = "roadConditionNativeThread";

static std::atomic<int> g_task_id{0};

static std::string jstring_to_utf8(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return "";
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return "";
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

static void put_int(JNIEnv* env, jobject bundle, jmethodID putInt, const char* key, int value) {
    jstring k = env->NewStringUTF(key);
    env->CallVoidMethod(bundle, putInt, k, value);
    env->DeleteLocalRef(k);
}

static void put_string(JNIEnv* env, jobject bundle, jmethodID putString,
                       const char* key, const char* value) {
    jstring k = env->NewStringUTF(key);
    jstring v = env->NewStringUTF(value != nullptr ? value : "");
    env->CallVoidMethod(bundle, putString, k, v);
    env->DeleteLocalRef(k);
    env->DeleteLocalRef(v);
}

static jobject new_road_condition_bundle(JNIEnv* env, int taskId, int city,
                                         const char* name, const char* thread_name) {
    jclass bundleClz = env->FindClass("android/os/Bundle");
    if (bundleClz == nullptr) {
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(bundleClz, "<init>", "()V");
    jmethodID putInt = env->GetMethodID(bundleClz, "putInt", "(Ljava/lang/String;I)V");
    jmethodID putString = env->GetMethodID(
        bundleClz, "putString", "(Ljava/lang/String;Ljava/lang/String;)V");
    jobject bundle = env->NewObject(bundleClz, ctor);
    put_int(env, bundle, putInt, kTaskId, taskId);
    put_int(env, bundle, putInt, kCityId, city);
    put_string(env, bundle, putString, kRoadName, name);
    put_string(env, bundle, putString, kNativeThread, thread_name);
    env->DeleteLocalRef(bundleClz);
    return bundle;
}

JNIEXPORT jobject JNICALL
Java_com_bingo_coresdk_route_NativeRoadCondition_nativeGetRoadCondition(
    JNIEnv* env, jobject /* thiz */, jint cityId, jstring roadName) {
    const int taskId = ++g_task_id;
    const std::string name = jstring_to_utf8(env, roadName);
    LOGI("nativeGetRoadCondition taskId=%d city=%d (sync)", taskId, cityId);
    // 方式一：当前线程组 Bundle 并 return，listener 不会收到
    return new_road_condition_bundle(env, taskId, cityId, name.c_str(), "sync-jni");
}

JNIEXPORT jint JNICALL
Java_com_bingo_coresdk_route_NativeRoadCondition_nativeRequestRoadCondition(
    JNIEnv* env, jobject /* thiz */, jint cityId, jstring roadName) {
    const int taskId = ++g_task_id;
    const int city = cityId;
    const std::string name = jstring_to_utf8(env, roadName);
    LOGI("nativeRequestRoadCondition taskId=%d city=%d (async)", taskId, city);

    // 方式二：模拟引擎工作线程，结果经消息总线回调
    std::thread([taskId, city, name]() {
        pthread_setname_np(pthread_self(), "rc-worker");
        std::this_thread::sleep_for(std::chrono::milliseconds(200));

        JniThreadEnv jni;
        if (!jni) {
            return;
        }
        JNIEnv* workerEnv = jni.get();
        jobject bundle = new_road_condition_bundle(
            workerEnv, taskId, city, name.c_str(), "rc-worker");
        if (bundle == nullptr) {
            return;
        }
        dispatch_vi_msg(MSG_SEARCH_ROAD_CONDITION_UPDATE, 0, 0, bundle);
        workerEnv->DeleteLocalRef(bundle);
    }).detach();

    return taskId;
}
