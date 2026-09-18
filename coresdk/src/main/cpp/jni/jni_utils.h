#ifndef JNI_UTILS_H
#define JNI_UTILS_H

#include <jni.h>
#include "vmsg_constant.h"

#ifdef __cplusplus

/**
 * 当前线程的 JNIEnv。
 * 引擎/工作线程通常未附着 JVM：构造时 AttachCurrentThread，析构时 Detach。
 * 已在 Java 线程则只 GetEnv，不 Detach。
 */
class JniThreadEnv {
public:
    JniThreadEnv();
    ~JniThreadEnv();
    JNIEnv* get() const { return env_; }
    explicit operator bool() const { return env_ != nullptr; }

private:
    JNIEnv* env_ = nullptr;
    bool attached_ = false;
};

/**
 * 对标百度 dispatch_vi_msg。
 * 调 Java `VMsgDispatcher.dispatchMessage(what, arg1, arg2, obj)`。
 * 可在任意 native 线程调用；Java 侧再用 Handler 切主线程。
 */
void dispatch_vi_msg(int msg_id, int arg1, int arg2, jobject obj);

extern "C" {
#endif

/** so 加载时缓存 JavaVM 和 VMsgDispatcher.dispatchMessage，供工作线程回调。 */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved);

#ifdef __cplusplus
}
#endif

#endif
