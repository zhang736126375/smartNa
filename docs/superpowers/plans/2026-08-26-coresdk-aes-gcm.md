# coresdk AES-256-GCM Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 smartNa 中落地 `:core`（纯 C++ `libcore.so`）+ `:coresdk`（JNI SDK）+ App Demo，实现 AES-256-GCM 字符串加解密。

**Architecture:** `:core` 用 mbedTLS 实现 C ABI（`core_encrypt` / `core_decrypt` / `core_free`），密钥写死在 C++；`:coresdk` 产出 `libcoresdk.so` 并通过 Prefab 链接 `libcore.so`，Kotlin `CryptoSdk` 给上层调用；`:app` 只依赖 `:coresdk`，删除原 `native-lib.cpp`。

**Tech Stack:** AGP 8.1.3、Kotlin 1.9.0、CMake 3.22.1、NDK C++17、mbedTLS 3.6.2、JUnit4 + AndroidX Test（仪器测试）

**Spec:** `docs/superpowers/specs/2026-08-26-coresdk-aes-gcm-design.md`

## Global Constraints

- 算法：AES-256-GCM；密钥 32 字节，仅存在于 `:core` 的 `.cpp`，Java/Kotlin 不可见
- 密文：`Base64(nonce 12字节 || ciphertext || tag 16字节)`；明文 UTF-8；空串允许加密
- Kotlin 唯一入口：`com.bingo.coresdk.CryptoSdk.encrypt(String): String` / `decrypt(String): String`
- C ABI：`int core_encrypt(const uint8_t* plain, size_t plain_len, uint8_t** out, size_t* out_len)`（decrypt 同形；`void core_free(uint8_t* p)`）；成功返回 0
- 异常文案固定：`密文格式无效` / `解密失败，数据可能被篡改` / `内部错误`
- `System.loadLibrary("core")` 再 `System.loadLibrary("coresdk")`
- minSdk 24、compileSdk 33、Java/Kotlin 1.8；注释用中文
- `:app` 不得直接依赖 `:core` 或调用 JNI
- 不做 OpenSSL、Keystore、ByteArray 重载、C++ gtest

## File Map

**Create**

- `core/build.gradle.kts` — Android Library，仅 native，Prefab 发布 `core`
- `core/src/main/AndroidManifest.xml`
- `core/src/main/cpp/CMakeLists.txt`
- `core/src/main/cpp/include/core/core_crypto.h` — C ABI
- `core/src/main/cpp/src/core_crypto.cpp` — AES-GCM + 内置密钥
- `core/src/main/cpp/include/mbedtls_config.h` — 裁剪配置
- `core/src/main/cpp/third_party/mbedtls/` — mbedTLS 3.6.2 源码（clone 后按 CMake 所列 `.c` 编译）
- `coresdk/build.gradle.kts` — Android Library，JNI + Kotlin
- `coresdk/src/main/AndroidManifest.xml`
- `coresdk/src/main/cpp/CMakeLists.txt`
- `coresdk/src/main/cpp/jni_crypto.cpp` — JNI 胶水 + Base64
- `coresdk/src/main/java/com/bingo/coresdk/CryptoException.kt`
- `coresdk/src/main/java/com/bingo/coresdk/NativeCrypto.kt`
- `coresdk/src/main/java/com/bingo/coresdk/CryptoSdk.kt`
- `coresdk/src/androidTest/java/com/bingo/coresdk/CryptoSdkTest.kt`

**Modify**

- `settings.gradle.kts` — include `:core` `:coresdk`
- `build.gradle.kts` — 增加 `com.android.library` plugin
- `app/build.gradle.kts` — 依赖 `:coresdk`，删除 `externalNativeBuild`
- `app/src/main/java/com/bingo/smartna/MainActivity.kt` — Demo UI
- `app/src/main/res/layout/activity_main.xml` — 输入框/按钮

**Delete**

- `app/src/main/cpp/native-lib.cpp`
- `app/src/main/cpp/CMakeLists.txt`

