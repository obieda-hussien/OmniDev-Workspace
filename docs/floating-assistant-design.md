# Floating assistant

The floating assistant and the translucent ACTION_ASSIST fallback share `AssistantConversation`. Its header uses the cropped Omni mark, a short status, and window controls; device permissions live in the inline tools panel. The transcript uses the main chat's message bubbles, streaming renderer, console redaction, reply handling and tail-follow state. New responses follow the end only while the user is following; reading older messages is not interrupted.

`ChatComposerSurface` is shared with ordinary chat. It keeps the editor mounted while Send changes to Stop, allows drafting during a run, and blocks editing/submission while attachments or handoff are being saved. Selected screen/files and replies stay adjacent to the editor. Screen capture, area selection and voice have compact shortcuts; all are also reachable through tools, including in short windows. Suggestions populate a draft without submitting it.

The outer assistant window consumes safe-drawing/IME insets once, before measuring the available panel height. The transcript and tools scroll within the remaining height. Whole-panel size is not animated on streaming tokens or keyboard changes; entrance/disclosure/press motion follows the existing low-memory, battery-saving and reduced-motion policy.

Tools, file-path entry, action previews and confirmations remain inline. No new dialog, popup or bottom-sheet window is created: Android's VoiceInteractionSession can supply a non-Activity context. Android file/voice pickers keep their existing handoff. Pending approval blocks expansion and replaces the transcript/composer with the existing review card. Flavor policy controls device access and floating bubbles. Locked-device handling, screen selection, attachment persistence, teaching/local-task controls and controller lifecycle remain intact.

Regression coverage includes native-window tool routing, narrow RTL layouts with large text, Stop and draft retention, blocked saving, stopping voice input, Lite capability visibility, pending approval, and two real-keyboard open/close cycles with Arabic/English input. CI exports light, dark/RTL, tools and keyboard captures with its test reports.
