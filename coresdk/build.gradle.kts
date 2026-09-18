import java.util.Properties

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// libcore-pack：C++ 同学交付的 libcore.so + 头文件包（与 coresdk 模块不同）
// 默认路径：<工程根>/libcore-pack/，可在 local.properties 用 libcore.pack.dir 覆盖
val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localPropsFile.inputStream().use { localProps.load(it) }
}
val libcorePackDir = file(
    localProps.getProperty("libcore.pack.dir") ?: rootProject.file("libcore-pack").absolutePath
)

val syncedLibcoreJniLibs = layout.buildDirectory.dir("libcore-pack-jniLibs")

tasks.register<Copy>("syncLibcorePack") {
    group = "native"
    description = "从 libcore-pack 同步 libcore.so（C++ 同学交付物）"
    from(libcorePackDir.resolve("lib"))
    into(syncedLibcoreJniLibs)
    include("**/libcore.so")
    doFirst {
        if (!libcorePackDir.resolve("include/core/core_crypto.h").exists()) {
            throw GradleException(
                "缺少 ${libcorePackDir.absolutePath}/include/core/core_crypto.h，请向 C++ 同学索取完整 libcore-pack 包"
            )
        }
    }
}

android {
    namespace = "com.bingo.coresdk"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DLIBCORE_PACK_ROOT=${libcorePackDir.absolutePath}"
                )
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    sourceSets {
        getByName("main") {
            jniLibs.srcDir(syncedLibcoreJniLibs)
        }
    }
    packaging {
        jniLibs {
            pickFirsts += setOf("**/libc++_shared.so", "**/libcore.so")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
}

dependencies {
    implementation("androidx.annotation:annotation:1.7.1")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.6.1")
}

tasks.named("preBuild").configure { dependsOn("syncLibcorePack") }
