# Shizuku user service ------------------------------------------------------
# The service class is instantiated reflectively inside a shell process by the
# Shizuku server; R8 must never rename or strip it (including its Context ctor).
-keep class com.sabeeir.catchapp.shell.ShellUserService { *; }
-keep class com.sabeeir.catchapp.shell.ShellUserService$* { *; }

# Shizuku API ----------------------------------------------------------------
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep class dev.rikka.shizuku.** { *; }

# Hidden API bypass uses Unsafe; keep it intact.
-keep class org.lsposed.hiddenapibypass.HiddenApiBypass { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep <methods>;
}

# Kotlin / coroutines --------------------------------------------------------
-keepattributes *Annotation*, InnerClasses, Signature
-dontwarn kotlinx.coroutines.**
-dontwarn org.slf4j.**
