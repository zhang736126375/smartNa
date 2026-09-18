# coresdk AES-256-GCM 加解密 Demo 设计

日期：2026-08-26  
工程：smartNa（`com.bingo.smartna`）  
状态：待实现

## 1. 背景与目标

当前工程只有 `:app`，C++ 仍是模板 `stringFromJNI()`。需要做成可演示、可注释、可扩展的大型项目骨架：

- 纯 C++ 加密内核独立成模块，当前源码编译，以后可单独打成 `.so`
- JNI 作为 SDK 层给 Kotlin/Java 调用
- Demo 使用 AES-256-GCM，C++ 内置测试密钥
- 上层接口：明文 `String` 进，Base64 `String` 出
- 代码带中文注释

成功标准：在 App 输入明文，点加密得到 Base64 密文，再解密还原为原文；篡改密文解密失败。

## 2. 模块架构

三层，禁止 `:app` 直接依赖 `:core` 或直接调用 JNI。

```
:core          纯 C++，无 JNI
               产物：libcore.so
               对外只暴露 C ABI（extern "C"）

:coresdk       JNI SDK 层
               产物：libcoresdk.so + Kotlin API（CryptoSdk）
               链接并加载 libcore.so

:app           Demo UI
               只依赖 :coresdk
```

调用链：

```
App(Kotlin)
  → CryptoSdk.encrypt("hello")
  → JNI NativeCrypto.nativeEncrypt
  → core_encrypt()          // C ABI in libcore.so
  → mbedTLS AES-256-GCM
  → Base64(nonce || ciphertext || tag)
```

Gradle：

- `settings.gradle.kts` 增加 `include(":core")`、`include(":coresdk")`
- `:core`、`:coresdk` 均为 `com.android.library`
- `:coresdk` `implementation(project(":core"))`
- `:app` `implementation(project(":coresdk"))`
- 根 `build.gradle.kts` 增加 `com.android.library` plugin（与现有 AGP 8.1.3 一致）

以后把 C++ 打成 so 的切法（本次不做）：`:core` 不再编源码，将各 ABI 的 `libcore.so` 放到 `coresdk/src/main/jniLibs/`；`coresdk` 仍先 `System.loadLibrary("core")` 再 `loadLibrary("coresdk")`。C ABI 保持不变，JNI 不用改。

删除 `:app` 现有 `native-lib.cpp`、`app/src/main/cpp/CMakeLists.txt` 以及 `app/build.gradle.kts` 中的 `externalNativeBuild`，避免两套 native。

## 3. 密码学约定

- 算法：AES-256-GCM（mbedTLS 源码编进 `:core`，不使用 OpenSSL Prefab）
- 密钥：32 字节，写死在 `:core` 的 `.cpp` 内，不出现在 Java/Kotlin，也不暴露在公开头文件的密钥值中
- nonce：12 字节，每次加密用安全随机数生成，拼接在密文前
- tag：16 字节，拼接在密文后
- 打包字节（JNI 再做 Base64）：

```
nonce(12) || ciphertext || tag(16)
```

- 明文编码：UTF-8
- 空字符串允许加密（合法输入）
- 不做密钥轮换、Android Keystore、文件加密、多算法切换

## 4. `:core` C ABI

公开头文件只声明如下函数（无 JNI、无 Android 类型）：

```c
#ifdef __cplusplus
extern "C" {
#endif

/** 成功返回 0，失败返回非 0。*out 由调用方 core_free 释放。 */
int core_encrypt(const uint8_t* plain, size_t plain_len,
                 uint8_t** out, size_t* out_len);

int core_decrypt(const uint8_t* packed, size_t packed_len,
                 uint8_t** out, size_t* out_len);

void core_free(uint8_t* p);

#ifdef __cplusplus
}
#endif
```

错误码（实现内定义，头文件可用枚举或宏）：

| 码 | 含义 |
|----|------|
| 0 | 成功 |
| 非 0 | 参数无效 / 长度不足 / GCM 校验失败 / 内存失败 |

