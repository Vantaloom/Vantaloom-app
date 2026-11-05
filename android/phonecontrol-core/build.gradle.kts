import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm")
}

// 纯 Kotlin，零运行时依赖：在 Android 上作为普通 jar 被 :android 依赖，在 JVM 上可以直接单测。
// 不用 org.json（Android 平台自带、JVM 上要另加依赖，两边行为有差），用自带的 Json.kt。
// 字节码目标 17：本机 JDK 21 / CI JDK 21 都能编译，D8 也能吃。不用 jvmToolchain，免得去下载 JDK 17。
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