---

### Task 1: 多模块骨架 + Kotlin API + 失败的仪器测试

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts`
- Create: `core/build.gradle.kts`
- Create: `core/src/main/AndroidManifest.xml`
- Create: `coresdk/build.gradle.kts`
- Create: `coresdk/src/main/AndroidManifest.xml`
- Create: `coresdk/src/main/java/com/bingo/coresdk/CryptoException.kt`
- Create: `coresdk/src/main/java/com/bingo/coresdk/NativeCrypto.kt`
- Create: `coresdk/src/main/java/com/bingo/coresdk/CryptoSdk.kt`
- Test: `coresdk/src/androidTest/java/com/bingo/coresdk/CryptoSdkTest.kt`

**Interfaces:**
- Consumes: 无
- Produces: `class CryptoException(message: String) : RuntimeException(message)`；`object CryptoSdk { fun encrypt(plainText: String): String; fun decrypt(cipherBase64: String): String }`；`internal object NativeCrypto { fun nativeEncrypt(plainText: String): String; fun nativeDecrypt(cipherBase64: String): String }`（本任务 native 方法先抛 `UnsatisfiedLinkError` 或由 Kotlin 抛 `CryptoException("内部错误")`，不编 JNI）

- [ ] **Step 1: 改 Gradle 纳入两个 library 模块**

`settings.gradle.kts` 末尾改为：

```kotlin
rootProject.name = "smartNa"
include(":app")
include(":core")
include(":coresdk")
```

根 `build.gradle.kts` 改为：

```kotlin
plugins {
    id("com.android.application") version "8.1.3" apply false
    id("com.android.library") version "8.1.3" apply false
    id("org.jetbrains.kotlin.android") version "1.9.0" apply false
}
```

`core/src/main/AndroidManifest.xml` 与 `coresdk/src/main/AndroidManifest.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

`core/build.gradle.kts`（本任务先不接 CMake，保证模块能 sync；Task 2 再加 `externalNativeBuild`）：

```kotlin
plugins {
    id("com.android.library")
}

android {
    namespace = "com.bingo.core"
    compileSdk = 33
    defaultConfig {
        minSdk = 24
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies { }
```

创建空文件 `core/consumer-rules.pro`。

`coresdk/build.gradle.kts`：

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bingo.coresdk"
    compileSdk = 33
    defaultConfig {
        minSdk = 24
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.annotation:annotation:1.7.1")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.6.1")
}
```

创建空文件 `coresdk/consumer-rules.pro`。

- [ ] **Step 2: 写 Kotlin API（先不 loadLibrary）**

`CryptoException.kt`：

```kotlin
package com.bingo.coresdk

class CryptoException(message: String) : RuntimeException(message)
```

`NativeCrypto.kt`：

```kotlin
package com.bingo.coresdk

internal object NativeCrypto {
    external fun nativeEncrypt(plainText: String): String
    external fun nativeDecrypt(cipherBase64: String): String
}
```

`CryptoSdk.kt`（本任务不 `loadLibrary`，调用 native 会 `UnsatisfiedLinkError`；包一层转成 `内部错误`，让测试能编译并按异常断言失败）：

```kotlin
package com.bingo.coresdk

object CryptoSdk {
    fun encrypt(plainText: String): String {
        return try {
            NativeCrypto.nativeEncrypt(plainText)
        } catch (e: UnsatisfiedLinkError) {
            throw CryptoException("内部错误")
        }
    }

