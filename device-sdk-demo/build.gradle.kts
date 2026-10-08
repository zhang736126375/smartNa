plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val androidConfig = rootProject.extra["androidConfig"] as Map<*, *>

android {
    namespace = "com.grasp.devicesdk.demo"
    compileSdk = (androidConfig["compileSdk"] as Number).toInt()

    defaultConfig {
        applicationId = "com.grasp.devicesdk.demo"
        minSdk = (androidConfig["deviceMinSdk"] as Number).toInt()
        targetSdk = (androidConfig["deviceTargetSdk"] as Number).toInt()
        versionCode = (androidConfig["versionCode"] as Number).toInt()
        versionName = androidConfig["versionName"] as String
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    packaging {
        jniLibs {
            pickFirsts += setOf("**/libc++_shared.so")
        }
    }
}

dependencies {
    implementation(project(":device-sdk"))
}