C++ 不抛异常，只用错误码。`:core` 的 CMake 产出 `SHARED` 库，名为 `core`。

目录（示意）：

```
core/
  build.gradle.kts
  src/main/AndroidManifest.xml
  src/main/cpp/
    CMakeLists.txt
    include/core/core_crypto.h
    src/core_crypto.cpp          # AES-GCM + 内置测试密钥
    third_party/mbedtls/         # 仅编译 aes/gcm/cipher/entropy/ctr_drbg 所需文件
```

## 5. `:coresdk` JNI 与 Kotlin API

包名：`com.bingo.coresdk`

Kotlin 对外唯一入口：

```kotlin
object CryptoSdk {
    fun encrypt(plainText: String): String
    fun decrypt(cipherBase64: String): String
}
```

内部：

- `NativeCrypto`：`external fun nativeEncrypt` / `nativeDecrypt`
- `init` 中按顺序 `System.loadLibrary("core")`、`System.loadLibrary("coresdk")`
- JNI 负责：UTF-8 编解码、Base64、调用 `core_*`、`core_free`
- 失败抛 `CryptoException`（`RuntimeException` 子类）

| 场景 | Kotlin 异常信息（固定文案） |
|------|---------------------------|
| Base64 非法或打包长度 &lt; 12+16 | `密文格式无效` |
| GCM tag 校验失败 | `解密失败，数据可能被篡改` |
| 其它 native 失败 | `内部错误` |

JNI 包/类必须与 native 方法全名一致：`Java_com_bingo_coresdk_NativeCrypto_nativeEncrypt` 等。

目录（示意）：

```
coresdk/
  build.gradle.kts
  src/main/java/com/bingo/coresdk/
    CryptoSdk.kt
    NativeCrypto.kt
    CryptoException.kt
  src/main/cpp/
    CMakeLists.txt
    jni_crypto.cpp               # 仅 JNI 胶水，不实现 AES
```

`:coresdk` 的 CMake `target_link_libraries(coresdk core ...)`，只链接、不编译 `:core` 源码。

## 6. Demo 页面

改造现有 `MainActivity`：

- 输入框、按钮「加密」「解密」、结果文本
- 加密：明文 → Base64 密文
- 解密：Base64 密文 → 明文
- catch `CryptoException` 显示错误信息
- 关键步骤写中文注释（loadLibrary 顺序、密文格式）

不新增独立 Activity/模块导航。

## 7. 测试

最少覆盖：

1. `encrypt` 再 `decrypt` 还原原文（含中文、空串）
2. 改密文一个字节 → 抛 `解密失败，数据可能被篡改` 或 `密文格式无效`
3. 非法 Base64 → 抛 `密文格式无效`

测试写在 `:coresdk` 的 androidTest（仪器测试调用 `CryptoSdk`），`:app` 不测 JNI。本次不做纯 C++ gtest 工程，避免再引入测试框架。

## 8. 注释要求

- `:core`：密钥为何内置、GCM 流程、nonce/tag 布局
- `:coresdk` JNI：局部引用、编码、错误映射
- Kotlin：`CryptoSdk` 用法与「生产环境不要用内置密钥」
- 注释用中文，不写无意义的复述型注释

## 9. 明确不做

- OpenSSL Prefab、RSA、AES-CBC
- 密钥从 Java 传入或 Android Keystore
- ByteArray 重载 API（仅 String/Base64）
- 将 `:coresdk` 再拆成 api/native 两个 module
- 保留 app 内 `native-lib.cpp` 示例

## 10. 实现顺序（供后续计划引用）

1. 新建 `:core`，接入 mbedTLS，实现 C ABI 与内置密钥
2. 新建 `:coresdk`，JNI + `CryptoSdk`
3. `:app` 依赖 coresdk，改 MainActivity demo
4. 仪器测试三条用例
5. 删除 app 旧 native 代码
