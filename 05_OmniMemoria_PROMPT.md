# OmniMemoria — Integration Prompt
### Repo: obieda-hussien/OmniMemoria · 3 phases, this repo only

> Prerequisite: `01_OmniLinkSDK_PROMPT.md` has shipped a tagged release.

---

## Phase 1 — Add the JitPack dependency and wire the manifest

1. Add the JitPack repository to `settings.gradle.kts` (bare `maven { url = uri("https://jitpack.io") } }` — `OmniLinkSDK` is public, no credentials needed), then `implementation("com.github.obieda-hussien:OmniLinkSDK:v1.0.0")` (current tag) to `app/build.gradle.kts`.
2. Add the new `MediaLinkService` entry to the manifest with `android:permission="com.omnilink.sdk.permission.BIND_EXTENSION"` and an intent-filter for `com.omnilink.sdk.action.EXTENSION_BIND`. The permission itself merges in automatically from the SDK's manifest (see `01_OmniLinkSDK_PROMPT.md` Phase 7).

**Acceptance criteria:** project compiles; no functional change yet.

---

## Phase 2 — Implement `MediaLinkService` (Vault explicitly excluded)

1. Create `MediaLinkService : ExtensionService` wrapping `MediaStoreRepository`, `FavoritesRepository`, and `TrashRepository` only. Set `override val securityValidator = SignatureSecurityValidator(setOf(<shared ecosystem signing certificate's SHA-256 — the canonical value documented in `02_OmniDev-Workspace_PROMPT.md` Phase 8>))` explicitly — **this app must be signed with that same shared keystore, or it will fail to install (`INSTALL_FAILED_DUPLICATE_PERMISSION`) once any other app consuming this SDK is already on the device** — required, since the base class defaults this to `null`.
2. `manifest()` returns a `CapabilityManifest` listing: `searchPhotos(query, dateRange?)`, `getFavorites()`, `createAlbum(name, ids)`, `moveToTrash(ids)`. Real MediaStore queries can be slow on a large library — make sure Workspace's caller reaches these through `executeActionAsync`, per the SDK's Phase 7 guidance, not the synchronous path.
3. `VaultRepository` (Decoy Vault) must never be reachable from `onAction()`. Implement this as a hard-coded rejection at the top of `onAction()` — reject any action name prefixed `vault_` and reject any attempt to route a generic action toward vault-backed data, before the request reaches any dispatch logic. This should be structurally impossible to bypass by adding a new action later, not just a naming convention developers are expected to follow.
4. Mark `moveToTrash` (and any other destructive action) with `requiresConfirmation: true` in its `CapabilityDescriptor`. **This app itself cannot show a confirmation dialog to the user on Workspace's behalf** — the actual prompt happens client-side in Workspace, which checks this exact manifest flag before ever calling `moveToTrash` (see `01_OmniLinkSDK_PROMPT.md` Phase 7 and `02_OmniDev-Workspace_PROMPT.md` Phase 2 for the full flow). This app's own `AccessController` can still independently return `REQUIRES_CONFIRMATION` as a fallback if it judges a call risky beyond what the static manifest declared, but the primary mechanism is the manifest flag Workspace reads upfront.

**Acceptance criteria:** agent can search, favorite-list, album, and trash visible library items via the bound service; the service has no import path, direct or transitive, to `VaultRepository`.

---

## Phase 3 — Verification

1. Unit test: assert `onAction("vault_anything", ...)` and any other vault-targeting payload returns a rejection, not a "not found."
2. Unit test: confirm `VaultRepository` is not referenced anywhere in `MediaLinkService`'s compiled class (a simple bytecode/import check is enough — the point is provable exclusion, not just a passing test).
3. Unit test: confirm `moveToTrash`'s `CapabilityDescriptor` in the returned manifest has `requiresConfirmation: true` — this repo can't test that a real `ConfirmationGate` dialog appears (that's a Workspace-side UI behavior, verified in `02_OmniDev-Workspace_PROMPT.md`'s own Phase 2 acceptance criteria), but it can and should verify the flag Workspace's confirmation check depends on is actually set correctly.

**Acceptance criteria:** all three tests pass; a code reviewer can confirm vault exclusion by inspecting imports alone, without needing to trust runtime behavior.
