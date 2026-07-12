# R8/ProGuard rules. Minification is disabled for the release build (see
# app/build.gradle.kts), so these are belt-and-suspenders in case it is ever
# enabled.

# Keep the gomobile-generated overlay facade + its JNI support runtime intact.
-keep class online.timefiles.mobile.** { *; }
-keep class go.** { *; }

# Keep the JS bridge exposed to the WebView via addJavascriptInterface.
-keepclassmembers class online.timefiles.vantaloom.LoomJsBridge {
    @android.webkit.JavascriptInterface <methods>;
}
