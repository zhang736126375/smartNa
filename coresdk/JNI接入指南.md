# coresdk JNI 接入文档

coresdk 对外有两种 JNI 形态，**两种都可以用**，先看这次调用能不能在当前栈拿齐结果：

| 形态 | 何时用 | 样例 |
|------|--------|------|
| **方式一：同步返回** | Java 调下去，这个栈上就要完整结果 | `RoadConditionController.getRoadCondition`、`CryptoSdk.encrypt`；百度 getter |
| **方式二：异步消息总线** | 引擎稍后在别的线程把结果推回来（或引擎自己推） | `RoadConditionController.requestRoadCondition` + listener |

路况 Demo **两个按钮各打一条**，见 `DemoJniMsgActivity`。不要把加密、setter 也改成发 Message。

类注释见 `RoadConditionController`。加密交付包、路径约定见第 1～4 节。新增引擎回调从第 5 节抄。

---

## 命名说明

| 名称 | 是什么 |
|------|--------|
| **libcore-pack/** | C++ 同学交付目录（libcore.so + 头文件），JNI 只解压覆盖 |
| **coresdk/** | Android Library 模块（JNI + Kotlin），打成 AAR 给 App |

## 1. 目录约定

```
smartNa/
  libcore-pack/                     # C++ 交付包（不是 coresdk 模块）
    include/core/core_crypto.h
    lib/armeabi-v7a/libcore.so
    lib/arm64-v8a/libcore.so
  coresdk/                          # Android JNI SDK 模块
    src/main/cpp/jni/
      jni_crypto.h / jni_crypto.cpp
      jni_utils.h / jni_utils.cpp      # dispatch_vi_msg、JNI_OnLoad
      vmsg_constant.h                  # 消息号，与 Kotlin VMsgConstant 对齐
      jni_road_condition.*             # 路况：同步 get + 异步 request
    src/main/java/com/bingo/coresdk/
      CoreSdk.kt                       # Application 里 init，只 loadLibrary
      vi/                              # 消息总线（对标百度 mapautosdk.vi）
      route/                           # 路况业务单例
```

Gradle 自动：sync libcore-pack → CMake 读头文件/链 so → 打 AAR。

---

## 2. C++ 发版

1. 收到 zip，解压覆盖 `libcore-pack/`
2. Sync → `./gradlew :coresdk:assembleDebug`

---

## 3. 加同步 JNI 方法（方式一）

当前调用栈就能拿齐结果时用这个，**不要**上消息总线。

路况样例：`nativeGetRoadCondition` → [RoadConditionController.getRoadCondition]，返回 `RoadConditionResult`，listener 不会收到。

加解密同理：`jni_crypto.h` → `jni_crypto.cpp` → Kotlin `external`。不必改 `libcore-pack/`（除非底层新增 C 函数）。

App：`CoreSdk.init()` 之后直接调。异步请求见第 5 节。

---

## 4. 自定义路径

`local.properties`：

```properties
libcore.pack.dir=/path/to/libcore-pack
```

默认：`<工程根>/libcore-pack/`

---

## 5. 异步消息总线（方式二）

只给「当前 JNI 栈拿不齐结果」用。对标百度 `VMsgDispatcher` + `MsgHandler` + 业务 Controller 单例。

方式一（同步 return）见第 3 节，不要走本节。

**不要**在 native 里 `FindClass` 业务 Controller、不要 JNI 直接 `CallVoidMethod` 回调 Activity。引擎只认识一个 Java 入口：`VMsgDispatcher.dispatchMessage`。

### 5.1 两条链路（路况 Demo 两个按钮）

**方式一 `getRoadCondition`（同步，不走本节总线）**

```
getRoadCondition(cityId, roadName)
  └─ nativeGetRoadCondition → 当前栈 new Bundle → return RoadConditionResult
     listener 不会收到
```

**方式二 `requestRoadCondition`（异步）**

```
Application.onCreate
  └─ CoreSdk.init()                    // 只 System.loadLibrary

App
  └─ addListener(...)                  // 第一次碰到 object 时 init：register Handler
  └─ requestRoadCondition(...)         // JNI 立刻返回 taskId

C++ 工作线程
  ├─ AttachCurrentThread（JniThreadEnv）
  ├─ new Bundle
  └─ dispatch_vi_msg(MSG_XXX, errorCode, arg2, bundle)
        └─ VMsgDispatcher.dispatchMessage
              └─ 主线程 MsgHandler → IXxxListener
```

引擎自己推、Java 没 request 的事件（引导态、定位）没有「立刻 return 结果」，直接从工作线程 `dispatch_vi_msg`。

`dispatch_vi_msg` 四个参数对应 Java `Message`：

| C++ | Java Message |
|-----|----------------|
| `msg_id` | `what`（`VMsgConstant`） |
| `arg1` | `arg1`（错误码） |
| `arg2` | `arg2`（扩展） |
| `obj` | `obj`（一般是 `Bundle`） |

### 5.2 职责

| 层 | 类/文件 | 做什么 | 不做什么 |
|----|---------|--------|----------|
| 进程入口 | `CoreSdk` | load so | 不要在这里 register Handler |
| 消息号 | `VMsgConstant` + `vmsg_constant.h` | 两边同一 int | 不要只改一边 |
| 总线 | `VMsgDispatcher` | 按 what 分发给 Handler | 不解析业务 Bundle |
| Handler | `MsgHandler` 子类 | `careAbout` 里 observe | 页面级不要自己 new 一套 |
| 业务 SDK | `XxxController` object | register 一次、拆 Bundle、回调 listener | 不要 unregister（进程单例） |
| JNI 胶水 | `jni_xxx.cpp` | 组 Bundle + `dispatch_vi_msg` | 不要调 Activity |
| App | Activity / Fragment | add/remove **listener** | 不要 register/unregister Handler |

页面销毁只 `removeListener`。百度 `RouteServiceController` 也是进程单例，没有 `release`。

会随会话开关的组件（地图、引导）才 `unregisterMsgHandler`，和路况这种 SDK 服务不是一类。

### 5.3 新增一条引擎回调（照抄路况）

假设要加「天气更新」`MSG_WEATHER_INFO_UPDATE = 12005`。

1. **消息号**  
   - Kotlin `VMsgConstant.MSG_WEATHER_INFO_UPDATE = 12005`  
   - C++ `vmsg_constant.h` 同样 `12005`

2. **Bundle key**  
   - Kotlin `JniConstant`（或业务自己的 Constant）  
   - C++ `putInt` / `putString` 用同一字符串

3. **JNI**  
   - `jni_weather.cpp`：引擎线程里组 Bundle，`dispatch_vi_msg(MSG_WEATHER_INFO_UPDATE, error, 0, bundle)`  
   - `CMakeLists.txt` 的 `add_library(coresdk ...)` 追加该 cpp

4. **业务单例**（不要做成 Activity 字段去 new）

```kotlin
object WeatherController {
    private val handler = object : MsgHandler(Looper.getMainLooper()) {
        override fun careAbout() {
            observe(VMsgConstant.MSG_WEATHER_INFO_UPDATE)
        }
        override fun handleMessage(msg: Message) {
            if (msg.what != VMsgConstant.MSG_WEATHER_INFO_UPDATE) return
            // 拆 Bundle，回调 listeners
        }
    }
    init { VMsgDispatcher.registerMsgHandler(handler) }
}
```

第一次 `WeatherController.addListener` / `request` 就会跑 `init`，Handler 自己挂上。

5. **App**  
   `CoreSdk.init()` 已在 Application 做过。页面：

```kotlin
override fun initViewObservable() {
    WeatherController.addListener(listener)
}
override fun onDestroy() {
    WeatherController.removeListener(listener)
    super.onDestroy()
}
```

### 5.4 样例与对照

| 本仓库 | 百度 |
|--------|------|
| `getRoadCondition`（方式一，完整结果当场 return） | `getEvRangeOnRouteInfoByIdx` 填 Bundle 返回 |
| `requestRoadCondition` + listener（方式二） | `asyncRequestRoadCondition` + `MSG_SEARCH_ROAD_CONDITION_UPDATE` |
| `VMsgDispatcher` / `MsgHandler` | `com.baidu.mapautosdk.vi.*` |
| `VMsgConstant` / `vmsg_constant.h` | 同名消息号文件 |
| `CryptoSdk.encrypt` | 纯同步 JNI，与路况方式一同类 |
| `DemoJniMsgActivity` 两个按钮 | 业务方自己选同步 getter 或异步 listener |

跑一遍：首页 →「JNI 同步 / 异步 Demo」

- 方式一：马上出结果，`native 标记=sync-jni`，listener 不响
- 方式二：先显示 taskId，约 200ms 后 listener 出结果，`native 标记=rc-worker`
