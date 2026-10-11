# -----------------------------------------------------------------------------
# CloudStream Hardened & Optimized - Production ProGuard / R8 Rules
# -----------------------------------------------------------------------------

# Keep line numbers and source files for readable crash logs
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# -----------------------------------------------------------------------------
# 1. Native JNI & Rust Core Bridge (cloudstream_core)
# -----------------------------------------------------------------------------
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.lagradost.cloudstream3.services.NativeCoreBridge {
    public *;
}

# -----------------------------------------------------------------------------
# 2. Zero-Trust Extension Sandbox & IPC Messenger
# -----------------------------------------------------------------------------
-keep class com.lagradost.cloudstream3.services.ExtensionSandboxService* {
    *;
}

-keep class com.lagradost.cloudstream3.services.ExtensionSandboxClient* {
    *;
}

# -----------------------------------------------------------------------------
# 3. CloudStream Core Plugin API & Data Models (Keep entire tree for runtime plugins)
# -----------------------------------------------------------------------------
-dontobfuscate
-keep class com.lagradost.cloudstream3.** { *; }
-keep class com.lagradost.nicehttp.** { *; }
-keep class com.lagradost.api.** { *; }

# -----------------------------------------------------------------------------
# 4. Data Serialization (Kotlinx Serialization, Jackson, Gson, Serializable)
# -----------------------------------------------------------------------------
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Kotlinx Serialization
-keepattributes *Annotation*,InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
-keepclassmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Jackson & Gson
-dontwarn com.fasterxml.jackson.**
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* *;
}
-dontwarn com.google.gson.**
-keepclassmembers class * {
    @com.google.gson.annotations.* *;
}

# -----------------------------------------------------------------------------
# 5. Media3 / ExoPlayer / Video Decoding
# -----------------------------------------------------------------------------
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }
-keep class com.lagradost.cloudstream3.ui.player.** { *; }

# -----------------------------------------------------------------------------
# 6. Networking & Image Loading (OkHttp, Coil, Cronet)
# -----------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn coil3.**
-dontwarn org.chromium.net.**

# -----------------------------------------------------------------------------
# 7. Kotlin Coroutines & Architecture Components
# -----------------------------------------------------------------------------
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { *; }
-keep class com.lagradost.cloudstream3.di.** { *; }
-keep class com.lagradost.cloudstream3.domain.** { *; }
-keep class com.lagradost.cloudstream3.utils.persistence.** { *; }
-keep class com.lagradost.cloudstream3.utils.telemetry.** { *; }
-keep class com.lagradost.cloudstream3.ui.coordinators.** { *; }