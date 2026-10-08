# Development guide

Use this guide with the [README](../README.md) and [CONTRIBUTING.md](../CONTRIBUTING.md). It describes the checked-in project, not a future modularization proposal.

## Project and prerequisites

The current settings include one Android Gradle module, `:app`, with `lite`, `norm`, `pro`, `oem` and `admin` flavors. The application packages `core`, `domain`, `data` and `ui` are layers within that module.

Use JDK 17, the checked-in Gradle wrapper and an Android SDK. Current configuration:

| Setting | Value |
|---|---|
| Gradle wrapper | 8.14.5 |
| Android Gradle Plugin | 8.10.1 |
| Kotlin Android/Compose | 2.4.10 |
| Kotlin serialization plugin | 2.4.20 |
| Compile / target / min API | 36 / 36 / 24 |
| CI Build Tools | 35.0.0 |
| CI NDK | 27.0.12077973 |
| CMake | 3.22.1 |
| OmniLink dependency | v3.0.0 |

Read `gradle/libs.versions.toml`, `app/build.gradle.kts` and `.github/workflows/android-ci.yml` when these values change. Keep SDK/signing local configuration out of Git.

## Checkout and dependencies

```sh
git clone --recurse-submodules https://github.com/obieda-hussien/OmniDev-Workspace.git
cd OmniDev-Workspace
chmod +x gradlew
git submodule update --init --recursive --depth 1
```

The pinned llama.cpp submodule is required for real native inference in eligible editions. Gradle's initialization fallback can create a stub if checkout fails; a native library alone does not prove real inference works.

Install required Android SDK components:

```sh
sdkmanager "platform-tools" "platforms;android-36" "build-tools;35.0.0" "cmake;3.22.1" "ndk;27.0.12077973"
```

Use Android tooling to accept licenses and select the SDK/JDK. `ANDROID_HOME` or an untracked `local.properties` can locate the SDK. CI uses helper scripts to recover selected CMake/NDK download failures; inspect their diagnostics before changing source to address an infrastructure error.

Stage the tagged OmniLink module artifacts when JitPack resolution is unreliable:

```sh
bash .github/scripts/fetch-omnilink.sh
```

The local `ci-m2` resolver is configured in settings. Do not commit its cache or replace the tagged dependency with an unverified binary.

## Common commands

| Goal | Command |
|---|---|
| Compile Standard Kotlin | `./gradlew :app:compileNormDebugKotlin` |
| Standard unit tests | `./gradlew :app:testNormDebugUnitTest` |
| Lite unit tests | `./gradlew :app:testLiteDebugUnitTest` |
| Standard lint | `./gradlew :app:lintNormDebug` |
| Standard debug APK | `./gradlew :app:assembleNormDebug` |
| Lite debug APK | `./gradlew :app:assembleLiteDebug` |
| Pro native debug APK | `./gradlew :app:assembleProDebug` |
| Standard release APK | `./gradlew :app:assembleNormRelease` |
| Standard release bundle | `./gradlew :app:bundleNormRelease` |
| Lite device/emulator tests | `./gradlew :app:connectedLiteDebugAndroidTest` |
| Standard device/emulator tests | `./gradlew :app:connectedNormDebugAndroidTest` |
| English documentation integrity | `python3 scripts/check_docs.py` |
| Current repository guard | `python3 scripts/public_repo_guard.py` |
| Full history guard | `python3 scripts/public_repo_guard.py --history` |
| Tracked-source metrics | `python3 scripts/repo_metrics.py` |

Lite/Standard disable local llama.cpp inference build tasks and use the arm64 app ABI configuration. Pro/OEM/Admin enable native inference paths and include arm64/x86_64. CI instrumentation uses its own emulator/test setup; do not infer native-model support from the Lite UI test configuration.

For a constrained machine, select one variant and one meaningful suite before running all flavors/native releases. CI uses one Gradle worker and in-process Kotlin compilation with explicit memory settings; a developer's available RAM may require different heap limits. Do not copy a 5 GB heap onto a machine that cannot accommodate it.

## Source navigation

Start from `WorkspaceChatRuntime`, `ChatViewModel`, `AgentPipeline`, `SwarmOrchestrator` and `CompositeToolManager` for request execution. Review `TierPolicy`, `TierToolGate` and the relevant backend/receiver for access changes.

Use `data/routines/` for recipes, `data/chatmedia/` for media work, `data/repo/` for local code context, `data/ipc/` for connected apps and `data/assistant/`/`ui/assistant/` for screen/voice behavior. [Project files](PROJECT_FILES.md) provides the broader navigation map.

Flavor policies/manifests live under `app/src/<flavor>/`; shared sources include `liteNorm`, `proOem` and `proOemAdmin`. Moving a file between source sets can change several variants even if its body is unchanged.

## Validation boundaries

A shared source check should compile/test the relevant consumers. A Room change should increment/update the schema/migration contract and verify an old database upgrade. A tool change should exercise wrong names/arguments, denied capability, failure outcomes and current-state verification as appropriate.

UI acceptance should include small displays, keyboard expansion, RTL phone language, rotation, history reading during streaming and reduced motion. CI has an API-30 UI job, but assistant windows, native keyguards, local voice, gallery/codec behavior and privileged backends need their own device coverage.

Provider contract tests should isolate credentials and avoid billable live calls. To claim live compatibility, identify the actual account/model/device test and its result. An intercepted request fixture alone cannot establish quota/access or external job completion.

A useful verification report is:

```text
Validated: Standard Kotlin compile and the focused regression suite.
Not run: full five-flavor release build and physical-device keyguard behavior.
Required acceptance: install the signed build and verify the target OEM keypad path.
```

Use exact results for your change; this is a reporting example, not a preapproved assertion.

## Signing and installed identities

The README lists the `OMNI_SHARED_DEBUG_*` and `OMNI_SHARED_RELEASE_*` configuration names. Supply them through untracked local configuration or protected CI. Never put passwords/keys in commands pasted into an issue or public build log.

Non-Admin release builds can use a debug fallback when an explicit release signer is absent. Inspect the actual signer before distribution. Same-signer privileged OmniLink integration requires compatible installed apps using the intended certificate, plus receiver consent/policy. Copying an AAR or application ID does not grant that authority.

Admin release needs explicit shared release credentials or deliberate unsigned internal packaging. An unsigned output needs local signing before installation. Do not add Admin APKs to public artifact paths as part of a build cleanup.

## Documentation maintenance

Update operational guides when behavior, provider controls or permissions change. Keep planned architecture separate from current implementation and name the scope of validation. The documentation checker requires Python 3 and PyYAML (`python3 -m pip install PyYAML` in your development environment). It validates local links/anchors in the English entry documents, issue-form structure and the repository metadata manifest; it does not fetch external websites or validate runtime claims automatically.

See [Repository maintenance](REPOSITORY_MAINTENANCE.md) for metadata/labels and [Security](../SECURITY.md) for reporting and guard limitations.
