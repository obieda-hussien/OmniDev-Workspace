# OmniPriceWatch — Integration Prompt
### New repo: obieda-hussien/OmniPriceWatch · 5 phases, this repo only

> Personal-use price/stock tracker, runs entirely on the user's own device (no shared backend, no "deals channel" — every install tracks only its own user's links). Prerequisite: `01_OmniLinkSDK_PROMPT.md` shipped a tag, and `02_OmniDev-Workspace_PROMPT.md` Phase 6 (heartbeat) is live so this app has something to subscribe to.

> **Be upfront with the user, in-app, about one thing:** checking a product page automatically is a form of automated access that most e-commerce sites' terms of service don't strictly permit, even for personal low-volume use. This is common practice for personal tools (this is exactly how well-known price trackers work) and low-risk at the scale of one person's own wishlist, but it's not the same as an officially sanctioned API — say so plainly in the app's about screen rather than silently.

---

## Phase 1 — Scaffolding and JitPack dependency

1. New Android app, add the JitPack repository (bare `maven { url = uri("https://jitpack.io") } }` — `OmniLinkSDK` is public, no credentials needed) plus `implementation("com.github.obieda-hussien:OmniLinkSDK:v1.0.0")`.
2. Create `PriceWatchLinkService : ExtensionService` and declare it in the manifest with `android:permission="com.omnilink.sdk.permission.BIND_EXTENSION"` and an intent-filter for `com.omnilink.sdk.action.EXTENSION_BIND`. Set `override val securityValidator = SignatureSecurityValidator(setOf(<shared ecosystem signing certificate's SHA-256 — the canonical value documented in `02_OmniDev-Workspace_PROMPT.md` Phase 8>))` explicitly — **this app must be signed with that same shared keystore, or it will fail to install (`INSTALL_FAILED_DUPLICATE_PERMISSION`) once any other app consuming this SDK is already on the device** — required, since the base class defaults this to `null`.
3. `manifest()` returns a `CapabilityManifest` with `supportsTicks = true` and `preferredTickIntervalSeconds` left at the SDK default (`900` = 15 minutes — see `01_OmniLinkSDK_PROMPT.md` Phase 6's note on why sub-15-minute cadence isn't the honest default on current Android versions), and capabilities: `trackProduct`, `untrackProduct`, `getTrackedProducts`, `getPriceHistory` (none flagged `destructive`; none need `requiresConfirmation` — reading/adding a tracked link isn't a dangerous action).

**Acceptance criteria:** project compiles against the SDK; binds successfully from a Workspace test harness and reports `supportsTicks: true` in its manifest.

---

## Phase 2 — Product page fetch & parse

1. Add an HTML fetch (`OkHttp` or similar) + parse (`Jsoup`) layer that extracts: product title, current price, and availability (in stock / out of stock) from a given product URL.
2. Keep selectors defensive and centralized in one file — Amazon's markup changes periodically without notice. On a parse failure, return a clear "could not parse this page" result rather than a stale or guessed value; log the failure with enough context (URL, which selector failed) to fix it quickly, but never log full page HTML at normal verbosity (it's large and mostly noise).
3. Respect a minimum per-request delay and a realistic `User-Agent` — this is a personal tool checking a modest number of links, not a scraper hammering the site; keep it that way deliberately, not just by accident.

**Acceptance criteria:** given a real product URL, the fetch/parse layer returns title, price, and availability correctly on a real device with a live network connection; a deliberately malformed/unreachable URL returns a clean parse-failure result, not a crash.

---

## Phase 3 — Local storage & scheduling logic

1. Add a Room database: `TrackedProduct(id, url, title, lastKnownPrice, lastKnownAvailability, checkIntervalMinutes, lastCheckedAt)` and `PriceHistoryEntry(productId, price, checkedAt)` for the history view.
2. Default `checkIntervalMinutes` to something reasonable (e.g. 30) and let the user override it per product — with the heartbeat itself now realistically ticking every 15 minutes (see the SDK/Workspace phases on `WorkManager`-based scheduling), a 30-minute default means most products get checked on every other tick, and a product set to `checkIntervalMinutes = 15` gets checked on essentially every tick — this throttle and the heartbeat's own cadence now sit close enough together that the distinction mainly matters for products you deliberately want checked less often.
3. Implement the reserved `_tick` action handler: on each call, select only products where `lastCheckedAt + checkIntervalMinutes <= now`, fetch/parse each due product (Phase 2), compare to `lastKnownPrice`/`lastKnownAvailability`, update the DB and append a `PriceHistoryEntry` on any change. **This handler does real network I/O by design — Workspace's heartbeat (see `02_OmniDev-Workspace_PROMPT.md` Phase 6) calls it exclusively through `executeActionAsync`, never the synchronous `executeAction`; don't add a second code path that invokes this logic synchronously for any reason.**

**Acceptance criteria:** with two tracked products at different `checkIntervalMinutes`, running the tick handler repeatedly at the realistic ~15-minute heartbeat cadence over several hours results in each product being actually fetched only as often as its own interval dictates, not on every tick.

---

## Phase 4 — Notifications and typed actions

1. Wire into the existing Omni notification system (already built for OmniEqualizer) to fire immediately when Phase 3 detects: a price drop, a price rise (useful context, not just drops), or an out-of-stock → in-stock transition.
2. Implement `trackProduct(url)` (validates the URL, does an immediate first fetch so the user gets instant feedback rather than waiting for the next tick, inserts the row), `untrackProduct(id)`, `getTrackedProducts()`, `getPriceHistory(id, limit)`.
3. All four go through the standard `AccessController`/`AuditLogger` path from the SDK — no bypass just because they're "just reads."

**Acceptance criteria:** calling `trackProduct` from the Workspace agent (e.g. "تابعلي المنتج ده") returns an immediate price/availability snapshot and shows up in `getTrackedProducts()`; a simulated price change during testing produces a real notification within one tick cycle.

---

## Phase 5 — Verification

1. Instrumented test: full round-trip — track a product, force a tick, confirm a price-history entry appears if the (test-mocked) price changed.
2. Instrumented test: confirm `_tick` calls for this app stop being sent if `supportsTicks` is somehow reported `false` (defensive check on the Workspace side, but worth confirming from this side too).
3. Manual on-device check: confirm ticks continue to arrive at roughly the expected cadence across a multi-hour idle (Doze) period — `WorkManager` periodic work is designed to cooperate with Doze correctly on its own, so this check is confirming that design assumption holds, not working around a battery-optimization exemption. Also confirm the about screen's automated-access disclosure (see the note at the top of this file) is actually visible to the user, not buried.

**Acceptance criteria:** all automated tests pass; manual Doze-period check confirms ticks still arrive on a real device without needing a manual battery-optimization exemption prompt.