    fun decrypt(cipherBase64: String): String {
        return try {
            NativeCrypto.nativeDecrypt(cipherBase64)
        } catch (e: UnsatisfiedLinkError) {
            throw CryptoException("内部错误")
        }
    }
}
```

- [ ] **Step 3: 写仪器测试（此时应失败）**

`CryptoSdkTest.kt`：

```kotlin
package com.bingo.coresdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CryptoSdkTest {

    @Test
    fun encryptDecrypt_restoresPlaintext() {
        val plain = "你好 SmartNa"
        val cipher = CryptoSdk.encrypt(plain)
        assertTrue(cipher.isNotEmpty())
        assertNotEquals(plain, cipher)
        assertEquals(plain, CryptoSdk.decrypt(cipher))
    }

    @Test
    fun encryptDecrypt_emptyString() {
        assertEquals("", CryptoSdk.decrypt(CryptoSdk.encrypt("")))
    }

    @Test
    fun decrypt_invalidBase64_throwsFormat() {
        try {
            CryptoSdk.decrypt("@@@not-base64@@@")
            throw AssertionError("expected CryptoException")
        } catch (e: CryptoException) {
            assertEquals("密文格式无效", e.message)
        }
    }

    @Test
    fun decrypt_tamperedCipher_throwsAuthOrFormat() {
        val cipher = CryptoSdk.encrypt("payload")
        val tampered = if (cipher.last() == 'A') cipher.dropLast(1) + "B" else cipher.dropLast(1) + "A"
        try {
            CryptoSdk.decrypt(tampered)
            throw AssertionError("expected CryptoException")
        } catch (e: CryptoException) {
            assertTrue(
                e.message == "解密失败，数据可能被篡改" || e.message == "密文格式无效"
            )
        }
    }
}
```

- [ ] **Step 4: 确认测试失败**

有模拟器/真机时：

```bash
./gradlew :coresdk:connectedDebugAndroidTest
```

Expected: `encryptDecrypt_restoresPlaintext` 失败，异常为 `CryptoException("内部错误")`。

无设备时至少：

```bash
./gradlew :core:assembleDebug :coresdk:assembleDebug :coresdk:compileDebugAndroidTestKotlin
```

Expected: BUILD SUCCESSFUL（测试尚未通过）。

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts build.gradle.kts core coresdk
git commit -m "$(cat <<'EOF'
feat: add core and coresdk modules with CryptoSdk API stubs

EOF
)"
```

---

### Task 2: `:core` mbedTLS + AES-256-GCM C ABI

**Files:**
- Modify: `core/build.gradle.kts`
- Create: `core/src/main/cpp/CMakeLists.txt`
- Create: `core/src/main/cpp/include/core/core_crypto.h`
- Create: `core/src/main/cpp/include/mbedtls_config.h`
- Create: `core/src/main/cpp/src/core_crypto.cpp`
- Create: `core/src/main/cpp/third_party/mbedtls/`（mbedTLS 3.6.2）

**Interfaces:**
- Consumes: 无 Kotlin
- Produces: `libcore.so`；头文件符号：
  - `#define CORE_OK 0`
  - `#define CORE_ERR_PARAM 1`
  - `#define CORE_ERR_FORMAT 2`
  - `#define CORE_ERR_AUTH 3`
  - `#define CORE_ERR_NOMEM 4`
  - `#define CORE_ERR_CRYPTO 5`
  - `int core_encrypt(const uint8_t* plain, size_t plain_len, uint8_t** out, size_t* out_len);`
  - `int core_decrypt(const uint8_t* packed, size_t packed_len, uint8_t** out, size_t* out_len);`
  - `void core_free(uint8_t* p);`
  - Prefab 包名 `core`，headers 目录 `src/main/cpp/include`

- [ ] **Step 1: 拉取 mbedTLS 3.6.2**

```bash
mkdir -p core/src/main/cpp/third_party
# 若 github.com 不通，改用 https://ghproxy.net/https://github.com/Mbed-TLS/mbedtls.git
git clone --depth 1 --branch v3.6.2 https://github.com/Mbed-TLS/mbedtls.git \
  core/src/main/cpp/third_party/mbedtls
rm -rf core/src/main/cpp/third_party/mbedtls/.git
```

不要把整个 mbedtls 测试目录当源码编进 so，只在 CMake 里列出下面这些 `.c`。

