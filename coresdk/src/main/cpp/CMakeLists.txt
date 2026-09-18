# =============================================================================
# coresdk Native 构建脚本
# 作用：编译 libcoresdk.so（JNI 层），并链接 C++ 同学交付的 libcore.so（加密内核）
# 产物：libcoresdk.so；运行时还需一并打包 libcore.so（由 Gradle 从 libcore-pack 同步）
# =============================================================================

cmake_minimum_required(VERSION 3.22.1)
project(coresdk)

# -----------------------------------------------------------------------------
# LIBCORE_PACK_ROOT
# -----------------------------------------------------------------------------
# C++ 同学交付的 libcore-pack 根目录绝对路径（与 Android 模块 :coresdk 不是一回事）。
#
# 目录约定：
#   ${LIBCORE_PACK_ROOT}/
#     include/          # 头文件，可多个，CMake 已加整个 include/
#       core/core_crypto.h
#     lib/
#       armeabi-v7a/libcore.so
#       arm64-v8a/libcore.so
#
# 由 Gradle 传入：-DLIBCORE_PACK_ROOT=...
# 路径来源：local.properties 的 libcore.pack.dir，未配则默认 <工程根>/libcore-pack/
# -----------------------------------------------------------------------------
if(NOT DEFINED LIBCORE_PACK_ROOT)
    message(FATAL_ERROR "未设置 LIBCORE_PACK_ROOT。请用 Gradle 编译 coresdk；或 -DLIBCORE_PACK_ROOT=/path/to/libcore-pack")
endif()

# 当前 ABI 对应的预编译内核（ANDROID_ABI 由 NDK/CMake 自动提供）
set(CORE_SO "${LIBCORE_PACK_ROOT}/lib/${ANDROID_ABI}/libcore.so")
if(NOT EXISTS ${CORE_SO})
    message(FATAL_ERROR "未找到 ${CORE_SO}。请向 C++ 同学索取 libcore-pack 包，解压到 libcore-pack/ 目录")
endif()


# add_library(core SHARED IMPORTED)：作用是创建一个名为 core 的 CMake 目标（Target）。此时，这个目标在 CMake 内部被注册，但尚未绑定任何实际的 .so 文件路径。
# set_target_properties(...)：作用是修改已经存在的目标属性。它必须作用于一个已注册的目标。
# 必须先有“目标”，才能设置“目标的属性”。
#
# 将已有的 libcore.so 声明为「导入的共享库」，本脚本不编译它，只拿来链接
add_library(core SHARED IMPORTED)
set_target_properties(core PROPERTIES IMPORTED_LOCATION ${CORE_SO})

# 编译 JNI 层：jni_crypto.cpp + jni_utils.cpp + jni_road_condition.cpp → libcoresdk.so
# 新增 JNI 接口时在此追加 .cpp
add_library(coresdk SHARED
    jni/jni_crypto.cpp
    jni/jni_utils.cpp
    jni/jni_road_condition.cpp
)

# 头文件搜索路径：
#   jni/                     — jni_crypto.h、jni_utils.h、vmsg_constant.h
#   ${LIBCORE_PACK_ROOT}/include — C++ 交付头，如 core/core_crypto.h
target_include_directories(coresdk PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/jni
    ${LIBCORE_PACK_ROOT}/include
)

# 链接依赖库 链接：libcore.so + Android 系统库 log（JNI 里可用 __android_log_print）
target_link_libraries(coresdk core android log)
