# Live agent follow-ups

In Agent or Team mode, and in the floating assistant, write a correction while a task is running and press **Send follow-up**. Examples: “Use Kotlin instead”, “Keep the existing database schema”, or “Also check the Arabic layout”. This continues the same task. It does not replace the original user message or create a separate session.

The composer shows when the latest follow-up was received and when it was applied. A separate Stop button cancels the run. Multiple follow-ups stay ordered; later corrections override conflicting earlier instructions. Normal Chat continues to use its existing send/stop behavior.

## Execution contract

- Each run owns its `RunSteering` queue. It is not a singleton and is not reused between sessions.
- A correction interrupts model generation and its retry waits. Single-agent execution keeps its conversation, closed tool-result groups, total token usage, iteration ceiling, and elapsed-time limit.
- Every tool dispatch and retry checks the plan revision. An obsolete action that has not started returns a skipped result without invoking the tool. A tool already in flight is allowed to settle under its existing timeout; there is no promise to undo side effects or stop external jobs that were already accepted.
- Team workers share a broadcast revision. A redirected worker stops at a safe boundary. The coordinator waits for the whole old wave, preserves public tool observations and completed-task evidence, and replans remaining work. One worker's redirected model call does not cancel another worker's in-flight tool.
- Team replanning retains the logical run's token ceiling. Interrupted model calls without usage metadata retain input/observed-output estimates; interrupted planner calls reserve a bounded estimate. Provider-side billing after cancellation can differ from these estimates. Previous execution evidence is bounded and redacted; shortened evidence is marked so the new plan must verify current state. Provider reasoning and partial drafts are excluded from the team handoff.
- Final completion is sealed against the current revision. A follow-up accepted before sealing must be considered before a final answer can be published. A submission after sealing leaves the draft available for the next message.
- Follow-ups are saved as user messages in order, after the original message. Final-response saving waits for accepted follow-up writes. Failed persistence is surfaced in the UI. File imports block follow-up submission. Session changes, Stop, and run cleanup disable steering in the affected composer.
- Pending approval proposals from the old route are resolved as denied. The assistant's action guard is refreshed from the latest typed request. Tool availability, target scope, flavor restrictions, and approval gates remain enforced.

## Limits

Live follow-ups accept text. Remove selected attachments before submitting a correction, or send them when the task finishes. Each follow-up allows up to 16,000 characters; a run allows 64 follow-ups and 64,000 total follow-up characters. Oversized requests are rejected without silent truncation.

Automatically matched learned tasks keep their existing zero-model-token fast path and separate pause/resume controls. If an agent is already executing a learned routine tool, a follow-up requests a pause at the routine's next step boundary before model continuation.

Replanning reuses evidence but still depends on the model's judgment. It must verify existing state before repeating a mutation, disclose uncertain outcomes, and perform newly requested work under the application's ordinary permissions. Voice input that sends through the assistant's chat controller uses the same follow-up path.

## Regression coverage

`RunSteeringTest`, `AgentSteeringTest`, and `SwarmSteeringTest` cover ordered updates, run isolation, model cancellation, stale revision rejection, mutation-batch skipping, preserved tool evidence, parallel worker barriers, and completion sealing. `ChatSurfaceTest.liveFollowUpSendsWithoutStoppingAndKeepsIndependentStopControl` covers the two composer controls on Android.
