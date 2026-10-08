# Little Omni

Little Omni is a native, lilac companion in the main chat and floating assistant. It has a soft body, two small ears and no legs. The artwork is drawn with Compose Canvas paths and gradients; the app does not load the documentation images at runtime.

![Artwork and placement illustration](companion-artwork.png)

The illustration shows the drawing and intended placement, not a device screenshot.

- Hops along the actual composer and, while the agent works, onto a visible console.
- Tap for a tumble and recovery; drag and release to land on the nearest available perch.
- Shows a working expression and rests after idle time.
- Settings → Virtual companion controls visibility and automatic hopping. Preferences apply to both chat hosts.
- Reserves headroom above perches, pauses when the host lifecycle stops, follows system reduced motion, and uses a 30 fps ticker on compact devices. Very short windows and assistant confirmation/tool panels hide it.
- Pointer input belongs only to the 60 dp sprite, with a semantic play action and a return-to-composer accessibility action. There are no extra windows, model calls or Activity result launchers.

`CompanionMotionTest` covers perches, jumps, drag/throw bounds, recovery, resize, reduced motion and sleeping. `ChatCompanionTest` covers real editor geometry, send/stop availability, preference changes and hidden/tiny hosts; the API 30 UI regression workflow runs it with the existing chat and assistant tests.
