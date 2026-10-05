# Chat experience

The conversation has one compact header and a composer that starts as a single toolbar row. The header title opens the Chat / Agent / Multi-agent picker. The composer `+` opens file attachments, project scope and capability settings. A selected workspace remains visible for execution modes when the editor is unfocused; scope is also reachable from the header menu.

Assistant replies use a reading surface with the same typography and width during streaming and after completion. Short user messages wrap their text. Copy and reply actions remain separate from text selection, and copying an abbreviated long message copies its complete content.

Live execution activity sits in the scrollable transcript. It no longer takes fixed space away from the message list or composer. Replying focuses the editor. Sending dismisses the keyboard. The editor remains usable during an active run so the user can prepare the next draft; Stop acts only on the current run and does not submit that draft. Sending attachments without a caption uses the explicit default prompt `Please review the attached files.` Existing scope checks and mode approvals still apply.

## Motion and rendering

- Small mode and Send / Stop changes crossfade using the shared motion policy, rather than animating transcript size or restarting a scroll for every token.
- Reply previews expand and collapse with the shared disclosure motion. The focus border transitions without moving the text editing node.
- History reorder motion is disabled for compact and reduced-motion policies. Closed history is removed from composition while retaining its saved search and sort state.
- Streaming Markdown refreshes are coalesced to a maximum cadence of one update every 32 ms, or 64 ms with the compact policy. This changes presentation cadence only; the latest chunk and final response keep their full content and rich formatting.
- The existing tail-follow state preserves the reading position when the user scrolls into history. Jump to latest remains available. Text continues to use content-based direction, and controls follow the app's layout direction.

No device frame-rate or battery improvement is asserted without a device trace. The implementation avoids bitmap effects, background blur and per-token layout transitions.

## Adaptive composer

After system and IME insets, a conversation area shorter than 280 dp uses a compact composer: reply and attachment previews share one horizontal row, the editing area has up to three visible lines, and the workspace badge gives its space to the editor. Larger areas allow five visible lines and a full reply excerpt. Errors live in the transcript, with dismissal and a selectable details dialog, so diagnostics cannot push Stop off screen.

## Verification

Compose regressions cover mode selection, full-content copy and reply identity, drafts while processing, Send / Stop, confirmed history deletion, source search, the one-row empty composer, attachment-only submission, tool routing, latest-chunk Markdown rendering, reduced motion, and starter prompts that prepare a draft without sending it.

Two native captures are exported with GitHub UI test reports: `chat-previews/welcome-light.png` and `chat-previews/compact-dark-rtl.png`. The compact case uses a 320 × 340 dp viewport, 1.5× text scaling, a reply, attachment, active console and a long error. Capture production and execution are CI tasks; adding a case alone is not a passing test result.

On a physical Infinix X689, check portrait and landscape with the Arabic and English keyboards, a long streamed code reply, scroll-back while streaming, an expanded console, text selection, and Stop with a pending draft. Compare frame-time traces in a release build before reporting performance numbers.
