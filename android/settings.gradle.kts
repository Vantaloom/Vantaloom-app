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
    }
}

rootProject.name = "Vantaloom"
include(":app")
// 手机操控（实验，feat/phone-control）：源自 vantaloom-computeruse-lab/android/phone-control。
// phonecontrol-core 是纯 Kotlin/JVM（零依赖），phonecontrol 是 Android 库（无障碍服务 + 浮层 + loopback MCP）。
include(":phonecontrol-core")
include(":phonecontrol")
