# Launcher Home experiences

Companion host policy for Omni Launcher PR #16. Existing launcher and IDE capability behavior is retained. New launcher capabilities require an allowlisted Launcher package, the SDK signer checks, the receiver local control opt-in and receiving-side flavor ceiling. Mutations advertise confirmation and dry-run metadata; the existing approval gate must still run.

- NORM: list actions/scenes/folders/Shelf previews/widget stacks/OEM status, plus open a known action while Home is resumed.
- PRO: those actions plus capture/apply scenes, edit folder rules/aliases, publish text Shelf cards and select a previously bound widget.
- OEM/ADMIN: also request app suspension through an actually provisioned Android device/profile owner. A flavor name or same signer does not confer that role.
- New experience capabilities are not granted to LITE by this change. Its previous allowlist remains intact; Launcher independently retains its health-only Lite ceiling.

The Launcher service catalog supplies input schemas. `launcher.shelf.publish` accepts `{ "id": "stable-source-id", "title": "Note", "text": "Preview" }`, records the authenticated Workspace caller and accepts no URI grants. Card text is capped at 4,000 characters and all requests remain bounded to 16 KiB. `launcher.shelf.list` returns at most ten titles and short text previews; document contents are not shared.

`launcher.list_apps` now includes `componentKey` and `profileSerial` for accessible profiles. Pass the returned optional `profileSerial` to `launcher.open_app`; omission retains current-profile behavior.

The new Launcher build and this Workspace build are both needed for the full control catalog. Public text search handoff remains independent of this host-policy change. There is no Root/Shizuku implementation and no always-running launcher agent.


## Discovery and Home organization repair

A discovery call scoped by extension_id now filters before manifest fetch and before pagination.
It cannot return Workspace memory capabilities while claiming to inspect only Launcher. Responses
include advertised_count and allowed_count per extension and an actionable diagnostic for a legacy
Launcher APK. All result pages remain bounded; next_offset must be followed before claiming absence.
The one-entry, 20-second metadata cache is keyed by selected extension IDs and connection/catalog
revision. Package replacement drops the old Binder; reconnect/unbind invalidates cached metadata.
Package receiver queries/binds run on the existing bounded IO scope. Explicit refresh discards the
snapshot; execution always re-resolves a fresh schema and fails if the action is not advertised.

The companion Launcher now advertises 24 commands. Read `launcher.health` for app version and catalog
version 2. A protocol version is not a catalog version; install both matching signed APKs. Refreshing
an old installed six-command Launcher does not add commands. PRO/OEM/ADMIN can use the following after
confirmation and local receiver consent; NORM may read the layout only:

| Action | Contract |
| --- | --- |
| launcher.get_layout | Page occupancy, rows/columns, actual screen IDs and revision; hidden identities are omitted |
| launcher.move_items | expectedRevision + 1–40 {id, screenId, cellX, cellY}; only existing visible Home application icons; atomic swaps and collision checks |
| launcher.set_grid | expectedRevision, rows, columns; standard grid migration, 3–10 (up to 20 only if locally enabled); can reflow content |
| launcher.create_folder | title + nameContains/packagePrefix/category condition; creates a drawer rule folder, not a Home icon folder |

Call find_capability for the full schema, use `dry_run=true` to validate supported actions, then request
normal approval for the actual mutation. `dry_run` is forwarded as ActionRequest.dryRun rather than
inserted into a business payload. Unsupported dry runs fail explicitly. After a layout change, refetch
get_layout once reload completes; stale_layout means replan using the new revision. Schema absence never
bypasses approval by guessing an explicit extension/action pair. Complete nested rule/move schemas
come from the Launcher service; do not invent ids or mirror model cell coordinates in RTL.

Focused direct-JVM regressions execute with `python3 scripts/test_launcher_link.py` and are also run
by Android CI. They complement actual flavor compilation, receiver tests and device interoperability.
