# Auto model selection (initial trial)

This change adds **Auto · Free only** and **Auto · Free first, paid fallback** to
the existing model picker for Chat, Agent, Team Coordinator and Team Worker.

## Safety boundaries

- Auto is opt-in for each role. Existing explicit model selections are unchanged.
- Free-only accepts **explicitly zero-priced** catalog entries or OpenRouter
  \`:free\` models. Missing price metadata does **not** prove a free allowance.
- Gemini API Free Tier is a **per-project billing designation**, not a model price.
  Free-only includes an allowlist of publicly documented Gemini free-tier models
  only after the user explicitly confirms their Gemini project is **unbilled**.
  The /models endpoint cannot verify that declaration: users should review
  Google AI Studio billing status and free limits before enabling the checkbox.
  If billing changes, switch it off.
- Gemini API model listings do not specify Function Calling capability; Auto
  uses conservative known-good Gemini text models. Antigravity preview isn't
  agent-eligible even if an old cached catalog incorrectly labels it as such.
- The paid fallback choice is an explicit opt-in and can charge the account or
  send conversation content to a different **already configured** provider.
- There are no background "test prompts" or provider-wide API probes.
- No on-device model is suggested or selected unless a user-configured local
  model is **already loaded**. The router never downloads a model.
- Models must match role tool-call requirements, and vision requests must
  match vision-capable models. The retired GitHub Models provider is excluded.
- Up to three attempts are made on recoverable provider failures. HTTP 429
  honors Retry-After as a bounded per-process cooldown.
- Cross-model fallback is blocked after tool-call/tool-result history; streaming
  fallback is blocked after any text is delivered. This prevents automatic
  replay of executed actions and duplicated streamed content.
- Automatic selection does **not** lift any upstream provider limit.

## Manual test (Android)

1. Connect an account/API key whose refreshed model catalog includes an
   explicitly free model (such as an available OpenRouter \`:free\` entry).
2. Select Settings → Model Selection → Chat → Auto · Free only.
3. Send a plain text request; verify normal output.
4. Disconnect all eligible free providers; verify that Auto reports no
   eligible model instead of silently using an unconfigured model.
5. Connect two distinct eligible providers and simulate a recoverable
   failure before first streamed text. Verify the next provider is used,
   with no more than three requests.
6. Ensure `antigravity-preview-05-2026` is not selected by Agent Auto.
   Function Calling HTTP 400 must not be retried six times or mislabeled
   as a network failure. If a compatible candidate is connected, try it.
7. Test a message involving tool calls and verify already-executed tool
   actions are not repeated on provider errors.
8. Repeat the selection in Agent, Team Coordinator, and Team Worker on a
   flavor supporting those modes.
9. Without a loaded user-added GGUF, verify Local Edge is never selected.
10. Verify existing explicit model selections and Lite builds remain intact.

## Scope and follow-ups

This PR adds routing for **existing provider connections**, not ChatGPT Plan
OAuth, Gemini CLI, Codex runtime, or Copilot SDK authentication. Those require
separate approved authorization adapters and independent integration tests.

No CI/build result is implied by this document. Test the PR workflow artifacts
before distributing an APK.
