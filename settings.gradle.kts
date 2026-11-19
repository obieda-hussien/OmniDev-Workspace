// CI can retry downloads through another Central endpoint or Google Cloud's Central mirror.
pluginManagement {
    val centralFallback = System.getenv("OMNI_MAVEN_CENTRAL_FALLBACK")
    resolutionStrategy {
        eachPlugin {
            if (centralFallback in setOf("1", "2") && requested.id.id == "com.google.devtools.ksp") {
                // Reuse the pinned implementation even if the plugin marker is unavailable.
                requested.version?.let { version ->
                    useModule("com.google.devtools.ksp:symbol-processing-gradle-plugin:$version")
                }
            }
        }
    }

    buildscript {
        repositories {
            // Official R8 releases; this exact version is not in Google Maven.
            maven {
                url = uri("https://storage.googleapis.com/r8-releases/raw")
                content { includeModule("com.android.tools", "r8") }
            }
            google()
            mavenCentral {
                when (System.getenv("OMNI_MAVEN_CENTRAL_FALLBACK")) {
                    "1" -> url = uri("https://repo1.maven.org/maven2/")
                    "2" -> url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
                    else -> Unit
                }
            }
        }
        dependencies {
            // Kotlin 2.4 metadata requires R8 9.1.29; AGP 8.10 bundles an older R8.
            // This is a build compiler, not an application runtime dependency.
            // https://developer.android.com/build/kotlin-support
            classpath("com.android.tools:r8:9.1.29")
        }
    }
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral {
            when (System.getenv("OMNI_MAVEN_CENTRAL_FALLBACK")) {
                "1" -> url = uri("https://repo1.maven.org/maven2/")
                "2" -> url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
                else -> Unit
            }
        }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral {
            when (System.getenv("OMNI_MAVEN_CENTRAL_FALLBACK")) {
                "1" -> url = uri("https://repo1.maven.org/maven2/")
                "2" -> url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
                else -> Unit
            }
        }
        // CI stages OmniLink here once with retry/backoff to avoid transient JitPack 429/timeouts.
        maven { url = uri(rootDir.resolve("ci-m2")) }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "OmniDevWorkspace"
include(":app")

include(":oem-system-contract", ":oem-system")
include(":neural-core")
// Standalone test APK only; normal builds do not configure this experiment.
if (providers.gradleProperty("includeNeuralAndroidTests").orNull == "true") {
    include(":neural-android")
}
