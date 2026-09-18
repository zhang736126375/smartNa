# libcore-pack 交付包（由 C++ 同学维护）

「libcore-pack」= libcore.so 的交付包，与 Android 模块「coresdk」不同，不要混淆。

固定目录结构，发版时整包替换：

```
libcore-pack/
  include/core/core_crypto.h
  lib/armeabi-v7a/libcore.so
  lib/arm64-v8a/libcore.so
```

**JNI 同学**：收到 C++ 的 zip → 解压覆盖本目录 → Sync → Rebuild。

**可选配置**（默认就是工程根目录 `libcore-pack/`，一般不用配）：

```properties
libcore.pack.dir=/absolute/path/to/libcore-pack
```

参考：`libcore-pack.properties.example`
