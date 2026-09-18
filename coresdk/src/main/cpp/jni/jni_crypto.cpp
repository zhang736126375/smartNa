/**
 * JNI 胶水层：不实现 AES，只做编码转换并调用 libcore.so。
 *
 * 新增 native 方法流程：先改 jni/jni_crypto.h 声明，再在本文件实现。
 *
 * 注意：
 * - 明文用 String.getBytes("UTF-8")，避免 GetStringUTFChars 的 Modified UTF-8。
 * - Base64 在本文件完成，core 只处理原始字节。
 * - 错误码映射成 Kotlin CryptoException 文案。
 */
#include "jni_crypto.h"
#include <core/core_crypto.h>

#include <cstdint>
#include <string>
#include <vector>

namespace {

const char* kErrFormat = "密文格式无效";
const char* kErrAuth = "解密失败，数据可能被篡改";
const char* kErrInternal = "内部错误";

const char kB64Table[] =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

void throwCrypto(JNIEnv* env, const char* msg) {
    jclass cls = env->FindClass("com/bingo/coresdk/CryptoException");
    if (cls != nullptr) {
        env->ThrowNew(cls, msg);
        env->DeleteLocalRef(cls);
    }
}

const char* mapCoreError(int rc) {
    if (rc == CORE_ERR_FORMAT) {
        return kErrFormat;
    }
    if (rc == CORE_ERR_AUTH) {
        return kErrAuth;
    }
    return kErrInternal;
}

std::string base64Encode(const uint8_t* data, size_t len) {
    std::string out;
    out.reserve(((len + 2) / 3) * 4);
    size_t i = 0;
    while (i + 2 < len) {
        uint32_t n = (static_cast<uint32_t>(data[i]) << 16)
            | (static_cast<uint32_t>(data[i + 1]) << 8)
            | data[i + 2];
        out.push_back(kB64Table[(n >> 18) & 63]);
        out.push_back(kB64Table[(n >> 12) & 63]);
        out.push_back(kB64Table[(n >> 6) & 63]);
        out.push_back(kB64Table[n & 63]);
        i += 3;
    }
    if (i < len) {
        uint32_t n = static_cast<uint32_t>(data[i]) << 16;
        if (i + 1 < len) {
            n |= static_cast<uint32_t>(data[i + 1]) << 8;
        }
        out.push_back(kB64Table[(n >> 18) & 63]);
        out.push_back(kB64Table[(n >> 12) & 63]);
        if (i + 1 < len) {
            out.push_back(kB64Table[(n >> 6) & 63]);
        } else {
            out.push_back('=');
        }
        out.push_back('=');
    }
    return out;
}

int b64Value(char c) {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+') return 62;
    if (c == '/') return 63;
    return -1;
}

bool base64Decode(const char* text, size_t len, std::vector<uint8_t>* out) {
    if (len % 4 != 0) {
        return false;
    }
    size_t pad = 0;
    if (len >= 1 && text[len - 1] == '=') pad++;
    if (len >= 2 && text[len - 2] == '=') pad++;
    out->clear();
    out->reserve((len / 4) * 3 - pad);
    for (size_t i = 0; i < len; i += 4) {
        int v[4];
        for (int j = 0; j < 4; j++) {
            char c = text[i + j];
            if (c == '=') {
                if (i != len - 4 || j < 2) {
                    return false;
                }
                v[j] = 0;
            } else {
                v[j] = b64Value(c);
                if (v[j] < 0) {
                    return false;
                }
            }
        }
        out->push_back(static_cast<uint8_t>((v[0] << 2) | (v[1] >> 4)));
        if (text[i + 2] != '=') {
            out->push_back(static_cast<uint8_t>((v[1] << 4) | (v[2] >> 2)));
        }
        if (text[i + 3] != '=') {
            out->push_back(static_cast<uint8_t>((v[2] << 6) | v[3]));
        }
    }
    return true;
}

jbyteArray jstringToUtf8Bytes(JNIEnv* env, jstring s) {
    if (s == nullptr) {
        s = env->NewStringUTF("");
    }
    jclass strCls = env->FindClass("java/lang/String");
    jstring enc = env->NewStringUTF("UTF-8");
    jmethodID getBytes = env->GetMethodID(strCls, "getBytes", "(Ljava/lang/String;)[B");
    jbyteArray bytes = static_cast<jbyteArray>(env->CallObjectMethod(s, getBytes, enc));
    env->DeleteLocalRef(strCls);
    env->DeleteLocalRef(enc);
    return bytes;
}

jstring utf8BytesToJstring(JNIEnv* env, const uint8_t* data, size_t len) {
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(len));
    if (bytes == nullptr) {
        throwCrypto(env, kErrInternal);
        return nullptr;
    }
    if (len > 0) {
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(len),
                                reinterpret_cast<const jbyte*>(data));
    }
    jclass strCls = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(strCls, "<init>", "([BLjava/lang/String;)V");
    jstring enc = env->NewStringUTF("UTF-8");
    jstring result = static_cast<jstring>(env->NewObject(strCls, ctor, bytes, enc));
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(strCls);
    env->DeleteLocalRef(enc);
    return result;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_bingo_coresdk_NativeCrypto_nativeEncrypt(JNIEnv* env, jobject /* thiz */, jstring plainText) {
    jbyteArray bytes = jstringToUtf8Bytes(env, plainText);
    if (bytes == nullptr) {
        throwCrypto(env, kErrInternal);
        return nullptr;
    }
    jsize len = env->GetArrayLength(bytes);
    std::vector<uint8_t> plain(static_cast<size_t>(len));
    if (len > 0) {
        env->GetByteArrayRegion(bytes, 0, len, reinterpret_cast<jbyte*>(plain.data()));
    }
    env->DeleteLocalRef(bytes);

    uint8_t* packed = nullptr;
    size_t packedLen = 0;
    int rc = core_encrypt(plain.empty() ? nullptr : plain.data(), plain.size(),
                          &packed, &packedLen);
    if (rc != CORE_OK) {
        throwCrypto(env, mapCoreError(rc));
        return nullptr;
    }
    std::string b64 = base64Encode(packed, packedLen);
    core_free(packed);
    return env->NewStringUTF(b64.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bingo_coresdk_NativeCrypto_nativeDecrypt(JNIEnv* env, jobject /* thiz */, jstring cipherBase64) {
    if (cipherBase64 == nullptr) {
        throwCrypto(env, kErrFormat);
        return nullptr;
    }
    const char* chars = env->GetStringUTFChars(cipherBase64, nullptr);
    if (chars == nullptr) {
        throwCrypto(env, kErrInternal);
        return nullptr;
    }
    jsize charLen = env->GetStringUTFLength(cipherBase64);
    std::vector<uint8_t> packed;
    bool ok = base64Decode(chars, static_cast<size_t>(charLen), &packed);
    env->ReleaseStringUTFChars(cipherBase64, chars);
    if (!ok) {
        throwCrypto(env, kErrFormat);
        return nullptr;
    }
    if (packed.size() < 28) {
        throwCrypto(env, kErrFormat);
        return nullptr;
    }

    uint8_t* plain = nullptr;
    size_t plainLen = 0;
    int rc = core_decrypt(packed.data(), packed.size(), &plain, &plainLen);
    if (rc != CORE_OK) {
        throwCrypto(env, mapCoreError(rc));
        return nullptr;
    }
    jstring result = utf8BytesToJstring(env, plain, plainLen);
    core_free(plain);
    return result;
}
