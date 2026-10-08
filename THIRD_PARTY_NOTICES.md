# Third-party components and provenance

OmniDev Workspace depends on separately authored libraries, models, tools and connected applications. Their licenses, notices and source headers remain applicable. This document is a navigation inventory, not a replacement for the exact upstream license text or a completed transitive-license audit.

## Direct dependency and submodule references

| Component/family | Workspace role | Source/license reference |
|---|---|---|
| OmniLinkSDK | Typed IPC, capability and transport integration | [SDK repository](https://github.com/obieda-hussien/OmniLinkSDK), [LICENSE](https://github.com/obieda-hussien/OmniLinkSDK/blob/main/LICENSE), [NOTICE](https://github.com/obieda-hussien/OmniLinkSDK/blob/main/NOTICE.md) |
| llama.cpp | Native local inference submodule | [Upstream repository](https://github.com/ggml-org/llama.cpp); inspect the pinned submodule's license |
| Vosk API | Offline PCM speech recognition | [Upstream](https://github.com/alphacep/vosk-api); retained Apache-2.0 attribution described in [ATTRIBUTION.md](ATTRIBUTION.md) |
| JNA | Vosk/native Java access | [Upstream](https://github.com/java-native-access/jna); inspect the selected distribution's dual-license notices |
| AndroidX/Jetpack | Compose, lifecycle, navigation, Room, WorkManager, CameraX, WebKit and other Android components | [AndroidX project](https://android.googlesource.com/platform/frameworks/support/); inspect individual artifact notices |
| Kotlin/kotlinx | Language/runtime, coroutines and serialization | [Kotlin](https://github.com/JetBrains/kotlin), [coroutines](https://github.com/Kotlin/kotlinx.coroutines), [serialization](https://github.com/Kotlin/kotlinx.serialization) |
| Eclipse JGit | Embedded local and remote Git | [JGit project](https://projects.eclipse.org/projects/technology.jgit); inspect artifact notices |
| OkHttp/Retrofit | HTTP and API transport | [OkHttp](https://github.com/square/okhttp), [Retrofit](https://github.com/square/retrofit) |
| jsoup | HTML parsing/extraction | [Upstream](https://github.com/jhy/jsoup) |
| Shizuku | Eligible privileged Android backend | [Shizuku](https://github.com/RikkaApps/Shizuku), [API](https://github.com/RikkaApps/Shizuku-API) |
| ML Kit | Device text-recognition tooling | [Official ML Kit documentation](https://developers.google.com/ml-kit); review selected artifact/service terms |
| JSON-java and test libraries | JSON/testing support | [JSON-java](https://github.com/stleary/JSON-java), [JUnit](https://github.com/junit-team/junit4); inspect packaged artifacts |
| OWASP Dependency-Check | Optional dependency-analysis plugin | [Upstream](https://github.com/dependency-check/DependencyCheck) |
| Baileys and Node bridge dependencies | Personal same-phone WhatsApp bridge | [Bridge package manifest](whatsapp-bridge/package.json), [bridge guide](whatsapp-bridge/README.md), [Baileys](https://github.com/WhiskeySockets/Baileys) |

Exact versions are defined in `gradle/libs.versions.toml`, `app/build.gradle.kts`, the pinned submodule and `whatsapp-bridge/package.json`. Transitive libraries and binary distribution obligations must also be checked when producing a release.

## Downloaded models

The optional Vosk English/Arabic archives are downloaded only on explicit user action. Their bundled README/notices are retained; [ATTRIBUTION.md](ATTRIBUTION.md) documents the selected releases and upstream credit. Use the [official model catalog](https://alphacephei.com/vosk/models) and exact downloaded archive when reviewing terms.

A local chat-model weight file can have a different license from llama.cpp. Installing a compatible inference engine does not confer rights in every model or its training data. Do not attribute pretrained weights or their research to Workspace merely because the application can load them.

## Connected applications

Omni Launcher retains its Lawnchair/AOSP Launcher3 provenance. AndroidIDE and other connected applications retain their own upstream notices. Workspace's integration contribution is separate from those foundations; see [ATTRIBUTION.md](ATTRIBUTION.md).

Connecting an app does not itself vendor all of that app's source into this repository. If code or assets are copied in a future change, record their exact origin and terms alongside the contribution.

## Release review

Before redistributing an APK/bridge package, inspect selected versions, transitive dependencies, native binaries, assets and any included model weights. Preserve required license/notice files and source headers. This inventory deliberately does not label all dependencies with one inherited Workspace license.

The original Workspace code has no repository-wide open-source license grant today; [LICENSE.md](LICENSE.md) records that separate status.
