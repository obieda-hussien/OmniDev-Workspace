# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# ── Preserve line numbers in stack traces for crash debugging ─────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── kotlinx.serialization ─────────────────────────────────────────────────────
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
-keep class com.omnidev.workspace.data.localllm.BitnetInferenceEngine { *; }
-keep class com.omnidev.workspace.data.localllm.BitnetInferenceEngine$* { *; }
-keep class com.omnidev.workspace.data.localllm.LocalEngineType { *; }
-keep class com.omnidev.workspace.data.localllm.LocalEngineHolder { *; }

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

# ── Deep Research & Advanced Tools ─────────────────────────────────────────────
-keep class com.omnidev.workspace.data.tools.WebScraperTool { *; }
-keep class com.omnidev.workspace.data.tools.HeadlessBrowserManager { *; }
-keep class com.omnidev.workspace.data.tools.AdvancedFileTools { *; }

# ── Jsoup (HTML parser) ──────────────────────────────────────────────────────
-dontwarn org.jsoup.**
-keep class org.jsoup.** { *; }

# ── Retrofit & OkHttp (إضافة حيوية لمنع كراش الشبكات) ────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature, Exceptions
-keepclasseswithmembers interface * {
    @retrofit2.http.* <methods>;
}
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# ── DataStore & Protobuf ──────────────────────────────────────────────────────
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

# ── Voice Mode (VoiceManager + VoiceAssistantService) ──────────────────────────
-keep class com.omnidev.workspace.data.voice.VoiceManager { *; }
-keep class com.omnidev.workspace.data.voice.VoiceAssistantService { *; }
-keep enum com.omnidev.workspace.data.voice.VoiceManager$* { *; }
-keep enum com.omnidev.workspace.data.voice.VoiceAssistantService$* { *; }

# ── GitHub Device Flow & GitHub AI Models ─────────────────────────────────────
-keep class com.omnidev.workspace.data.auth.GitHubDeviceFlowManager { *; }
-keep class com.omnidev.workspace.data.auth.GitHubDeviceFlowManager$DeviceFlowState { *; }
-keep class com.omnidev.workspace.data.auth.GitHubDeviceFlowManager$DeviceFlowState$* { *; }

# ── Models (حماية أي Data Class بيستخدم للشبكات أو قواعد البيانات) ─────────────
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ── JGit / SLF4J ──────────────────────────────────────────────────────────────
-dontwarn javax.management.**
-dontwarn java.lang.management.**
-dontwarn org.slf4j.impl.StaticLoggerBinder
-keep class org.eclipse.jgit.** { *; }
-dontwarn java.lang.ProcessHandle
-dontwarn org.ietf.jgss.**

# ── Progressive Trust Engine & Tools (Phase 2) ────────────────────────────────
-keep class com.omnidev.workspace.data.brain.ProgressiveTrustEngine { *; }
-keep class com.omnidev.workspace.data.brain.ProgressiveTrustEngine$* { *; }
-keep class com.omnidev.workspace.data.tools.ProgressiveTrustTool { *; }
-keep class com.omnidev.workspace.data.tools.ScriptRunnerTool { *; }
-keep class com.omnidev.workspace.data.tools.ScriptRunnerTool$* { *; }
