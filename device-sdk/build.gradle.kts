plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val androidConfig = rootProject.extra["androidConfig"] as Map<*, *>

android {
    namespace = "com.grasp.device"
    compileSdk = (androidConfig["compileSdk"] as Number).toInt()

    defaultConfig {
        minSdk = (androidConfig["deviceMinSdk"] as Number).toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // 本地 AAR 不能用 files() 打进本模块，否则 bundleReleaseAar 会失败。
    // 拆成独立模块后，class 和 so 仍会随 api 传给依赖本模块的 App。
    api(project(":device-sdk:obsensor-aar"))
    api(project(":device-sdk:nng-aar"))
}
