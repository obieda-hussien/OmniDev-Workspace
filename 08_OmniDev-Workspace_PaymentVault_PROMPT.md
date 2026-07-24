# OmniDev-Workspace — Payment Vault & Autonomous Checkout Addendum
### Repo: obieda-hussien/OmniDev-Workspace · 8 phases, this repo only

> This is a built-in tool inside Workspace itself — deliberately **not** exposed through the Omni-Link extension protocol (`01_OmniLinkSDK_PROMPT.md`). No other app, satellite or otherwise, should ever be able to reach this surface via IPC. It doesn't get an `ext_` tool prefix; it's a native Workspace tool like Shizuku shell execution.

> **Read this before Phase 1:** no design here makes the agent "unfoolable." The actual safety property is that every high-consequence decision is enforced by a layer the agent cannot itself alter or talk its way around — allowlist, spend caps, and confirmation gates all live outside the agent's own judgment. A successful deception should be expensive to pull off and cheap to contain, not impossible.

> **Strong recommendation before building any of this:** if the card issuer offers a spend-capped or merchant-locked virtual/disposable card, use that as the stored card for this feature instead of a primary, high-limit card. A bank-enforced cap is a guarantee independent of this app's code being bug-free; an app-level cap is only as strong as the code in Phase 4. Both are implemented below, but they are not equivalent in strength — say so plainly in the add-card UI, and require extra friction (an explicit acknowledgment screen, not just a checkbox) before letting the user register a primary/uncapped card for autonomous use.

---

## Phase 1 — Guardrail settings the agent can never touch

Build this first because everything else depends on these values existing and being tamper-proof from the agent's side.

1. A dedicated Settings screen (separate Activity, not reachable from any agent tool call) where the user — after biometric re-auth — sets: the merchant domain allowlist, per-transaction spend cap, cumulative daily/weekly spend cap, a "trivial amount" threshold below which a payment can proceed with post-hoc notification instead of a pre-payment confirmation, and a **velocity limit** (max transactions allowed within a rolling time window — e.g. default "max 1 per 5 minutes, max 3 per day," user-adjustable).
2. **Structural guarantee, not a convention:** audit the agent's full tool registry and confirm there is no tool — none, ever — that can modify the allowlist, either spend cap, or the trivial-amount threshold. These values are read-only from the agent's execution path.
3. Store these values using the same modern pattern as Phase 2's vault: Keystore-backed keys, accessed through Jetpack DataStore with Tink-based encryption — not `EncryptedSharedPreferences`, which Google has deprecated (see the note in `04_OmniNote_PROMPT.md` Phase 1 for why). A setting this security-critical shouldn't be built on a library Google itself has moved away from.

**Acceptance criteria:** a code-level check (lint rule, test, or manual audit) confirms zero agent-invokable code paths write to allowlist/caps/threshold storage; changing any of them requires a fresh biometric prompt every time, no session-level "already authenticated" shortcut.

---

## Phase 2 — Encrypted payment vault

1. Store card records via Android Keystore-backed encryption (StrongBox-backed where the device supports it), key generated with `setUserAuthenticationRequired(true)` so decryption requires a live biometric/device-credential check every time, not just once per app session.
2. Schema: last 4 digits (plaintext, for display only), full PAN (encrypted), expiry (encrypted), cardholder name, a user-set label, and a `isCapped: Boolean` flag the user sets honestly at add-card time (see the Phase-0 recommendation above) — this flag doesn't change the technical protection, but it should visibly color the UI (e.g. an uncapped card shows a persistent warning badge) as a constant reminder, not a one-time dismissed dialog.
3. **CVV is never persisted**, full stop — no field for it in the schema. Where a checkout flow requires a CVV, that's a strong signal this specific card/flow isn't a good fit for unattended automation; don't build a workaround that stores it anyway.

**Acceptance criteria:** inspecting the raw app storage on a rooted test device shows no recoverable plaintext PAN; attempting to read the decrypted PAN without a live biometric prompt fails at the Keystore level, not just at an app-logic check.

---

## Phase 3 — Sandboxed checkout WebView with hard navigation gating