- [ ] **Step 2: 写 `mbedtls_config.h`**

`core/src/main/cpp/include/mbedtls_config.h`：

```c
#ifndef MBEDTLS_CONFIG_H
#define MBEDTLS_CONFIG_H

#define MBEDTLS_PLATFORM_C
#define MBEDTLS_AES_C
#define MBEDTLS_GCM_C
#define MBEDTLS_SHA224_C
#define MBEDTLS_SHA256_C
#define MBEDTLS_ENTROPY_C
#define MBEDTLS_CTR_DRBG_C
#define MBEDTLS_AES_ROM_TABLES

#include "mbedtls/check_config.h"

#endif
```

CMake 必须 `-DMBEDTLS_CONFIG_FILE="mbedtls_config.h"`，并把 `include/` 放在 mbedTLS 头文件搜索路径之前。

- [ ] **Step 3: 写 C ABI 头文件**

`core/src/main/cpp/include/core/core_crypto.h`：

```c
#ifndef CORE_CRYPTO_H
#define CORE_CRYPTO_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define CORE_OK 0
#define CORE_ERR_PARAM 1
#define CORE_ERR_FORMAT 2
#define CORE_ERR_AUTH 3
#define CORE_ERR_NOMEM 4
#define CORE_ERR_CRYPTO 5

int core_encrypt(const uint8_t* plain, size_t plain_len,
                 uint8_t** out, size_t* out_len);

int core_decrypt(const uint8_t* packed, size_t packed_len,
                 uint8_t** out, size_t* out_len);

void core_free(uint8_t* p);

#ifdef __cplusplus
}
#endif

#endif
```

- [ ] **Step 4: 实现 `core_crypto.cpp`**

要求（实现时按此逻辑，并加中文注释）：

- 内置密钥（恰好 32 字节，demo only）：

```cpp
static const uint8_t kDemoAes256Key[32] = {
    'D','E','M','O','-','A','E','S','-','2','5','6','-','G','C','M',
    '-','T','E','S','T','-','K','E','Y','-','!','!','!','!','!','!'
};
```

- `#define CORE_NONCE_LEN 12` `#define CORE_TAG_LEN 16`
- `core_encrypt`：`plain == nullptr && plain_len != 0` → `CORE_ERR_PARAM`；`out`/`out_len` 空指针 → `CORE_ERR_PARAM`；用 `mbedtls_entropy` + `mbedtls_ctr_drbg` 生成 12 字节 nonce；`mbedtls_gcm_setkey(..., MBEDTLS_CIPHER_ID_AES, kDemoAes256Key, 256)`；`mbedtls_gcm_crypt_and_tag`（encrypt）；输出缓冲 `nonce || ciphertext || tag`，长度 `12 + plain_len + 16`；`malloc` 失败 → `CORE_ERR_NOMEM`；mbedTLS 失败 → `CORE_ERR_CRYPTO`
- `core_decrypt`：`packed_len < 12 + 16` → `CORE_ERR_FORMAT`；`mbedtls_gcm_auth_decrypt`；若返回 `MBEDTLS_ERR_GCM_AUTH_FAILED` → `CORE_ERR_AUTH`；明文长度 `packed_len - 28`
- `core_free`：`p != nullptr` 时 `free(p)`
- 每次调用独立 init/free mbedTLS 上下文，不使用全局可变状态（密钥数组除外）
- `plain_len == 0` 仍加密（输出 28 字节 packed）

参考实现骨架：

