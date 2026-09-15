---
name: omnidev-android-engineering
description: Kotlin, Jetpack Compose, coroutines, Room, Gradle flavors, and modular Android architecture guidance specialized for OmniDev-Workspace. Use for Android implementation, refactoring, build failures, persistence, migrations, flavor isolation, or architecture changes.
---

# OmniDev Android Engineering

Preserve the repository's tier boundaries and verify Android changes across affected variants, not just the easiest flavor.

## Architecture

Keep UI state/event handling in Compose/ViewModels, domain orchestration outside composables, persistence behind repositories/DAOs, and privileged implementations behind policy/facade boundaries. Avoid adding more direct dependencies to the already broad `CompositeToolManager`; prefer domain contributions/facades when refactoring.

Respect the intended module/tier split: shared core must not depend on privileged implementations; Lite must remain Play-Store-safe; Norm uses standard capabilities; Pro may include advanced/Shizuku tooling; OEM/Admin privilege backends must not be assumed equivalent to Pro.

## Kotlin and coroutines

Use structured concurrency. Tie UI jobs to `viewModelScope`/lifecycle, use `withContext(Dispatchers.IO)` for blocking I/O, preserve cancellation, and avoid launching unowned scopes for request work. Protect shared mutable state with appropriate synchronization/StateFlow rather than timing assumptions.

## Compose

Keep composables side-effect-safe, hoist durable state, use stable keys for lists, and launch external effects through `LaunchedEffect`/activity-result APIs. Do not perform file/network/database I/O directly during composition.

## Room

Schema changes require explicit migrations. Preserve foreign-key ordering, indices, defaults/nullability, and row counts when rebuilding tables. Never use destructive migration as a shortcut for user data. Test upgrade paths from realistically installed old versions, not only fresh databases.

## Change workflow

Search references/callers first, make the smallest coherent patch, compile the affected source sets, run unit/lint checks, and verify all impacted flavors. For flavor/policy work, confirm that forbidden classes/permissions do not leak into Lite/Norm artifacts.

## Definition of done

No unresolved compiler errors, migration mismatches, coroutine leaks introduced by the change, or unverified cross-flavor assumptions. Report exactly which variants/checks were executed.
