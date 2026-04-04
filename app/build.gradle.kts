plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.omnidev.workspace"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.omnidev.workspace"
        minSdk = 24 // ممتاز، بيدعم أجهزة كتير، بس الوظائف الخارقة هتشتغل من 11+
        targetSdk = 35
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

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
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
    kotlinOptions {
        jvmTarget = "17"
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

dependencies {
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
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
