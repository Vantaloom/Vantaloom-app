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
        // targetSdk 28 is load-bearing (0.14.33): Android 10+ (targetSdk 29+)
        // forbids execve() of files in the app's writable data dir. The on-device
        // Linux sandbox (proot + a downloaded rootfs) MUST exec rootfs binaries
        // from writable storage, so — exactly like Termux — we target API 28,
        // the last level exempt from that W^X restriction. This APK is
        // self-distributed via GitHub Releases (not Play), so the low targetSdk
        // is cost-free; it also simplifies notifications/FGS/storage behavior.
        targetSdk = 28
        // CI only overrides versionCode so every APK remains upgradable. Keep
        // versionName tied to the product release instead of inventing a build suffix.
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = "0.15.10"

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
        // Keep runtime-engine dotfiles such as npm's .npmrc: they are covered
        // by the signed manifest and Kotlin verifies every declared asset.
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:!CVS:!thumbs.db:!picasa.ini:!*~"
    }

    packaging {
        jniLibs {
            // jniLibs 里的 libvantaloom.so（完整 vantaloom-api）与 libproot.so
            // （无 root 的 Linux 沙箱内核）都是伪装成 .so 的可执行文件，必须解压到
            // nativeLibraryDir 才能被 exec。useLegacyPackaging=true 关闭「从 APK
            // 内直接 mmap」优化、强制解压——这是 exec 型 .so 的标准打法。
            useLegacyPackaging = true
        }
    }

    lint {
        // targetSdk 28 是本项目沙箱刻意的选择（W^X 豁免，见 defaultConfig）。
        // AGP 的 lintVitalRelease 把 targetSdk<33 当致命错误（面向 Google Play
        // 的规则），会挡住 assembleRelease；本 APK 走 GitHub Release 自分发、
        // 不上架 Play，故显式关闭这两条与 targetSdk 版本相关的检查。
        disable += "ExpiredTargetSdkVersion"
        disable += "OldTargetApi"
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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