1. Use a dedicated WebView instance reserved for agent-driven checkout tasks only — not the same component used for any general browsing the agent might do. Host it inside an active foreground `Activity`/task for the duration of the checkout, not a background service — a checkout is inherently a bounded, user-relevant, in-the-moment task, so there's no need to involve foreground-service machinery (or its Android 15+ `dataSync` timeout rules from Phase 6) here at all.
2. Implement a navigation interceptor that checks the target domain against Phase 1's allowlist **before** allowing the page to load at all. Not-allowlisted → block outright and return a plain "merchant not approved" result to the agent; this check happens in the WebView layer, not as something the agent evaluates and might reason its way around.
3. Reject any navigation to a non-HTTPS URL or one with an invalid/mismatched TLS certificate outright, no exception path.
4. **Prefer Android's native Autofill Framework (`androidx.autofill`) over manual `WebView.evaluateJavascript()` for the actual field-fill step where the target page supports standard autofill hints.** The OS-managed autofill path keeps the OS itself brokering the credential-to-field handoff with stronger isolation than injecting the raw decrypted value through the WebView's own JS engine, where a malicious page's own script has a better chance of reading it back via DOM APIs. Fall back to direct JS injection only for pages that don't expose proper autofill hints, and treat that fallback path as the higher-risk case it is.
5. Any page content the agent reads (cart contents, totals, item names) gets wrapped and passed to the agent explicitly marked as untrusted external data — never concatenated into the agent's instruction context in a way that could be mistaken for a command.

**Acceptance criteria:** a test page hosted on a non-allowlisted domain — even one visually identical to an allowlisted merchant — fails to load in the checkout WebView and the agent receives a clean rejection, never a rendered page; a test checkout form with standard autofill hints receives the card data via the Autofill Framework path, verified to not pass through `evaluateJavascript()` with the raw PAN as a literal string in the injected script.

---

## Phase 4 — The payment execution gate (most safety-critical phase)

1. Implement a single narrow function, something like `executePayment(cardId, observedDomain, amount, itemDescription)`, that on every call, in this order:
   - Re-reads the WebView's **actual current URL** directly and independently re-verifies it against the allowlist — never trusts the agent's own claim of what site it's on.
   - Checks `amount` against both the per-transaction and cumulative caps from Phase 1. Over cap → hard refusal, no override path, regardless of how confident the agent's reasoning was.
   - Checks the **velocity limit** from Phase 1: count completed/pending transactions in the current rolling window; if the limit is already reached, refuse outright — this rejects rapid repeated transactions even when each individually is within the spend cap, since rapid repetition is itself a signal something's wrong (fraud, a bug in the agent's loop, or a confused retry).
   - If `shadowMode` is enabled (see Phase 8): always route to the confirmation path below regardless of amount — shadow mode overrides the trivial-amount threshold entirely.
   - If `amount` exceeds the trivial-amount threshold (or shadow mode is on): blocks and requires a **fresh `BiometricPrompt` challenge** — the same live biometric check used to decrypt the vault in Phase 2, not merely a notification action tap (a tap alone is a weaker factor and more exposed to accidental or manipulated taps). The confirmation UI shown alongside the biometric prompt displays merchant + amount + item description. The payment does not fire until the biometric challenge succeeds, and it **fails closed** on timeout or cancellation — no default-proceed.
   - If under the threshold, within both caps, within the velocity limit, on an allowlisted domain, and shadow mode is off: proceeds with a biometric-gated decrypt (Phase 2) + field-fill + submit — but still fires an immediate post-payment notification. Nothing happens silently, ever, regardless of amount.
2. Every single call to this function — approved, refused-by-cap, refused-by-domain, refused-by-velocity, or pending-confirmation — writes a full audit record with timestamp, domain, amount, and outcome.

**Acceptance criteria:** a simulated payment attempt against a non-allowlisted domain is refused before any decrypt call happens; an attempt over the spend cap is refused regardless of the path that produced the request; a rapid sequence of attempts within the velocity window is refused after the configured count regardless of individual amounts; an above-threshold attempt visibly blocks pending a real biometric challenge in manual testing, and fails closed if the challenge is cancelled or times out.

---

## Phase 5 — Prompt-injection resistant execution rules

