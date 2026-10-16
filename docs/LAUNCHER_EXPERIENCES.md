# Launcher Home experiences

Companion host policy for Omni Launcher PR #16. Existing launcher and IDE capability behavior is retained. New launcher capabilities require an allowlisted Launcher package, the SDK signer checks, the receiver local control opt-in and receiving-side flavor ceiling. Mutations advertise confirmation and dry-run metadata; the existing approval gate must still run.

- NORM: list actions/scenes/folders/Shelf previews/widget stacks/OEM status, plus open a known action while Home is resumed.
- PRO: those actions plus capture/apply scenes, edit folder rules/aliases, publish text Shelf cards and select a previously bound widget.
- OEM/ADMIN: also request app suspension through an actually provisioned Android device/profile owner. A flavor name or same signer does not confer that role.
- New experience capabilities are not granted to LITE by this change. Its previous allowlist remains intact; Launcher independently retains its health-only Lite ceiling.

The Launcher service catalog supplies input schemas. `launcher.shelf.publish` accepts `{ "id": "stable-source-id", "title": "Note", "text": "Preview" }`, records the authenticated Workspace caller and accepts no URI grants. Card text is capped at 4,000 characters and all requests remain bounded to 16 KiB. `launcher.shelf.list` returns at most ten titles and short text previews; document contents are not shared.

`launcher.list_apps` now includes `componentKey` and `profileSerial` for accessible profiles. Pass the returned optional `profileSerial` to `launcher.open_app`; omission retains current-profile behavior.

The new Launcher build and this Workspace build are both needed for the full control catalog. Public text search handoff remains independent of this host-policy change. There is no Root/Shizuku implementation and no always-running launcher agent.
