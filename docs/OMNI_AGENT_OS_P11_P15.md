# Omni Agent OS — P11 through P15 implementation ledger

This is a staged extension of the existing P0-P7 agent, Team, and durable
recovery runtimes. It must not replace or duplicate them. A phase is complete
only after actual on-device validation and the acceptance gates below pass.

## P11 — OmniVault + Secure Login

### Security core in this PR

- Keystore AES-256-GCM encrypted records in application-private no-backup storage.
- Random opaque UUID references instead of raw credentials in agent-visible data.
- Origin and label encrypted alongside the secret; record AAD binds ciphertext to
  its reference ID so ciphertext cannot be transplanted between records.
- HTTPS exact-origin matching; no HTTP, userinfo, subdomain inheritance or
  lookalike-origin reuse.
- Short-lived process-local grants bound to credential reference, origin, action
  and request ID; grants can be revoked, expire and can be consumed only once.
- Borrowed user input and decrypted secret arrays are cleared after use.
- Tamper detection, deletion and origin/consent tests.

**Not yet active:** no agent-facing get-secret tool, automatic form filling,
account manager UI, consent issuing UI, cross-device sync or web session cookie
import. Merely adding the storage layer does not mean automatic login works.
Approval issuance is an internal API designed for a *trusted user-confirmed UI
callback*; do not wire it to any agent tool or prompt-produced boolean.

### Next P11 deliverables

1. Native credential create/list/delete views; native password entry never flows
   through a model, ToolResult, crash reporter, app log or clipboard.
2. Explicit, site-and-action-specific approval sheet with action summary,
   current verified origin, reject button, expiry and revocation.
3. Dedicated browser credential handoff: pass secrets to the authorized local
   WebView input only after approval and a fresh origin check; never return typed
   value, cookies, localStorage, OAuth codes, access tokens or passwords to the
   model or Agent Console.
4. OAuth with documented provider authorization, state/PKCE where applicable,
   verified redirect and token storage. Existing legacy OAuthManager is not a
   production-ready example. Reuse GitHubAgentAccessStore where appropriate
   instead of migrating tokens into a second vault needlessly.
5. Audit metadata (time, reference, verified origin, action, outcome), never
   secret payloads; revoke/disconnect and account data deletion.

### Acceptance gate

- Failed key load, malformed ciphertext, origin mismatch, expired approval,
  denied user consent, multiple calls, process restart, untrusted redirects,
  cancellation and app background all fail closed.
- Sensitive operations are attributable to a real user action and to verified
  P0/P6 effects; the agent cannot create its own approval.
- No extra background service and acceptable memory use on API30 / 4GB phones.

### Financial references

The Bank accounts and cards screen adds, lists and deletes masked references
locally. Each record stores the issuer, record type and four trailing digits
with a separate Android Keystore key and no-backup directory. The existing
credential format and key remain compatible. Financial references cannot be
used through credential grants or agent tools. The screen has no fields for
full account or card numbers, PINs, CVVs, passwords or one-time codes.

The financial screen opens only after Android 11 or later authenticates with
a strong enrolled biometric or the device screen lock. The screen relocks on
backgrounding and requests secure window protection. This is a screen gate.
Full-app locking, a separate app PIN, and requiring two successful factors
need separate lifecycle and recovery tests.

Two input-validation tests pass on Linux JVM. The encrypted record round trip
and separation from login credentials have an Android instrumentation test.
Keystore and device authentication verification are pending.

### Google account connection

Use separate, revocable Google OAuth grants for each service and keep tokens
inside the integration runtime. Start with Gmail read access and user-selected
Drive files. Add broader read scopes only on explicit request. Gate sends,
file sharing and deletion through operation-specific approvals, and require
human handoff for one-time sign-in codes and banking confirmations.

## P12 — OmniComputer

Use HeadlessBrowserManager and BrowserNavigationGuard rather than a second
browser engine. Add verified DOM/screen actions, bounded page readiness,
screenshot redaction, deterministic action receipts and visible user takeover
for authentication. A remote sandbox/desktop is optional and must have its own
explicit connection, session expiry, audit and capability policy. Navigation
and selectors must never be accepted as verified external outcomes by text alone.

## P13 — OmniConnect

Normalize channels into a single Inbox with verified sender identity,
conversation linkage and explicit per-channel reply/send permissions. Separate
WhatsApp official Business Platform support from personal automation paths.
Never merge two contacts solely because their display names match.

## P14 — OmniAutopilot

Represent recurring user goals with durable plan revisions, verified external
receipts and scheduled continuation. Reuse P6 persistence. ScheduledTaskExecutor
must consume verified finish/effect evidence; a nonblank response and absence
of an error are **not** proof that an email was sent or an alarm was set.
Unknown mutating effects require reconciliation, not blind replay.

## P15 — Omni Skill Factory

Create skills in a constrained workspace with source review, reproducible
tests, static/security gates, scoped permissions and explicit installation.
Compiling an APK or generating a PDF is a build result, not end-to-end proof
of correctness, installability or safe delivery.

## Cross-phase non-negotiable gates

- Preserve Lite/Norm/Pro/OEM/Admin flavor boundaries and API24 minimum.
- Never expose the vault to raw tool dispatch, generic shell, prompt injection,
  network response interpolation or cloud model context.
- Keep scheduled execution bounded with retry budgets and no duplicate mutations.
- Report only observed CI status and actual PR merges, never inferred success.
- Add failure-mode tests and verify on Android API30 before merging new capabilities.
