# OmniNote — Integration Prompt
### Repo: obieda-hussien/OmniNote · 4 phases, this repo only

> Prerequisite: `01_OmniLinkSDK_PROMPT.md` has shipped a tagged release.
> **Phase 1 below is a hard blocker — do not proceed to Phase 2 until it ships and is verified independently.**

---

## Phase 1 — Fix plaintext locked-note storage (blocking)

1. Audit `NoteEntity` / `AppDatabase` / `NoteRepository` for where "Locked Notes" PIN and content are currently stored in plaintext.
2. Replace plaintext PIN storage with a proper hash (e.g. Argon2/PBKDF2 via `androidx.security.crypto`), never store the raw PIN.
3. Encrypt locked-note body content at rest. **Don't reach for `EncryptedSharedPreferences`** — Google deprecated it (along with `EncryptedFile`) in `androidx.security-crypto:1.1.0-alpha07`, and it's known for main-thread I/O stalls and Keystore-related corruption on some OEM devices. The current recommended pattern is **Jetpack DataStore (Proto DataStore for type safety) with Tink-based streaming encryption** wrapping the values — coroutine/Flow-based, off the main thread by design. For the note body specifically, either that pattern for small note content, or `SQLCipher`-backed Room for the full table if disruption to `NoteDao` is a bigger concern than adopting DataStore — pick whichever is less disruptive to the current schema, but not the deprecated ESP path.
4. This must ship as its own release and be manually verified (inspect the raw `.db` file / shared prefs on a rooted test device to confirm no plaintext PIN or locked content is recoverable) **before** any IPC surface is added on top of it.

**Acceptance criteria:** locked note PIN and content are unreadable from a raw file dump of app storage; existing locked-note UI functionality is unchanged from the user's perspective.

---

## Phase 2 — Add the JitPack dependency and wire the manifest

1. Add the JitPack repository to `settings.gradle.kts` (bare `maven { url = uri("https://jitpack.io") } }` — `OmniLinkSDK` is public, no credentials needed), then `implementation("com.github.obieda-hussien:OmniLinkSDK:v1.0.0")` (current tag) to `app/build.gradle.kts`.
2. Add the new `NoteLinkService` entry to the manifest with `android:permission="com.omnilink.sdk.permission.BIND_EXTENSION"` and an intent-filter for `com.omnilink.sdk.action.EXTENSION_BIND`, alongside the existing `FileProvider` declaration (no change needed there). The permission itself merges in automatically from the SDK's manifest (see `01_OmniLinkSDK_PROMPT.md` Phase 7) — nothing to declare beyond the service entry itself.

**Acceptance criteria:** project compiles; no functional change yet.

---

## Phase 3 — Implement `NoteLinkService`

1. Create `NoteLinkService : ExtensionService` wrapping the existing `NoteRepository` / `NoteDao`. Set `override val securityValidator = SignatureSecurityValidator(setOf(<shared ecosystem signing certificate's SHA-256 — the canonical value documented in `02_OmniDev-Workspace_PROMPT.md` Phase 8>))` explicitly — **this app must be signed with that same shared keystore, or it will fail to install (`INSTALL_FAILED_DUPLICATE_PERMISSION`) once any other app consuming this SDK is already on the device** — required, since the base class defaults this to `null`.
2. `manifest()` returns a `CapabilityManifest` listing: `createNote(title, body)`, `searchNotes(query)`, `appendToNote(id, text)`, `listRecent(count)`.
3. For any note flagged as locked, `onAction()` must return an explicit `ActionOutcome.Failure(ActionError("locked", "Note is locked; unlock required"))` — never silently filter it out of search results (that leaks existence-but-not-content, which is fine) and never silently return its content (which is the actual line not to cross).
4. Define — but do not yet implement the UI for — an explicit `unlockNote(id, pin)` action, marked `requiresConfirmation: true` in its `CapabilityDescriptor`. **This app cannot show a confirmation/unlock prompt on Workspace's behalf** — Workspace checks this manifest flag before ever calling `unlockNote` and shows its own prompt first (see `01_OmniLinkSDK_PROMPT.md` Phase 7 and `02_OmniDev-Workspace_PROMPT.md` Phase 2 for the full flow). When the call does arrive, `onAction()` checks the Phase 1 hash and grants access for the remainder of the bound session only.
5. `searchNotes` and, on a large note collection, `listRecent` can genuinely be slow DB queries — per the SDK's Phase 7 guidance, make sure Workspace's caller always reaches these through `executeActionAsync`, and don't advertise them as "fast" in this app's own documentation.

**Acceptance criteria:** agent can create/search/append unlocked notes via the bound service; any attempt to read a locked note without going through `unlockNote` returns the `locked` error, verified by an automated test, not just manual spot-check.

---

## Phase 4 — Verification

1. Instrumented test: bind, create a note, search for it, append to it — confirm round-trip correctness.
2. Instrumented test: create a locked note, attempt `searchNotes`/`appendToNote` against it without unlocking — confirm the `locked` error every time, including edge cases (empty query matching it, id guessed directly).
3. Confirm this app's own `AuditLogger` implementation (set on `NoteLinkService`) is actually invoked by the base class for both allowed and denied/locked attempts, with the correct `CallerContext`/`ActionRequest`/`ActionOutcome` — this repo has no access to `OmniAuditLog`, which is an internal class living inside the separate `OmniDev-Workspace` app/process; the unified, user-visible audit trail across all extensions is verified in `02_OmniDev-Workspace_PROMPT.md`'s own Phase 5, not here.

**Acceptance criteria:** all three tests pass; a locked note's content never appears in any log output, including this app's own `AuditLogger` output (log the fact of denial, never the attempted payload for locked content).
