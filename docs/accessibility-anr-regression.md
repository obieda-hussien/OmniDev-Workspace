# Accessibility observer ANR regression

The supplied Android 11 trace identifies `onAccessibilityEvent -> refreshRootNode ->
updateRootNode -> countNodes -> AccessibilityNodeInfo.getChild` on the main thread.
Each event previously performed two recursive full-tree scans. The 40 GB allocation
figure is cumulative across the process lifetime; it is not a 40 GB resident heap.

## Repair

- Event callbacks now enqueue a conflated refresh. One background observer performs
  root lookup, consent checks and a single iterative classification pass. It waits
  500 ms after completing a refresh before accepting the next pending refresh.
- The heuristic pass visits at most 256 nodes, requests at most 255 children, and
  descends at most 48 levels. It stops starting child requests after 120 ms. Android
  Binder calls already in flight cannot be preempted by this budget; they stay off
  main and cannot cause a growing queue of replacement workers.
- Partial scans do not declare a small/loading screen. Every acquired child and
  discarded root is released, including exception and cancellation paths.
- Publication is constant-time on main. Generation checks reject results completed
  after invalidation, consent changes or disconnect. Equal Android node identities
  do not leave StateFlow holding a recycled older wrapper.
- Teaching events use a bounded, session-tagged queue and are copied only during
  an active recording. No remote node lookup holds the teaching-state monitor.
  Overflow discards the affected recording rather than silently omitting actions.
- Accessibility subscribes only to events consumed by observation or teaching.

## Automated verification

`BoundedTreeWalkTest` covers deep/wide trees, null children, a simulated five-second
IPC response, cleanup after metadata failure and complete small trees.
`ConflatedRefreshLoopTest` covers a 10,000-event burst, a trailing refresh and
shutdown while work is in flight. The Android state-manager tests cover equal node
wrappers, invalidation during a scan and clearing stale loading metadata.

```bash
./gradlew :app:testAdminDebugUnitTest :app:testLiteDebugUnitTest \
  :app:compileAdminDebugAndroidTestKotlin
./gradlew :app:connectedAdminDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.omnidev.workspace.data.accessibility.AccessibilityStateManagerTest
```

## Device validation

Use an Android 11 device, preferably Infinix X689, with the Admin accessibility
service enabled. Scroll a large web page and long lists continuously, switch apps,
open/close the assistant overlay, and leave the service running through a normal
background session. Verify that Omni remains responsive, observation refreshes
continue during scrolling, and the observer thread count stays constant.

Record, stop and restart teaching while switching apps. Confirm old queued events
never enter the new recording. Disable accessibility or revoke device consent
during a refresh and verify the root stays cleared until a new authorized refresh.
Compare allocation rate and main-thread stall reports with the prior build under
the same workload. Unit tests do not establish a measured device speedup or prove
the absence of unrelated ANRs in manual semantic tools or OEM firmware.