1. Document and enforce, at the prompt-construction layer, that text extracted from any webpage is data the agent may reason about but never a command it may follow. If page content contains something shaped like an instruction ("ignore previous instructions," "ship to a different address," "use a different card," "increase quantity," "add insurance"), the agent must not act on it — only the user's original request, or an explicit new confirmation tap, can change what's being bought, the amount, or the destination.
2. If the actual checkout total or shipping destination the agent observes mid-flow differs from what the user originally asked for, treat the mismatch itself as a hard stop requiring fresh user confirmation — never silently proceed because "the site said so."

**Acceptance criteria:** a test page containing hidden injected text attempting to change the card, amount, or destination results in the agent's actually-executed action matching the user's original request, verified by an automated test that plants the injection and asserts on the final `executePayment` call's parameters.

---

## Phase 6 — Kill switch & visibility

1. A persistent, one-tap control (notification action or quick-settings tile) that immediately disables Phase 4's execution gate entirely, regardless of any other state — this must work even mid-transaction-flow, not just before one starts.
2. A simple in-app log of recent autonomous payment attempts (approved, refused-by-cap, refused-by-domain, pending, timed-out) for the user to review at any time, not just via notifications that can be missed.

**Acceptance criteria:** toggling the kill switch mid-flow reliably prevents a pending payment from completing; the log accurately reflects every Phase 4 call from Phase 4's own acceptance-criteria tests.

---

## Phase 7 — Verification

1. Adversarial-domain test: a QA-served test page visually mimicking an allowlisted merchant but hosted on a different domain — confirm it never loads in the checkout WebView.
2. Prompt-injection test: as in Phase 5's acceptance criteria.
3. Spend-cap test: attempt a payment exceeding the configured cap through the normal agent flow — confirm hard refusal, and confirm the refusal is logged.
4. Velocity test: attempt more transactions than the configured window allows, each individually within the spend cap — confirm the ones beyond the limit are refused.
5. Biometric-confirmation test: trigger an above-threshold payment and cancel the biometric challenge — confirm it resolves to "not paid," not "paid after cancel/timeout."
6. Kill-switch test: trigger the kill switch mid-flow and confirm the in-progress payment attempt is stopped, not just future ones blocked.

**Acceptance criteria:** all six tests pass in CI or a documented manual on-device test pass; none of them can be satisfied by a mock that bypasses the actual WebView navigation, Keystore decrypt, velocity counter, or biometric-confirmation code paths — they need to exercise the real components.

---

## Phase 8 — Staged trust rollout (do this after Phase 7 passes, before any real unattended payment)

A passing test suite proves the mechanism works against the cases you thought to test. It doesn't prove it holds up against the cases you didn't think of. This phase is how you close that gap before trusting it with real money unattended.

1. Add a `shadowMode: Boolean` setting (default **on** for any newly installed build) in Phase 1's settings screen. While on, Phase 4 always routes to the biometric-confirmation path regardless of amount or threshold — every single transaction gets a live human check, no exceptions, no matter how small.
2. Run the full system for real for a probation period you set yourself (a few weeks and/or some number of real confirmed transactions, whichever you decide is enough) — every transaction still requires your confirmation, but everything else (allowlist check, browsing, form-fill, velocity counting, audit logging) runs exactly as it will in full autonomous mode.
3. Review the audit log from that period specifically for near-misses: cases where the amount was close to a cap, where a domain almost matched something not actually on your allowlist, or where the agent's reasoning before the confirmation step looked confused even though the guardrail caught it correctly. These are the signal that tells you whether the guardrails are earning their keep or just haven't been tested by a real edge case yet.
4. Only after that review, manually turn `shadowMode` off from the settings screen (biometric re-auth required, as with any Phase 1 setting) — there is no agent-callable path to do this, matching the rule from Phase 1.
5. Consider leaving the trivial-amount threshold very low for a further period even after shadow mode ends, and raising it gradually rather than jumping straight to whatever you originally had in mind.

**Acceptance criteria:** a fresh install defaults to `shadowMode = true`; there is no code path, agent-invokable or otherwise, that flips it off besides the Phase 1 settings screen with a fresh biometric check.

---

**Cross-cutting note:** this feature lives inside `OmniDev-Workspace`, so it inherits that repo's own `compileSdk`/`targetSdk` bump toward API 36 (see `01_OmniLinkSDK_PROMPT.md` Phase 9) automatically — no separate action needed here beyond what `02_OmniDev-Workspace_PROMPT.md` already covers.
