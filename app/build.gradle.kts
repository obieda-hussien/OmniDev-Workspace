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
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ── NDK / llama.cpp native inference ──────────────────────────────
        // arm64-v8a covers all modern Android phones; x86_64 keeps emulators working.
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

    // CMake entry point — path is relative to the module root (app/)
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            // TODO: Replace with production release keystore before Google Play publishing
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

// ── Auto-initialise llama.cpp git submodule before native build ──────────────
// If the submodule directory is empty (fresh clone, CI without --recursive),
// run `git submodule update` automatically so CMake finds the real llama.cpp
// source and compiles the inference-capable library instead of the stub.
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
                "The stub library will be compiled — on-device inference will not be available. " +
                "To fix: run `git submodule update --init --recursive` manually."
            )
        }
    }
}

// Hook into every task that configures or runs the CMake / native build.
tasks.configureEach {
    if (name.startsWith("configureCMake") ||
        name.startsWith("buildCMake") ||
        name.startsWith("externalNativeBuild")) {
        dependsOn(initLlamaCppSubmodule)
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Shizuku — privileged shell / ADB command execution (power-user tools)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // Chrome Custom Tabs — used for OAuth 2.0 browser-based auth flows
    implementation(libs.androidx.browser)

    // Jsoup — HTML parsing for web scraper tool (HTML → token-optimized Markdown)
    implementation(libs.jsoup)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
