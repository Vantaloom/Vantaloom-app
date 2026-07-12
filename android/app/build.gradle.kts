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
