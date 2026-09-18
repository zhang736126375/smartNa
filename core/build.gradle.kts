plugins {
    id("com.android.library")
}

android {
    namespace = "com.bingo.core"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies { }
