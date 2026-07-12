// Root build script. Native Android/Gradle project (no Tauri, no Rust). The
// Android Gradle Plugin + Kotlin versions are pinned here and applied per-module.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
