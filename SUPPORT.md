# Support and useful diagnostics

Start with the [README](README.md) and the guide for the feature you are configuring. Use repository issues for reproducible application problems and feature requests. Maintainer responses depend on availability; the project does not promise a support SLA.

## Where to ask

| Need | Route |
|---|---|
| Reproducible Android bug | Bug report issue form |
| New behavior or workflow | Feature request issue form |
| Build/toolchain/CI failure | Build and CI issue form |
| A documentation correction | Documentation issue form or a scoped PR |
| Security vulnerability/private data exposure | Private route in [SECURITY.md](SECURITY.md) |
| Code contribution | [CONTRIBUTING.md](CONTRIBUTING.md) and the PR template |

Search existing issues/PRs before opening a duplicate. Include links to an existing failed CI run or relevant PR when they provide the actual evidence.

## Android bug details

Include the application edition, app version/build or commit, device/OEM, Android version/API, phone language, orientation and exact reproduction. For chat/runtime problems include the selected mode, provider/model and whether tools are enabled. For assistant/device problems include relevant access states and whether Accessibility/Shizuku/root is actually connected.

A screenshot/video can explain a visual defect; redact private conversation text, notifications and credentials. If a device-specific behavior cannot be reproduced on an emulator, state that explicitly.

## Build details

Include the first actionable error, exact Gradle task, JDK and SDK/native versions, branch/commit and whether the submodule/dependency staging completed. A link to the failing workflow job is more useful than a truncated final `BUILD FAILED` line.

Separate download/network failure from a source compilation error. Do not post environment dumps, private signing configuration, token-bearing URLs or complete keystore-restore logs.

## Before reporting common setup problems

- Confirm the installed flavor permits the tool path and Android/backend permission is actually granted.
- Confirm the selected provider model/account accepts a small ordinary request.
- For GitHub, test **GitHub Agent Access** independently from Copilot/Models.
- For media, inspect the persisted card error and distinguish an unstarted queue from a known remote operation.
- For voice, confirm saved enrollment, explicit foreground listener start, local recognition model and offline TTS voice.
- For learned tasks, inspect the paused cursor and fresh target/expected-result identity.
- For OmniLink, verify both installed peers, real signing certificate, receiver consent and exact allowed capability.

Do not remove app data as an initial troubleshooting step unless you deliberately accept losing local installation records. Where possible retain a minimal redacted reproduction and inspect the specific failing store/permission.
