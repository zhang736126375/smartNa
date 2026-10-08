pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://developer.huawei.com/repo/") }
    }
}

rootProject.name = "smartNa"
include(":app")
include(":base-common")
include(":device-sdk")
include(":device-sdk:obsensor-aar")
include(":device-sdk:nng-aar")
include(":device-sdk-demo")