```cpp
#include "core/core_crypto.h"
#include "mbedtls/ctr_drbg.h"
#include "mbedtls/entropy.h"
#include "mbedtls/error.h"
#include "mbedtls/gcm.h"
#include <stdlib.h>
#include <string.h>

static const uint8_t kDemoAes256Key[32] = {
    'D','E','M','O','-','A','E','S','-','2','5','6','-','G','C','M',
    '-','T','E','S','T','-','K','E','Y','-','!','!','!','!','!','!'
};

static const size_t kNonceLen = 12;
static const size_t kTagLen = 16;

void core_free(uint8_t* p) {
    free(p);
}

static int random_nonce(uint8_t* nonce) {
    mbedtls_entropy_context entropy;
    mbedtls_ctr_drbg_context drbg;
    mbedtls_entropy_init(&entropy);
    mbedtls_ctr_drbg_init(&drbg);
    const char pers[] = "core_gcm_demo";
    int rc = mbedtls_ctr_drbg_seed(&drbg, mbedtls_entropy_func, &entropy,
                                   reinterpret_cast<const unsigned char*>(pers),
                                   sizeof(pers) - 1);
    if (rc == 0) {
        rc = mbedtls_ctr_drbg_random(&drbg, nonce, kNonceLen);
    }
    mbedtls_ctr_drbg_free(&drbg);
    mbedtls_entropy_free(&entropy);
    return rc == 0 ? CORE_OK : CORE_ERR_CRYPTO;
}

int core_encrypt(const uint8_t* plain, size_t plain_len,
                 uint8_t** out, size_t* out_len) {
    if (out == nullptr || out_len == nullptr) return CORE_ERR_PARAM;
    if (plain == nullptr && plain_len != 0) return CORE_ERR_PARAM;
    *out = nullptr;
    *out_len = 0;

    const size_t packed_len = kNonceLen + plain_len + kTagLen;
    uint8_t* packed = static_cast<uint8_t*>(malloc(packed_len));
    if (packed == nullptr) return CORE_ERR_NOMEM;

    int rc = random_nonce(packed);
    if (rc != CORE_OK) {
        free(packed);
        return rc;
    }

    mbedtls_gcm_context gcm;
    mbedtls_gcm_init(&gcm);
    rc = mbedtls_gcm_setkey(&gcm, MBEDTLS_CIPHER_ID_AES, kDemoAes256Key, 256);
    if (rc == 0) {
        rc = mbedtls_gcm_crypt_and_tag(
            &gcm, MBEDTLS_GCM_ENCRYPT, plain_len,
            packed, kNonceLen,
            nullptr, 0,
            plain == nullptr ? reinterpret_cast<const uint8_t*>("") : plain,
            packed + kNonceLen,
            kTagLen, packed + kNonceLen + plain_len);
    }
    mbedtls_gcm_free(&gcm);
    if (rc != 0) {
        free(packed);
        return CORE_ERR_CRYPTO;
    }
    *out = packed;
    *out_len = packed_len;
    return CORE_OK;
}

int core_decrypt(const uint8_t* packed, size_t packed_len,
                 uint8_t** out, size_t* out_len) {
    if (out == nullptr || out_len == nullptr) return CORE_ERR_PARAM;
    if (packed == nullptr || packed_len < kNonceLen + kTagLen) return CORE_ERR_FORMAT;
    *out = nullptr;
    *out_len = 0;

    const size_t plain_len = packed_len - kNonceLen - kTagLen;
    uint8_t* plain = static_cast<uint8_t*>(malloc(plain_len == 0 ? 1 : plain_len));
    if (plain == nullptr) return CORE_ERR_NOMEM;

    mbedtls_gcm_context gcm;
    mbedtls_gcm_init(&gcm);
    int rc = mbedtls_gcm_setkey(&gcm, MBEDTLS_CIPHER_ID_AES, kDemoAes256Key, 256);
    if (rc == 0) {
        rc = mbedtls_gcm_auth_decrypt(
            &gcm, plain_len,
            packed, kNonceLen,
            nullptr, 0,
            packed + kNonceLen + plain_len, kTagLen,
            packed + kNonceLen,
            plain);
    }
    mbedtls_gcm_free(&gcm);
    if (rc == MBEDTLS_ERR_GCM_AUTH_FAILED) {
        free(plain);
        return CORE_ERR_AUTH;
    }
    if (rc != 0) {
        free(plain);
        return CORE_ERR_CRYPTO;
    }
    *out = plain;
    *out_len = plain_len;
    return CORE_OK;
}
```

