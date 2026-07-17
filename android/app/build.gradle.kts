plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "online.timefiles.vantaloom"
    compileSdk = 34

    defaultConfig {
        applicationId = "online.timefiles.vantaloom"
        minSdk = 24
        targetSdk = 34
        // versionCode / versionName are overridable from CI (-PversionCode / -PversionName)
        // so every build is a distinct, upgradable artifact.
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.13.0"

        // The gomobile AAR ships a per-ABI .so; the CI binds android/arm64 only.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            // R8 OFF: the gomobile-generated JNI classes and the @JavascriptInterface
            // bridge must survive intact; keeping minification off is the simplest
            // guarantee (the APK is dominated by the 42MB web export + Go .so anyway).
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // No signingConfig here: the release APK is built UNSIGNED, then CI
            // zipaligns + apksigner-signs it with the keystore from GitHub secrets
            // (see .github/workflows/build-apk.yml).
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }

    androidResources {
        // AAPT's DEFAULT ignore pattern contains `<dir>_*` — it SILENTLY drops
        // asset directories whose name starts with "_", which is exactly the
        // Next.js export's `_next/` (the entire JS/CSS bundle). Build136 shipped
        // 40 asset entries instead of thousands and the app froze forever on the
        // prerendered spinner. This is the default pattern minus that one rule;
        // the CI workflow additionally asserts assets/_next/ made it into the APK.
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~"
    }

    packaging {
        jniLibs {
            // 本地运行时（0.14.29）：jniLibs 里的 libvantaloom.so 是完整的
            // vantaloom-api（纯 Go 可执行文件伪装成 .so），必须解压到
            // nativeLibraryDir 才能被 ProcessBuilder exec（Android 10+ 禁止从
            // 可写目录执行）。useLegacyPackaging=true 关闭「从 APK 内直接
            // mmap」优化、强制解压——这是 exec 型 .so 的标准打法。
            useLegacyPackaging = true
        }
    }

    // The compiled Next.js web export is copied into src/main/assets/ by CI (see
    // build-apk.yml) and served from a vantaloom.localhost origin by
    // WebViewAssetLoader. It is gitignored (42MB, lives in the repo's web/).
}

dependencies {
    // The loomnet overlay facade, built by `gomobile bind` in CI (app/libs/loomnet.aar).
    implementation(files("libs/loomnet.aar"))

    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
