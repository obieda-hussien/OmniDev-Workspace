# Learned tasks: demonstrate, review, run locally

Omni's `task_manager` is a Taskly-style todo list. Its old `semantic_ui` macros are process-local coordinate/action recordings. Neither is a durable executable skill. Learned tasks add a separate, versioned execution layer alongside prompt-only `SKILL.md` skills.

## User flow

Open **Settings → Agent Skills → Learned tasks**.

1. **Learn drafts from agent actions** saves disabled drafts after a normal agent task produces a final answer. The switch can turn capture off. A final answer does not prove the recorded task succeeded; failed, unsupported and uncertain operations become decision checkpoints.
2. **Teach live** starts an explicitly requested semantic demonstration. Open the target app and demonstrate. Notification controls and the floating assistant let you insert a decision, stop/save, or discard. Accessibility and visible notification controls must be available. Captured editable text is replaced with a runtime parameter, never copied into the recipe. Password/OTP/payment-like fields require user handoff. Scroll events with no reliable target/direction become decisions.
3. **From video** uses the Android file picker and samples eight frames locally from a clip up to five minutes. The review surface also supports adding, removing and reordering steps. Review samples yourself or choose **Interpret samples with Omni**, which sends selected samples through the configured vision model. Interpretation can cost tokens; importing, editing and local replay do not call a model. Video pixels do not reveal reliable view IDs, hidden app state, every tap, or all actions between frames. Imported steps begin as decisions, not executable inferred taps.
4. **Review** lets you edit trigger phrases, action/decision kinds, app identity, target text/accessibility label/view ID, runtime input and expected-result identity. Approve and enable after reviewing. New drafts and agent edits never enable themselves.
5. **Run** executes the reviewed recipe through the existing tool stack. Captured UI demonstrations receive an explicit, reviewable app-launch entry point; launching is skipped when that app is already foreground. Alternatively, use an approved exact phrase in Agent/Auto mode or the floating assistant. `Search for {{query}}` can bind a changing query; the TYPE step must use the same `{{query}}` parameter name. Unrecognized or ambiguous wording uses ordinary agent routing rather than guessing a task.
6. **Pause / take over** is available in Learned tasks and in the floating assistant while a task runs. It takes effect between actions. Failed, missing or ambiguous targets and missing/unobserved outcomes preserve a checkpoint. **Help with step / Ask Omni** supplies the pending cursor and instructions to inspect current state, not restart the entire task. After completing/verifying a paused action, the user can explicitly continue. Agent resume advances only when the taught expected result can be observed. A pause before an action resumes that same action.

## Execution and token boundary

The `AgentPipeline` fast path precedes provider resolution, API-key reads, memory retrieval and prompt compilation. A local task uses zero model requests/tokens. Auto mode checks for a matching task before complexity classification. The explicit Run button also bypasses completion providers. Existing flavor/tool/assistant/OmniLink permissions and confirmation gates still apply, and confirmation UI itself needs no model tokens.

UI selectors are package-scoped and freshly resolved on every action. Text, view ID, accessibility description and class criteria are conjunctive; more than one matching node is ambiguous. Screen coordinates and transient `N1` IDs are never persisted as targets. Reads wait briefly for UI transitions without repeating mutations. TYPE requires a successful read-back. CLICK/LONG_CLICK/SCROLL without a taught outcome pause for verification instead of reporting completed business outcomes.

Replayable native operations currently include semantic UI, a conservative observation allowlist, app launch, and explicitly pinned OmniLink capabilities. OmniLink payloads are runtime parameters; JSON is checked before dispatch and the existing capability consent/trust policy is re-evaluated. Shell scripts, arbitrary browser mutations, destructive app-manager actions and unsupported tools remain agent decision checkpoints.

## Durability, interruption and privacy

- Atomic per-record files live in app-private storage. No Room migration or server is required. Recipes are revisioned. Editing/disabling/deleting a recipe prevents further steps and invalidates old revision cursors.
- Checkpoints are committed before a mutation. A killed process restores unfinished runs as paused, with no automatic retry. Resume never blindly repeats an uncertain side effect.
- A process-wide mutex prevents two local runners from manipulating the screen at the same time. Completed checkpoint retention is bounded; parameter maps are transient and capped, and are omitted from saved runs and returned checkpoint text. Re-enter parameters after a process restart.
- Captured UI inputs/tool outputs are not persisted into executable recipes. Explicit task names, trigger phrases, selectors and user-written lesson instructions are saved. Video samples remain private until the user asks the selected model to interpret them. Deleting the recipe deletes its checkpoints and video evidence.
- Learning is recipe compilation and selective handoff, not model-weight training. Variable steps remain decisions unless a user reviews a reliable replacement. Semantic identity tolerates moved controls, but cannot guarantee replay after arbitrary app redesigns or inaccessible canvas UI.
- A live demonstration buffer is process-local until Stop & save; an interrupted recording must be demonstrated again. Agent-run capture is per run, never shared across concurrent workers.

## Implementation

`data/routines/` contains models, validation/matching, storage, the runner, UI resolver, learning hub, video sampling/observation bridge, teaching controls and `learned_routine`. `LearnedTasksPanel` owns review and local run controls. `SemanticUITool` dispatches the replay actions using fresh roots. `CompositeToolManager` keeps every replayed tool behind its normal gates.

The `learned_routine` tool exposes list/inspect/save_draft/run/status/resume/pause/teach_start/teach_decision/teach_stop/video_frame. A tool-requested teaching session uses the existing confirmation gate. A model cannot pass the user-only “I completed this step” override or enable a draft.

## Validation

The 29-test focused JVM suite covers exact/ambiguous matching, Arabic and variable binding, invalid identities/tool replay, input parameterization, durable checkpoints, zero-provider replay, no mutation retries, decision and interruption cursors, recipe revision changes, native replay restrictions, malformed OmniLink JSON and image payload separation/non-vision limits. The new Android adapters and Compose panel were also compiled against Android/Compose APIs with narrow stubs for existing app collaborators. This is not a complete Android/NDK build or on-device end-to-end validation; CI and a real device still need to exercise notification controls, accessibility event capture, overlay confirmation, video decoding and target-app transitions.

Android APIs: [AccessibilityEvent](https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent) and [MediaMetadataRetriever](https://developer.android.com/reference/android/media/MediaMetadataRetriever).
