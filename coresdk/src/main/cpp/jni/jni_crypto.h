#ifndef JNI_CRYPTO_H
#define JNI_CRYPTO_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * JNI 导出声明。
 * 新增给 Java/Kotlin 的 native 方法：先在本文件声明，再在 jni_crypto.cpp 实现。
 */
JNIEXPORT jstring JNICALL
Java_com_bingo_coresdk_NativeCrypto_nativeEncrypt(JNIEnv* env, jobject thiz, jstring plainText);

JNIEXPORT jstring JNICALL
Java_com_bingo_coresdk_NativeCrypto_nativeDecrypt(JNIEnv* env, jobject thiz, jstring cipherBase64);

#ifdef __cplusplus
}
#endif

#endif