文件顶部加中文注释：说明 demo 密钥、`nonce||cipher||tag` 布局、生产环境应外置密钥。

- [ ] **Step 5: 写 core CMake + 打开 Prefab**

`core/src/main/cpp/CMakeLists.txt`：

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(core)

set(MBEDTLS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/mbedtls)

add_library(mbedtls_crypto STATIC
    ${MBEDTLS_DIR}/library/aes.c
    ${MBEDTLS_DIR}/library/gcm.c
    ${MBEDTLS_DIR}/library/sha256.c
    ${MBEDTLS_DIR}/library/entropy.c
    ${MBEDTLS_DIR}/library/entropy_poll.c
    ${MBEDTLS_DIR}/library/ctr_drbg.c
    ${MBEDTLS_DIR}/library/platform.c
    ${MBEDTLS_DIR}/library/platform_util.c
    ${MBEDTLS_DIR}/library/constant_time.c
)
target_include_directories(mbedtls_crypto PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${MBEDTLS_DIR}/include
)
target_compile_definitions(mbedtls_crypto PUBLIC
    MBEDTLS_CONFIG_FILE="mbedtls_config.h"
)

add_library(core SHARED src/core_crypto.cpp)
target_include_directories(core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_link_libraries(core mbedtls_crypto log)
```

`core/build.gradle.kts` 的 `android {}` 增加：

```kotlin
    buildFeatures {
        prefabPublishing = true
    }
    prefab {
        create("core") {
            headers = "src/main/cpp/include"
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    defaultConfig {
        // 保留已有 minSdk / ndk
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }
```

- [ ] **Step 6: 编译 `:core`**

```bash
./gradlew :core:assembleDebug
```

Expected: `core/build/intermediates/cmake/debug/obj/<abi>/libcore.so` 存在。若 `check_config.h` 或缺 `.c` 报错，按编译器提示补 `mbedtls_config.h` 宏或 CMake 源文件，不要改 C ABI。

- [ ] **Step 7: Commit**

```bash
git add core
git commit -m "$(cat <<'EOF'
feat: add core AES-256-GCM C ABI with bundled mbedTLS

EOF
)"
```

---

### Task 3: `:coresdk` JNI + loadLibrary，仪器测试通过

**Files:**
- Modify: `coresdk/build.gradle.kts`
- Create: `coresdk/src/main/cpp/CMakeLists.txt`
- Create: `coresdk/src/main/cpp/jni_crypto.cpp`
- Modify: `coresdk/src/main/java/com/bingo/coresdk/NativeCrypto.kt`
- Modify: `coresdk/src/main/java/com/bingo/coresdk/CryptoSdk.kt`

**Interfaces:**
- Consumes: Task 2 的 `core_encrypt` / `core_decrypt` / `core_free` 与错误码；Prefab `core::core`
- Produces: `libcoresdk.so`；JNI：
  - `Java_com_bingo_coresdk_NativeCrypto_nativeEncrypt(JNIEnv*, jobject, jstring) -> jstring`
  - `Java_com_bingo_coresdk_NativeCrypto_nativeDecrypt(JNIEnv*, jobject, jstring) -> jstring`
  - 失败抛 `com/bingo/coresdk/CryptoException`
  - `CryptoSdk` 的 `init` 先 `System.loadLibrary("core")` 再 `System.loadLibrary("coresdk")`

- [ ] **Step 1: coresdk CMake 通过 Prefab 链接 libcore**

`coresdk/src/main/cpp/CMakeLists.txt`：

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(coresdk)

find_package(core REQUIRED CONFIG)

add_library(coresdk SHARED jni_crypto.cpp)
target_link_libraries(coresdk core::core android log)
```

`coresdk/build.gradle.kts` 的 `android {}` 增加：

```kotlin
    buildFeatures {
        prefab = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    defaultConfig {
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }
    packaging {
        jniLibs {
            pickFirsts += setOf("**/libc++_shared.so")
        }
    }
```

- [ ] **Step 2: 实现 JNI（含 Base64 与异常映射）**

`jni_crypto.cpp` 必须包含：

1. 标准 Base64（RFC 4648，无换行）。encode 输出 `jstring`；decode 失败（非法字符、长度错误）抛 `CryptoException("密文格式无效")`。
2. `jstring` ↔ UTF-8：用 `java/lang/String.getBytes("UTF-8")` 和 `new String(byte[], "UTF-8")`，不要只用 `GetStringUTFChars` 处理中文补充平面。
3. `nativeEncrypt`：UTF-8 字节 → `core_encrypt` → Base64；`core_*` 非 0 → `内部错误`；`core_free` 释放。
4. `nativeDecrypt`：Base64 decode → 若 decode 后长度 `< 28` 抛 `密文格式无效` → `core_decrypt`：
   - `CORE_ERR_FORMAT` → `密文格式无效`
   - `CORE_ERR_AUTH` → `解密失败，数据可能被篡改`
   - 其它非 0 → `内部错误`
5. 抛异常辅助：

```cpp
static void throwCrypto(JNIEnv* env, const char* msg) {
    jclass cls = env->FindClass("com/bingo/coresdk/CryptoException");
    env->ThrowNew(cls, msg);
}
```

6. `nativeEncrypt` / `nativeDecrypt` 在 `jstring` 为 `nullptr` 时：encrypt 当作空串；decrypt 抛 `密文格式无效`。
7. 中文注释：load 顺序在 Kotlin 不在这里；这里注释 JNI 局部引用、Base64 与错误码映射。

JNI 函数签名必须精确为：

```cpp
extern "C" JNIEXPORT jstring JNICALL
Java_com_bingo_coresdk_NativeCrypto_nativeEncrypt(JNIEnv* env, jobject /* thiz */, jstring plainText);

extern "C" JNIEXPORT jstring JNICALL
Java_com_bingo_coresdk_NativeCrypto_nativeDecrypt(JNIEnv* env, jobject /* thiz */, jstring cipherBase64);
```

`NativeCrypto` 是 Kotlin `object`，JNI 第二参是 `jobject`（不是 `jclass`）。若链接期找不到符号，把 Kotlin 改成 `class NativeCrypto` 的 `companion object` 并改用 `jclass`——优先保持 `internal object NativeCrypto` + `jobject`。

- [ ] **Step 3: Kotlin 加载 so，并映射 native 已抛的 CryptoException**

`NativeCrypto.kt` 增加：

```kotlin
internal object NativeCrypto {
    init {
        // 先加载纯 C++ 内核，再加载 JNI so（JNI so 依赖 libcore.so）
        System.loadLibrary("core")
        System.loadLibrary("coresdk")
    }

    external fun nativeEncrypt(plainText: String): String
    external fun nativeDecrypt(cipherBase64: String): String
}
```

`CryptoSdk.kt` 改为直接转发（JNI 已抛 `CryptoException`）：

```kotlin
package com.bingo.coresdk

object CryptoSdk {
    /**
     * AES-256-GCM 加密。返回 Base64(nonce||ciphertext||tag)。
     * Demo 密钥在 native 内置，生产环境不要使用。
     */
    fun encrypt(plainText: String): String = NativeCrypto.nativeEncrypt(plainText)

    fun decrypt(cipherBase64: String): String = NativeCrypto.nativeDecrypt(cipherBase64)
}
```

访问 `CryptoSdk` 会初始化 `NativeCrypto` 从而 loadLibrary。不要在 `CryptoSdk` 再 load 一次。

- [ ] **Step 4: 编译 JNI**

```bash
./gradlew :coresdk:assembleDebug
```

Expected: APK/AAR 中同时有 `libcore.so` 与 `libcoresdk.so`。

- [ ] **Step 5: 跑仪器测试**

```bash
./gradlew :coresdk:connectedDebugAndroidTest
```

Expected: `CryptoSdkTest` 全部 PASS。无设备则先保证 `assembleDebug` 成功，并在有设备后补跑；**不得在测试未通过时宣称完成**。

若 `UnsatisfiedLinkError: dlopen failed`：检查 ABI、`c++_shared`、load 顺序、Prefab 是否把 `libcore.so` 打进测试 APK。

若中文 roundtrip 失败：检查 UTF-8 JNI 转换。

- [ ] **Step 6: Commit**

```bash
git add coresdk
git commit -m "$(cat <<'EOF'
feat: add coresdk JNI wrapper for core AES-GCM

EOF
)"
```

---

### Task 4: App Demo UI + 删除旧 native

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/com/bingo/smartna/MainActivity.kt`
- Delete: `app/src/main/cpp/native-lib.cpp`
- Delete: `app/src/main/cpp/CMakeLists.txt`

**Interfaces:**
- Consumes: `com.bingo.coresdk.CryptoSdk`、`com.bingo.coresdk.CryptoException`
- Produces: 可运行 Demo：加密显示 Base64，解密还原明文；错误显示异常 message

- [ ] **Step 1: app 依赖 coresdk，去掉 app 自己的 CMake**

`app/build.gradle.kts`：删除整个 `externalNativeBuild { cmake { ... } }` 块。

`dependencies` 增加：

```kotlin
implementation(project(":coresdk"))
```

不要 `implementation(project(":core"))`。

- [ ] **Step 2: 改布局**

`activity_main.xml` 换成垂直 LinearLayout：`EditText` id=`inputText`（hint「输入明文或密文」）、`Button` id=`btnEncrypt` text「加密」、`Button` id=`btnDecrypt` text「解密」、`TextView` id=`resultText`（可滚动，textIsSelectable=true）。

- [ ] **Step 3: 改 MainActivity**

删除 `external fun stringFromJNI` 和 `System.loadLibrary("smartna")`。

```kotlin
package com.bingo.smartna

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.bingo.coresdk.CryptoException
import com.bingo.coresdk.CryptoSdk
import com.bingo.smartna.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnEncrypt.setOnClickListener {
            val plain = binding.inputText.text.toString()
            try {
                binding.resultText.text = CryptoSdk.encrypt(plain)
            } catch (e: CryptoException) {
                binding.resultText.text = e.message
            }
        }
        binding.btnDecrypt.setOnClickListener {
            val cipher = binding.inputText.text.toString()
            try {
                binding.resultText.text = CryptoSdk.decrypt(cipher)
            } catch (e: CryptoException) {
                binding.resultText.text = e.message
            }
        }
    }
}
```

加中文注释：App 只调 SDK，不 load so。

删除 `app/src/main/cpp/` 下两个文件。

- [ ] **Step 4: 编译 App 并手工点验**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL。安装后：输入 `hello` → 加密得到 Base64 → 把结果贴回输入框 → 解密得到 `hello`。

再跑：

```bash
./gradlew :coresdk:connectedDebugAndroidTest :app:assembleDebug
```

- [ ] **Step 5: Commit**

```bash
git add app
git commit -m "$(cat <<'EOF'
feat: wire app demo to coresdk AES-GCM APIs

EOF
)"
```

---

## Spec coverage (self-review)

| Spec 条目 | Task |
|-----------|------|
| `:core` C ABI + 内置密钥 + mbedTLS | Task 2 |
| Prefab / 双 so / 日后换预编译 so | Task 2–3 |
| `:coresdk` Kotlin API + JNI + Base64 + 异常文案 | Task 1 stubs，Task 3 实现 |
| 空串、中文、篡改、非法 Base64 | Task 1 测试，Task 3 转绿 |
| App Demo、删除 native-lib | Task 4 |
| 中文注释 | Task 2–4 |
| 不做 OpenSSL / Keystore / ByteArray API | 全任务遵守 |
