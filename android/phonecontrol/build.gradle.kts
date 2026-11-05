// :android —— 安卓 computer use 的平台层（无障碍服务、驱动、浮层、宿主生命周期）。
// 工具链与 Vantaloom-app 一致（AGP 8.5.2 / Kotlin 1.9.24），并入正式 APK 时整包搬过去即可。
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "online.timefiles.vantaloom.phone"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        // 库模块不设 targetSdk（由宿主 APK 决定）；消费方混淆规则随 AAR 一起带走。
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // 字节码目标 17：与 :core 保持一致（core 也是 17），宿主 APK 用 21 也能消费。
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        // 浮层与 demo 界面全部代码构建，不需要 viewBinding/compose；BuildConfig 用来区分 debug 日志。
        buildConfig = true
    }

    // 源码放在 src/main/kotlin：显式加进源集，不依赖 KGP 的隐式约定。
    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

dependencies {
    api(project(":phonecontrol-core"))
    implementation("androidx.core:core-ktx:1.13.1")
}
