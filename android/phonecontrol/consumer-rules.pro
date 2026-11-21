# 宿主 APK 开 R8 时：无障碍服务和广播接收器由系统按类名反射实例化，必须保留类名。
-keep class online.timefiles.vantaloom.phone.a11y.PhoneAccessibilityService { *; }
-keep class online.timefiles.vantaloom.phone.overlay.StopTaskReceiver { *; }
