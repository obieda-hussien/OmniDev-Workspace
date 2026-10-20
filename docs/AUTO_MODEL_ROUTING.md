# Auto model selection (initial trial)

This change adds **Auto · Free only** and **Auto · Free first, paid fallback** to
the existing model picker for Chat, Agent, Team Coordinator and Team Worker.

## Safety boundaries

- Auto is opt-in for each role. Existing explicit model selections are unchanged.
- Free-only accepts **explicitly zero-priced** catalog entries or OpenRouter
  \`:free\` models. Missing price metadata does **not** prove a free allowance.
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
6. Test a message involving tool calls and verify already-executed tool
   actions are not repeated on provider errors.
7. Repeat the selection in Agent, Team Coordinator, and Team Worker on a
   flavor supporting those modes.
8. Without a loaded user-added GGUF, verify Local Edge is never selected.
9. Verify existing explicit model selections and Lite builds remain intact.

## Scope and follow-ups

This PR adds routing for **existing provider connections**, not ChatGPT Plan
OAuth, Gemini CLI, Codex runtime, or Copilot SDK authentication. Those require
separate approved authorization adapters and independent integration tests.

No CI/build result is implied by this document. Test the PR workflow artifacts
before distributing an APK.
