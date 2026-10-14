pluginManagement {
    buildscript {
        repositories {
            // Official R8 releases; this exact version is not in Google Maven.
            maven {
                url = uri("https://storage.googleapis.com/r8-releases/raw")
                content { includeModule("com.android.tools", "r8") }
            }
            google()
            mavenCentral()
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
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // CI stages OmniLink here once with retry/backoff to avoid transient JitPack 429/timeouts.
        maven { url = uri(rootDir.resolve("ci-m2")) }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "OmniDevWorkspace"
include(":app")

include(":oem-system-contract", ":oem-system")
