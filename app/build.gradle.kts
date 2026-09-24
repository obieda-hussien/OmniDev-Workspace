import java.util.Properties

plugins {
    alias(libs.plugins.owasp.dependencycheck)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val omniLocalProperties = Properties().apply {
    val local = rootProject.file("local.properties")
    if (local.isFile) {
        local.inputStream().use { stream -> load(stream) }
    }
}

fun omniSigningValue(name: String): String? =
    providers.gradleProperty(name).orNull
        ?: providers.environmentVariable(name).orNull
        ?: omniLocalProperties.getProperty(name)

val omniSharedDebugSigning = listOf(
    "OMNI_SHARED_DEBUG_STORE_FILE",
    "OMNI_SHARED_DEBUG_STORE_PASSWORD",
    "OMNI_SHARED_DEBUG_KEY_ALIAS",
    "OMNI_SHARED_DEBUG_KEY_PASSWORD"
).map(::omniSigningValue)

val omniSharedReleaseSigning = listOf(
    "OMNI_SHARED_RELEASE_STORE_FILE",
    "OMNI_SHARED_RELEASE_STORE_PASSWORD",
    "OMNI_SHARED_RELEASE_KEY_ALIAS",
    "OMNI_SHARED_RELEASE_KEY_PASSWORD"
).map(::omniSigningValue)

// The private CI may intentionally build Admin unsigned when the protected
// release keystore is unavailable or invalid. This is opt-in and is only used
// by the dedicated Admin job; normal release builds keep their existing signer.
val omniAdminUnsignedRelease =
    omniSigningValue("OMNI_ADMIN_UNSIGNED_RELEASE")
        ?.toBooleanStrictOrNull()
        ?: false

android {
    namespace = "com.omnidev.workspace"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.omnidev.workspace"
        minSdk = 24 // ممتاز، بيدعم أجهزة كتير، بس الوظائف الخارقة هتشتغل من 11+
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ── NDK / llama.cpp native inference ──────────────────────────────
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17 -O3"
                arguments += listOf(
                    "-DLLAMA_NATIVE=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // 5-Tier Product Flavors (tier dimension)
    // ══════════════════════════════════════════════════════════════════════════════
    //  lite  → Google Play B2C Free (scrubbed; NO root/Shizuku/Accessibility, but
    //          now includes Persistent Memory & Vector Knowledge Base tools)
    //  norm  → B2C Basic (Accessibility + Terminal, no root, no Shizuku)
    //  pro   → B2C Premium God Mode (Shizuku + Pentesting + full Swarm)
    //  oem   → B2B OEM / Custom ROM (system uid, zero-click, local SLM, no root/Shizuku)
    //  admin → 🔑 MASTER KEY (lead-developer-only testing build). EVERY capability
    //          is unlocked: root + Shizuku + Accessibility + Deep Security +
    //          Device Admin wipe + Local SLM + System Integration + zero-click
    //          auto-approval. NEVER distributed — used for rapid end-to-end QA.
    // ══════════════════════════════════════════════════════════════════════════════
    flavorDimensions += "tier"

    productFlavors {
        create("lite") {
            dimension = "tier"
            // lite keeps the canonical Play Store applicationId (no suffix)
            versionNameSuffix = "-lite"
            resValue("string", "app_name", "OmniDev Lite")

            buildConfigField("String",  "TIER",                     "\"LITE\"")
            buildConfigField("boolean", "ALLOW_ROOT",               "false")
            buildConfigField("boolean", "ALLOW_SHIZUKU",            "false")
            buildConfigField("boolean", "ALLOW_ACCESSIBILITY",      "false")
            buildConfigField("boolean", "ALLOW_DEEP_SECURITY",      "false")
            buildConfigField("boolean", "AUTO_APPROVE_CONFIRMATIONS","false")
            buildConfigField("boolean", "ALLOW_DEVICE_ADMIN_WIPE",  "false")
            buildConfigField("boolean", "ENABLE_LOCAL_SLM",         "false")
            buildConfigField("boolean", "ALLOW_SYSTEM_INTEGRATION", "false")

            // Smaller APK for Play Store; only arm64 (main production ABI)
            ndk {
                abiFilters.clear()
                abiFilters += "arm64-v8a"
            }
            // llama.cpp is completely disabled for lite — native build is gated in afterEvaluate
            externalNativeBuild {
                cmake {
                    // Sentinel flag picked up below to skip CMake entirely
                    arguments += "-DOMNIDEV_DISABLE_NATIVE=ON"
                }
            }
        }

        create("norm") {
            dimension = "tier"
            applicationIdSuffix = ".norm"
            versionNameSuffix   = "-norm"
            resValue("string", "app_name", "OmniDev Standard")

            buildConfigField("String",  "TIER",                     "\"NORM\"")
            buildConfigField("boolean", "ALLOW_ROOT",               "false")
            buildConfigField("boolean", "ALLOW_SHIZUKU",            "false")
            buildConfigField("boolean", "ALLOW_ACCESSIBILITY",      "true")
            buildConfigField("boolean", "ALLOW_DEEP_SECURITY",      "false")
            buildConfigField("boolean", "AUTO_APPROVE_CONFIRMATIONS","false")
            buildConfigField("boolean", "ALLOW_DEVICE_ADMIN_WIPE",  "false")
            buildConfigField("boolean", "ENABLE_LOCAL_SLM",         "false")
            buildConfigField("boolean", "ALLOW_SYSTEM_INTEGRATION", "false")

            ndk {
                abiFilters.clear()
                abiFilters += "arm64-v8a"
            }
            externalNativeBuild {
                cmake {
                    arguments += "-DOMNIDEV_DISABLE_NATIVE=ON"
                }
            }
        }

        create("pro") {
            dimension = "tier"
            applicationIdSuffix = ".pro"
            versionNameSuffix   = "-pro"
            resValue("string", "app_name", "OmniDev Pro")

            buildConfigField("String",  "TIER",                     "\"PRO\"")
            buildConfigField("boolean", "ALLOW_ROOT",               "true")
            buildConfigField("boolean", "ALLOW_SHIZUKU",            "true")
            buildConfigField("boolean", "ALLOW_ACCESSIBILITY",      "true")
            buildConfigField("boolean", "ALLOW_DEEP_SECURITY",      "true")
            buildConfigField("boolean", "AUTO_APPROVE_CONFIRMATIONS","false")
            buildConfigField("boolean", "ALLOW_DEVICE_ADMIN_WIPE",  "true")
            buildConfigField("boolean", "ENABLE_LOCAL_SLM",         "true")
            buildConfigField("boolean", "ALLOW_SYSTEM_INTEGRATION", "false")
            // Pro keeps the full ABI set for native llama.cpp inference
        }

        create("oem") {
            dimension = "tier"
            applicationIdSuffix = ".oem"
            versionNameSuffix   = "-oem"
            resValue("string", "app_name", "OmniDev OEM")

            buildConfigField("String",  "TIER",                     "\"OEM\"")
            buildConfigField("boolean", "ALLOW_ROOT",               "false") // OEM uses system uid, not su
            buildConfigField("boolean", "ALLOW_SHIZUKU",            "false")
            buildConfigField("boolean", "ALLOW_ACCESSIBILITY",      "true")
            buildConfigField("boolean", "ALLOW_DEEP_SECURITY",      "false")
            buildConfigField("boolean", "AUTO_APPROVE_CONFIRMATIONS","true")  // ← ZERO-CLICK EXECUTION
            buildConfigField("boolean", "ALLOW_DEVICE_ADMIN_WIPE",  "false") // OEM policy: wipe disabled
            buildConfigField("boolean", "ENABLE_LOCAL_SLM",         "true")  // Offline llama.cpp
            buildConfigField("boolean", "ALLOW_SYSTEM_INTEGRATION", "true")  // android.uid.system
        }

        // ──────────────────────────────────────────────────────────────────────
        //  🔑 ADMIN — Master-key developer testing build. EVERY flag is true.
        //  NOT distributed to any end-user. Used exclusively by the lead
        //  developer for rapid end-to-end QA of every tool, every permission,
        //  every privileged path. Zero restrictions, zero confirmation prompts.
        // ──────────────────────────────────────────────────────────────────────
        create("admin") {
            dimension = "tier"
            // Unique verified package identity; same signing key alone cannot identify Admin.
            applicationIdSuffix = ".admin"
            // applicationIdSuffix = ".admin"
            versionNameSuffix   = "-admin"
            resValue("string", "app_name", "OmniDev Admin")

            buildConfigField("String",  "TIER",                     "\"ADMIN\"")
            buildConfigField("boolean", "ALLOW_ROOT",               "true")
            buildConfigField("boolean", "ALLOW_SHIZUKU",            "true")
            buildConfigField("boolean", "ALLOW_ACCESSIBILITY",      "true")
            buildConfigField("boolean", "ALLOW_DEEP_SECURITY",      "true")
            buildConfigField("boolean", "AUTO_APPROVE_CONFIRMATIONS","true")  // ← ZERO-CLICK (rapid QA)
            buildConfigField("boolean", "ALLOW_DEVICE_ADMIN_WIPE",  "true")
            buildConfigField("boolean", "ENABLE_LOCAL_SLM",         "true")  // Offline llama.cpp
            buildConfigField("boolean", "ALLOW_SYSTEM_INTEGRATION", "true")  // Enable all system hooks
            // ADMIN keeps the full ABI set (arm64-v8a + x86_64) inherited from
            // defaultConfig and fully participates in externalNativeBuild / CMake
            // so that llama.cpp native inference is compiled for this variant.
        }
    }

    // ── Optional shared source folders ────────────────────────────────────────
    // liteNorm/:      code shared by both consumer tiers (lite + norm)
    // proOem/:        code shared by both privileged tiers (pro + oem)
    // proOemAdmin/:   code shared by ALL privileged tiers (pro + oem + admin) —
    //                 e.g. SLM bootstrapping, privileged execution facade adapters.
    //                 Admin is treated as the "super-set" of pro + oem so it
    //                 inherits every high-privilege facade automatically.
    sourceSets {
        getByName("lite").java.srcDir("src/liteNorm/java")
        getByName("norm").java.srcDir("src/liteNorm/java")
        getByName("pro").java.srcDir("src/proOem/java")
        getByName("oem").java.srcDir("src/proOem/java")
        getByName("pro").java.srcDir("src/proOemAdmin/java")
        getByName("oem").java.srcDir("src/proOemAdmin/java")
        getByName("admin").java.srcDir("src/proOemAdmin/java")
    }

    signingConfigs {
        if (omniSharedDebugSigning.all { !it.isNullOrBlank() }) {
            create("omniSharedDebug") {
                storeFile = rootProject.file(omniSharedDebugSigning[0]!!)
                storePassword = omniSharedDebugSigning[1]
                keyAlias = omniSharedDebugSigning[2]
                keyPassword = omniSharedDebugSigning[3]
            }
        }
        if (omniSharedReleaseSigning.all { !it.isNullOrBlank() }) {
            create("omniSharedRelease") {
                storeFile = rootProject.file(omniSharedReleaseSigning[0]!!)
                storePassword = omniSharedReleaseSigning[1]
                keyAlias = omniSharedReleaseSigning[2]
                keyPassword = omniSharedReleaseSigning[3]
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("omniSharedDebug")?.let { signingConfig = it }
        }
        release {
            signingConfig = if (omniAdminUnsignedRelease) {
                null
            } else {
                signingConfigs.findByName("omniSharedRelease")
                    ?: signingConfigs.findByName("omniSharedDebug")
                    ?: signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true // لتقليل حجم التطبيق بعد الـ Proguard
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        // تم الترقية لـ Java 17 (مطلوب لأندرويد 14+ و Compose الحديث)
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true // عشان تقدر تستخدم متغيرات الـ Build في الكود
    }

    packaging {
        jniLibs {
            // Replaces android:extractNativeLibs="false" in AndroidManifest.xml
            // (AGP 7.1+ preferred location for this flag)
            useLegacyPackaging = false
        }
    }
}

// Admin is a private developer identity. By default it must use the dedicated
// shared Omni release signer and must never silently fall back to the debug key.
// The private CI can explicitly request an unsigned Admin package for local
// offline signing by setting OMNI_ADMIN_UNSIGNED_RELEASE=true.
tasks.configureEach {
    val isAdminReleasePackagingTask =
        name == "packageAdminRelease" ||
            name == "assembleAdminRelease" ||
            name == "bundleAdminRelease"

    if (isAdminReleasePackagingTask) {
        doFirst {
            if (!omniAdminUnsignedRelease) {
                check(omniSharedReleaseSigning.all { !it.isNullOrBlank() }) {
                    "Admin release requires explicit OMNI_SHARED_RELEASE_* signing credentials, " +
                        "or OMNI_ADMIN_UNSIGNED_RELEASE=true for a deliberately unsigned package"
                }
            }
        }
    }

    if (omniAdminUnsignedRelease &&
        Regex("^(assemble|package|bundle)(Lite|Norm|Pro|Oem)Release$").matches(name)
    ) {
        doFirst {
            error("OMNI_ADMIN_UNSIGNED_RELEASE is restricted to Admin release tasks")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// ── Auto-initialise llama.cpp git submodule before native build ──────────────
val initLlamaCppSubmodule by tasks.registering {
    val marker = file("src/main/cpp/llama.cpp/CMakeLists.txt")
    onlyIf { !marker.exists() }
    doLast {
        try {
            exec {
                workingDir = rootProject.rootDir
                commandLine("git", "submodule", "update", "--init", "--recursive", "--depth", "1")
            }
            logger.lifecycle("llama.cpp submodule initialised — real native inference will be compiled.")
        } catch (e: Exception) {
            logger.warn(
                "Could not auto-init llama.cpp submodule (${e.message}). " +
                "The stub library will be compiled — on-device inference will not be available."
            )
        }
    }
}

tasks.configureEach {
    if (name.startsWith("configureCMake") ||
        name.startsWith("buildCMake") ||
        name.startsWith("externalNativeBuild")) {
        dependsOn(initLlamaCppSubmodule)
    }
}

// ── Skip native build entirely for lite + norm flavors ──────────────────────
// llama.cpp is ~40 MB of binaries and 4+ minutes of CMake. For the Play-Store
// (lite) and Standard (norm) tiers we don't ship local SLM inference, so we
// disable the entire externalNativeBuild pipeline for those flavor variants.
android.applicationVariants.configureEach {
    val flavor = productFlavors.firstOrNull()?.name
    if (flavor == "lite" || flavor == "norm") {
        val variantName = name.replaceFirstChar { it.uppercase() }
        tasks.matching { task ->
            val n = task.name
            n == "configureCMake${variantName}" ||
            n == "buildCMake${variantName}" ||
            n == "externalNativeBuild${variantName}" ||
            n == "configureCMakeDebug[arm64-v8a]${variantName}" ||
            n == "configureCMakeRelWithDebInfo[arm64-v8a]${variantName}" ||
            n.startsWith("configureCMake${variantName}") ||
            n.startsWith("buildCMake${variantName}") ||
            n.startsWith("externalNativeBuild${variantName}")
        }.configureEach {
            enabled = false
        }
    }
}

dependencies {
    // OmniLink typed IPC + embedded-agent gateway.
    // Stable tagged OmniLink protocol shared by Workspace and connected apps.
    implementation("com.github.obieda-hussien.OmniLinkSDK:omni-link-sdk:v2.0.1")

    implementation("androidx.webkit:webkit:1.17.0")
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.8.0.202311291450-r")

    // ── Core & Lifecycle ──
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // ── Compose BOM & UI ──
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // ── Window Management (للفقاعة العائمة والنوافذ) ──
    implementation(libs.androidx.window)

    // ── Navigation & ViewModel ──
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // ── Data & Storage (Room + DataStore) ──
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // ── Coroutines & Serialization ──
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // ── Shizuku (The God-Mode Key) ──
    // NOTE: Shizuku is currently on the `main` classpath (all flavors).
    // Runtime access is gated by TierPolicy.allowShizuku, and the ShizukuProvider
    // manifest entry is stripped from lite/norm/oem via tools:node="remove".
    // TODO(Commit 6): Physically relocate ShizukuCommandTool + 18 callers into
    //                 :tools:advanced so Shizuku is purely a `proImplementation`
    //                 dependency and cannot even be linked in lite/norm.
    //                 See PROJECT_ARCHITECTURE.md → Modularization Roadmap.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // ── Web Scraping & Auth ──
    implementation(libs.androidx.browser)
    implementation(libs.jsoup)

    // ==========================================================
    // 🚀 أسلحة الوكيل الذكي (AI Agent Libraries)
    // ==========================================================
    
    // 1. Networking (OkHttp/Retrofit) لخدمات التليجرام وجلب البيانات
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    
    // 2. WorkManager للعمليات المجدولة في الخلفية
    implementation(libs.androidx.work.runtime.ktx)
    
    // 3. CameraX & ML Kit (لتحليل الشاشة وقراءة النصوص OCR)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.text.recognition)

    // ── Testing ──
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
android {
    lint {
        abortOnError = false
    }
}

dependencyCheck {
    failOnError = false
    autoUpdate = false // prevent long downloads in CI without NVD key
}
