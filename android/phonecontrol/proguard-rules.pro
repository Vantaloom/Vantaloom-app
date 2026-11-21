# 本库自身 release 构建（isMinifyEnabled=false 时不生效，留作将来开启 R8 时的底线）。
-keep class online.timefiles.vantaloom.phone.a11y.PhoneAccessibilityService { *; }
-keep class online.timefiles.vantaloom.phone.overlay.StopTaskReceiver { *; }
