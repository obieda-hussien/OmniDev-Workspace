# Account-based AI connections — implementation gates

This document specifies what remains **not implemented** after the Free-First Auto
Router PR. Do not confuse existing API-key connections with subscription-based
OAuth access. No new login action should be displayed as working until end-to-end
authentication, entitlement checks, inference and disconnection are tested.

## Shipped in the current Auto PR

- Opt-in per-role Chat / Agent / Coordinator / Worker routing.
- Free-only or explicitly allowed paid fallback; connected providers only.
- Choice of **All connected providers** or **one provider** per role.
- Supported capabilities (tools for Agents, tools on chat requests when present,
  vision when attachments require it), no automatic local-model download.
- Bounded pre-output fallback and capability-error classification.
- Entire Agent Console persisted trace and accurate counters.
- Private branch Admin builds / Telegram delivery behind protected secrets.

## Common account connection contract

Every connection requires these states:

`NOT_CONFIGURED → CONNECTING → AWAITING_USER → AUTHORIZED → READY`

Additional states: `CONSENT_REQUIRED`, `ELIGIBILITY_UNAVAILABLE`,
`SESSION_EXPIRED`, `QUOTA_EXHAUSTED`, `DISCONNECTED`, `UNSUPPORTED_PLATFORM`.

A connection may become READY only after a real, documented inference-capability
check succeeds or an official capability endpoint authoritatively validates access.
Do not send hidden probe prompts that spend tokens just to decorate Settings.

Each connection should expose a typed profile:
- Provider + auth type + verified display name, if returned;
- Granted inference scopes/entitlements, not inferred from the user's identity;
- Available model identifiers and per-model capabilities;
- Distinction between Free Tier, subscription-included, billable API and unknown cost;
- Disconnect/revoke flow and expiry/error state.

Security requirements: system browser + PKCE and state/nonce as applicable; reject
unverified token signatures and mismatched redirects; Android Keystore-backed
credential storage; no access/refresh token in logs, screenshots, URL parameters,
repo secrets, events, exports, crash reports, or tool arguments. Do not use
a personal Claude/Google/OpenAI web session cookie as a general API credential.

## Integration 1 — Sign in with ChatGPT

Official overview: https://developers.openai.com/siwc/token-sharing-open-source

1. Confirm Omni's distribution/eligibility. Open-source locally hosted projects
   and selected private clients have different access conditions. A paid or
   remotely hosted app needs the documented approval path.
2. For an eligible client, implement issued-client registration with persisted
   host ID, PKCE, verified ID token and requested plan-usage scope.
3. Only enable inference if the OAuth token actually includes
   `chatgpt.tokens.use.direct`. Discover eligible model IDs; use only supported
   Responses API features. Identity-only sign-in is not inference.
4. Keep ChatGPT-plan quotas separate from OpenAI API-key quotas, and provide
   management/disconnection UI.

Do not start the flow in production without an approved client path.

## Integration 2 — GitHub Copilot SDK

Official docs: https://docs.github.com/en/copilot/how-tos/copilot-sdk/auth/authenticate

- Register Omni's own GitHub OAuth App / GitHub App and use the user's `gho_`
  or `ghu_` token (or a documented supported auth method).
- Run the **official** Copilot SDK in a supported companion environment, passing
  the user's token explicitly. Disable CLI / other-user fallback in multi-user mode.
- Define a typed secured Android ↔ runtime bridge for sessions, message streaming,
  tool calls, approvals, cancellations, limits and model selection.
- A GitHub login does not guarantee Copilot entitlements; show only models the SDK
  actually grants to that account.
- Migrate away from any borrowed OAuth Client ID / private
  `copilot_internal/v2/token` dependencies before claiming SDK integration.

## Integration 3 — Gemini

Gemini API credentials / documented project-based OAuth are independent of
Gemini CLI's Google Code Assist login. Respect the project's Free Tier/billing
configuration and retrieve the current model catalog. The CLI itself is an
optional external coding runtime, not a way to repurpose an unofficial session
token for arbitrary API calls on Android.

## Integration 4 — Codex

Codex is an external coding runtime, distinct from the model API. Integrate
through a documented host protocol in a supported desktop/companion
environment; do not bundle undocumented Rust binaries into all Android APKs.
If ChatGPT plan usage can be passed to an eligible Codex runtime, retain
the same originating quota/permissions rather than inventing a second quota.

## Integration 5 — Claude Code

Anthropic API credentials and an eligible, supported Claude Code runtime are
separate connections. Use documented authentication for the intended
distribution. Never borrow OAuth web credentials or silently present a
subscription as an unlimited general-purpose API.

## Testing and release

1. Unit tests for auth state transitions, scope/entitlement errors, provider
   ranking, model capabilities, quota cooldown and cancellation.
2. OAuth instrumentation tests for user denial, cancellation, mismatched state,
   token expiry, revoked access and re-login.
3. Runtime integration tests must verify idempotency: tool results may not be
   replayed on a provider switch.
4. The Lite flavor may use supported remote provider connections, but must not
   bundle optional external runtimes or prompt to install local models.
5. Admin/Pro builds should expose only connected/supported runtimes and keep
   provider credentials isolated from agent tool dispatch.

**Order:** preserve Auto CI stability → official Copilot SDK companion prototype →
eligible ChatGPT OAuth adapter → Gemini project OAuth → optional Codex/Claude Code
external runtime. These are separate PRs and separate feature flags.
