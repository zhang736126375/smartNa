#include "jni_utils.h"

#include <android/log.h>

#define LOG_TAG "coresdk-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM* g_vm = nullptr;
static jclass g_dispatcher_clz = nullptr;
static jmethodID g_dispatch_mid = nullptr;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    g_vm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    // Kotlin object + @JvmStatic → 静态方法 (IIILjava/lang/Object;)V
    jclass local = env->FindClass("com/bingo/coresdk/vi/VMsgDispatcher");
    if (local == nullptr) {
        LOGE("FindClass VMsgDispatcher failed");
        return JNI_ERR;
    }
    g_dispatcher_clz = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    g_dispatch_mid = env->GetStaticMethodID(
        g_dispatcher_clz, "dispatchMessage", "(IIILjava/lang/Object;)V");
    if (g_dispatch_mid == nullptr) {
        LOGE("GetStaticMethodID dispatchMessage failed");
        return JNI_ERR;
    }
    LOGI("JNI_OnLoad ok");
    return JNI_VERSION_1_6;
}

JniThreadEnv::JniThreadEnv() {
    if (g_vm == nullptr) {
        return;
    }
    jint status = g_vm->GetEnv(reinterpret_cast<void**>(&env_), JNI_VERSION_1_6);
    if (status == JNI_OK) {
        return;
    }
    if (status == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env_, nullptr) == JNI_OK && env_ != nullptr) {
            attached_ = true;
        }
    }
}

JniThreadEnv::~JniThreadEnv() {
    if (attached_ && g_vm != nullptr) {
        g_vm->DetachCurrentThread();
    }
}

void dispatch_vi_msg(int msg_id, int arg1, int arg2, jobject obj) {
    if (g_dispatcher_clz == nullptr || g_dispatch_mid == nullptr) {
        return;
    }
    JniThreadEnv jni;
    if (!jni) {
        LOGE("dispatch_vi_msg no JNIEnv");
        return;
    }
    JNIEnv* env = jni.get();
    env->CallStaticVoidMethod(g_dispatcher_clz, g_dispatch_mid, msg_id, arg1, arg2, obj);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}
