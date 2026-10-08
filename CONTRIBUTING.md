# Contributing to OmniDev Workspace

Thank you for helping improve the Android workspace, its runtime and its documentation. Contributions should describe a concrete problem, preserve the application's access boundaries and include evidence appropriate to the change.

Start with the [README](README.md), [development guide](docs/DEVELOPMENT.md), [project architecture](PROJECT_ARCHITECTURE.md) and [licensing status](LICENSE.md). The current Gradle project contains a single `:app` module and five product flavors.

## Before proposing a change

Search existing issues and pull requests for the same behavior. For a larger architectural change, explain the problem and affected interfaces in an issue before investing in an implementation. Use the feature-request form to describe the user journey, prerequisites and success criteria.

Report security vulnerabilities through [SECURITY.md](SECURITY.md), rather than placing exploit details or credentials in a public issue. Use [SUPPORT.md](SUPPORT.md) for ordinary setup and diagnostic questions.

## Local setup

```sh
git clone --recurse-submodules https://github.com/obieda-hussien/OmniDev-Workspace.git
cd OmniDev-Workspace
chmod +x gradlew
```

Use JDK 17 and the checked-in wrapper. The current SDK/native versions and flavor instructions are in [Development](docs/DEVELOPMENT.md). Keep `local.properties`, provider keys, signing files and integration sessions out of Git.

Use a focused branch such as `fix/assistant-window-state`, `feature/reviewed-routine-step` or `docs/provider-setup`. Prefer one coherent change over unrelated cleanup mixed with a behavioral fix.

## Implementation expectations

- Follow surrounding Kotlin/Compose conventions and existing dependency versions.
- Route privileged behavior through the bound `TierPolicy`, tool gate and backend authorization; avoid an isolated package-name or UI-only permission check.
- Keep tool names/schemas precise. Validate malformed arguments, disabled capability and uncertain outcomes as well as the successful path.
- Treat web, notification, repository, MCP and connected-app content as observations, not instructions granting permission.
- Keep source-backed evidence bounded and retain identifiers when compressing results.
- Separate local approval, actual Android permission and receiver-side consent.
- Preserve cancellation/revision identities so late results cannot overwrite a different turn or session.
- Use explicit verification after mutations. Service-start acceptance, a submitted intent or discovery result is not proof of the requested final state.
- Preserve existing data through migrations; changing a Room entity requires updating migration behavior.
- Respect lifecycle, keyboard insets, RTL and reduced motion on small devices.
- Keep credentials out of prompts, logs, recipe capture, screenshots and exported diagnostics where the private input contract requires that separation.
- Preserve upstream license/copyright notices and mark modifications without replacing original authorship.

The [tool execution contract](docs/TOOL_EXECUTION_CONTRACT.md), [learned-task guide](docs/LEARNED_TASKS.md), [device-lock contract](docs/DEVICE_LOCK_ACCESS.md) and [OmniLink integration](OMNILINK_V3_INTEGRATION.md) are important references for changes in those areas.

## Validation

Choose the smallest checks that cover the behavior, then expand when a failure or shared/flavor change warrants it. Do not add a test that only copies the implementation; test a meaningful outcome or regression.

```sh
./gradlew :app:compileNormDebugKotlin :app:testNormDebugUnitTest :app:lintNormDebug
python3 scripts/check_docs.py
python3 scripts/public_repo_guard.py
```

A documentation-only change normally needs the documentation checker and repository guard, not a claim of application/device testing. Shared policy, manifests and flavor source changes need the affected flavor checks. Native, UI, media, audio and keyguard changes need their respective APK/device evidence.

In a PR, distinguish completed checks from checks you could not run. Provider fixtures are not live-provider tests; an emulator is not a physical OEM keyguard. Include the limitation and the next acceptance check without calling an untested path verified.

## Pull requests

Use the PR template. Lead with the concrete problem and resulting behavior, then include:

- The trigger or reproduction and the before/after result.
- Relevant implementation choices and interface/data implications.
- Exact validation scope and outcome.
- Remaining device/provider checks or known limitations.
- Related issue/reference when applicable.

Use a descriptive title such as `Fix late media delivery after turn regeneration` rather than `Update files`. Screenshots are useful for visible layout changes; redact private content. Keep generated APKs, keystores, dependency caches and private logs out of the source diff.

When feedback changes the scope, update the title/description around the final implementation. Reviewers should understand the change without reading a conversational history of abandoned approaches.

## AI-assisted contributions

AI tools have played a substantial role in this project's development. See [Contributors and development history](CONTRIBUTORS.md). AI-assisted changes follow the same review and verification expectations as other contributions. Identify relevant assistance when it helps explain provenance, but do not treat a model's answer as test evidence.

Check generated API names, permission assumptions, source attributions and dependency terms against actual code/contracts. Maintainers remain responsible for what is accepted and distributed.

## Licensing and contribution terms

The repository has not adopted a repository-wide open-source license or a contributor license agreement. Do not assume an MIT, Apache or GPL grant for original Workspace code. Existing third-party licenses and notices remain applicable.

Make sure you have authority to submit your contribution and identify any imported third-party material and its terms. Discuss substantial externally authored code or a licensing change with the maintainer before submission. A PR does not justify silently relicensing existing code or removing notices.

## Community expectations

Keep reviews specific to behavior and evidence. Explain disagreements clearly, respect contributors and do not post private data. See [Code of conduct](CODE_OF_CONDUCT.md). Project communication and maintenance are handled by [Obieda](https://github.com/obieda-hussien); response times depend on availability.
