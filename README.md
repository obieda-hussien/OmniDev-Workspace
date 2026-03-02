# DevSwarm

[![Android CI](https://github.com/obieda-hussien/DevSwarm/actions/workflows/android-ci.yml/badge.svg)](https://github.com/obieda-hussien/DevSwarm/actions/workflows/android-ci.yml)

A modern Android application built with **Jetpack Compose**, developed under the package name `com.devswarm.ai`.

## Tech Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose + Material 3
- **Build System**: Gradle (Kotlin DSL)
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 35 (Android 15)
- **Architecture**: Single-Activity with Compose

## Getting Started

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17 or later
- Android SDK 35

### Build

```bash
# Debug build
./gradlew assembleDebug

# Run unit tests
./gradlew test

# Run lint checks
./gradlew lint
```

The debug APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

## CI/CD

This project uses **GitHub Actions** for continuous integration. On every push and pull request to `main`, the workflow automatically:

1. Runs **lint** checks
2. Executes **unit tests**
3. Builds the **debug APK**
4. Uploads the APK as a build artifact

See [`.github/workflows/android-ci.yml`](.github/workflows/android-ci.yml) for the full workflow definition.

## Project Structure

```
DevSwarm/
├── app/
│   └── src/
│       ├── main/
│       │   ├── java/com/devswarm/ai/
│       │   │   ├── MainActivity.kt
│       │   │   └── ui/theme/
│       │   ├── res/
│       │   └── AndroidManifest.xml
│       ├── test/          # Unit tests
│       └── androidTest/   # Instrumented tests
├── gradle/
│   └── libs.versions.toml
└── .github/
    └── workflows/
        └── android-ci.yml
```
