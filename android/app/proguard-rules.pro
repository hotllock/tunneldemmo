# BouncyCastle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Native methods
-keep class com.tunnel.demo.tunneldemo.native.TunEngineBridge { *; }
-keepclassmembers class com.tunnel.demo.tunneldemo.native.TunEngineBridge {
    native <methods>;
}

# Keep data models for serialization
-keep class com.tunnel.demo.tunneldemo.model.** { *; }

# Keep services
-keep class com.tunnel.demo.tunneldemo.service.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# AndroidX
-dontwarn androidx.**
-keep class androidx.** { *; }
