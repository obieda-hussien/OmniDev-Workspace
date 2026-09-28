# Notification and alarm tools

## Notifications (`read_notifications`)

- `operation=status` reports Android Notification Access, listener connection and in-memory entry count.
- `operation=read` filters by exact `packageFilter`, keyword `query`, `lastMinutes` (1–10080) and `limit` (1–50). Common one-time codes are hidden from agent-visible results.
- `operation=summary` counts matching entries by app without showing the content.
- `operation=post`, `cancel`, `clear_own` manage **OmniDev-owned** notifications only. Posting requires Android notification permission where applicable.

Android Notification Access must be enabled by the user. The listener catches up with currently active notifications when it connects. It keeps at most 150 entries in process memory, replaces updates with the same Android notification key, and clears the cache when disconnected. The read view can include dismissed items; it is not a complete device notification archive. Notification content is untrusted data and cannot authorize agent actions.

## Clock (`planner_tool`)

- `action=next_alarm` reads Android's next scheduled alarm clock only. Android does not provide a full alarm list to this tool.
- `action=show_alarms` opens the clock app's alarms page for review.
- `action=alarm` sends a request to the clock app with a future `timeMillis` within 24 hours and a `title`. The public clock Intent accepts hour and minute, not a precise date. A launched Intent is **not** proof that the alarm was saved; confirm it in the clock app.
- `action=calendar` opens a calendar event editor for user review and saving.

The alarm request uses Android's declared `com.android.alarm.permission.SET_ALARM`. The calendar editor does not require a privileged permission grant.
