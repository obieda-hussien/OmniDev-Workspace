# Omni Launcher integration

Workspace now receives the OmniLink v3.0.0 public ASK_OMNI/OPEN_OMNI action through AssistantActivity.
The launcher sends an explicit, user-visible intent to an installed Workspace variant. No new
SDK release or AIDL ABI is required. Existing same-signer extension discovery/control stays separate
from unprivileged search ingress.

## Search handoff

Choose Omni in Launcher → Search bar → Search provider, or tap Ask Omni in app-only/on-device local
search. The launcher offers the row on a nonblank local miss, with optional always-show and disable
settings. ASI search, Smartspace, and Google feed are separate surfaces.

AssistantActivity accepts the SDK public action, consumes the JSON extra once, and handles both
cold launch and singleTop onNewIntent. OmniSearchIngress checks the 32 KiB SDK byte budget,
parses the typed request, and limits ASK_OMNI to 8,192 nonblank characters. OPEN_OMNI opens the floating assistant
without importing a prompt. Malformed or unsupported SHARE_TO_OMNI requests are ignored.
Public extras are never used as identity, tier, approval, auto-run, tool, session, URL or grant
instructions. The received text is a draft and nothing executes before the user presses Send.
A pre-existing composer draft is preserved and appended to; an active agent run is not cancelled.

## Launcher control via omni_link

Discover launcher.* capabilities with query=launcher and refresh=true; pass extension_id to scope
one provider and follow next_offset until null. Check launcher.health for app/catalog versions,
then read settings or list_apps as needed. Discovery reports advertised_count and allowed_count
separately; the six filtered commands alone cannot identify the installed Launcher version.
Use exact components from list_apps. Mutations use the existing capability approval gate. The
launcher independently enforces local opt-in, exact Workspace package, and same certificate.
Enable the toggle locally in Launcher; the agent may never enable it remotely or bypass a refusal.

| Capability | ADMIN / PRO / OEM | NORM | LITE |
| --- | --- | --- | --- |
| launcher.health | Yes | Yes | Yes |
| launcher.get_settings | Yes | Yes | No |
| launcher.list_apps | Yes | Yes | No |
| launcher.set_preference | With approval | No | No |
| launcher.open_app / launcher.open_drawer | With approval | With approval | No |
| launcher.list_actions / launcher.list_scenes / launcher.list_folders | Yes | Yes | No |
| launcher.shelf.list / launcher.widget_stacks.list / launcher.oem.status | Yes | Yes | No |
| launcher.get_layout | Yes | Yes | No |
| launcher.open_action | With approval | With approval | No |
| launcher.save_scene / launcher.apply_scene / launcher.set_folder_rule / launcher.set_alias | With approval | No | No |
| launcher.shelf.publish / launcher.widget_stacks.select | With approval | No | No |
| launcher.move_items / launcher.set_grid / launcher.create_folder / launcher.create_home_folder | With approval | No | No |
| launcher.oem.suspend_app | OEM/ADMIN only, with approval and provisioned owner role | No | No |

Only known Launcher package IDs (including supported debug/nightly/play variants) and known
capabilities are accepted by OmniLinkTierCapabilityPolicy. The receiving launcher repeats these flavor ceilings. SDK verified identity is still mandatory;
package name does not authorize anything. Public search needs no signature match; control does.

set_preference accepts exactly key/value: search_provider (provider ID), omni_suggestions (boolean),
or omni_always_suggest (boolean). It cannot clear Home, alter hidden apps, enable root or consent,
or modify arbitrary settings. Use omni_link dry_run=true for a supported capability; Workspace
forwards ActionRequest.dryRun without inserting it into the business payload. App opening/drawer
actions need resumed Home and return foreground_required otherwise. Missing capability schemas
fail before execution, including explicit-extension requests; they cannot bypass approval.

The current launcher.list_apps inventory includes visible launchable apps in accessible Android
profiles, excludes hidden apps, and is paged at 1–40 entries (default 20). Each entry includes
component, componentKey and profileSerial. Pass the returned profileSerial with component to
launcher.open_app for a specific profile; omission retains current-profile behavior. Do not
collapse same-package apps from different profiles. Never claim success after a refusal.

The matching Launcher catalog v3 advertises 25 commands. Home moves use get_layout revisions and
exact item/screen IDs; conflicts/stale state are rejected atomically. set_grid uses the normal
launcher migration and may reflow content. create_folder creates a drawer rule folder, not a
Home icon folder; create_home_folder atomically groups existing visible Home icons. After reloadScheduled, verify with get_layout. See
[Home schemas, examples and limits](LAUNCHER_EXPERIENCES.md) for details. Both matching signed
Launcher and Workspace builds must be installed; refresh cannot add code to an older APK.

## Validation

OmniSearchIngressTest covers Arabic, empty/malformed/oversized requests, OPEN_OMNI, rejected kinds,
and ignored authority extras. OmniLinkTierCapabilityPolicyTest covers flavor ceilings, spoofed
package IDs, unknown tiers and unknown launcher capabilities. Run `:app:testLiteDebugUnitTest`
and the normal flavor CI builds. The focused discovery/host-policy suite executes 12 tests with
scripts/test_launcher_link.py locally and on CI. Launcher executes 44 direct JVM regressions and
its three APK variants pass CI. Workspace quality compilation and all-flavor unit tests passed;
full Lint, API-30 UI tests and distributable release jobs were still running at the merge check:
[Workspace CI](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/38869246104).
Device interoperability is separately required. Test cold/warm handoff, rotation, rapid search,
existing draft/active run, signer mismatch, revoked consent, accessible/hidden profiles,
foreground gating, paged discovery, Home collision checks and stale-revision replanning.

## Maintainer and original authors

Workspace integration development, updates and maintenance are attributed to
**Abdelrahman Hussein (Obieda)**. Omni Launcher remains a Lawnchair/AOSP-derived
fork whose original authors retain their credits and licenses. The SDK also retains its separate
owner notices and license. See [ATTRIBUTION.md](../ATTRIBUTION.md). Existing rights notices and
historical documents are preserved; this guide describes only the new application contract.

### Floating search handoff update

The public SDK intent filter now resolves to AssistantActivity. ASK_OMNI imports validated text into an unsent assistant draft; OPEN_OMNI opens that assistant. No request executes automatically or enables control. Existing draft text and in-flight work are preserved, and a recreated host does not import the same draft twice. Explicit legacy MainActivity callers still use its existing ingress handling. The matching Launcher fixes its multi-install chooser and bounds optional preferred-target persistence.

Catalog version 3 adds launcher.create_home_folder for actual Home icons; see LAUNCHER_EXPERIENCES.md for the atomic grouping and execution-evidence contract. Update both installed APKs with the existing signing key; discovery cannot add implementation to an old build.
