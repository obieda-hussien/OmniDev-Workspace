# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ── Preserve line numbers in stack traces for crash debugging ─────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── kotlinx.serialization ─────────────────────────────────────────────────────
# Keep serializable classes and their companions intact so JSON encode/decode works.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <methods>;
}
-keep,includedescriptorclasses class com.omnidev.workspace.**$$serializer { *; }
-keepclassmembers class com.omnidev.workspace.** {
    *** Companion;
}
-keepclasseswithmembers class com.omnidev.workspace.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Room Database ─────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-keepclassmembers class * extends androidx.room.RoomDatabase {
    abstract *;
}

# ── JNI / native methods (llama.cpp bridge) ───────────────────────────────────
# Keep all native method declarations so ProGuard doesn't rename or remove them.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.omnidev.workspace.data.localllm.LlamaCppInferenceEngine { *; }
-keep class com.omnidev.workspace.data.localllm.LlamaCppInferenceEngine$* { *; }

# ── Shizuku ──────────────────────────────────────────────────────────────────
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }

# ── Kotlin Coroutines / Flow ──────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ── Accessibility Service (Semantic UI) ───────────────────────────────────────
-keep class com.omnidev.workspace.data.accessibility.OmniAccessibilityService { *; }
-keep class com.omnidev.workspace.data.accessibility.AccessibilityStateManager { *; }
-keep class com.omnidev.workspace.data.accessibility.SemanticTreeParser { *; }
-keep class com.omnidev.workspace.data.accessibility.SemanticUITool { *; }
-keep class com.omnidev.workspace.data.accessibility.GodModeAccessibility { *; }

# ── App Manifest Analyzer & Vector Memory ─────────────────────────────────────
-keep class com.omnidev.workspace.data.tools.AppManifestAnalyzerTool { *; }
-keep class com.omnidev.workspace.data.tools.VectorMemoryManager { *; }

# ── OkHttp / Ktor (if used for HTTP) ─────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**

# ── DataStore ─────────────────────────────────────────────────────────────────
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
