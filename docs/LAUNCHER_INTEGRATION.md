# Omni Launcher integration

Workspace now receives the OmniLink v3.0.0 public ASK_OMNI/OPEN_OMNI action through MainActivity.
The launcher sends an explicit, user-visible intent to an installed Workspace variant. No new
SDK release or AIDL ABI is required. Existing same-signer extension discovery/control stays separate
from unprivileged search ingress.

## Search handoff

Choose Omni in Launcher → Search bar → Search provider, or tap Ask Omni in app-only/on-device local
search. The launcher offers the row on a nonblank local miss, with optional always-show and disable
settings. ASI search, Smartspace, and Google feed are separate surfaces.

MainActivity accepts only the SDK public action, consumes the JSON extra once, and handles both
cold launch and singleTop onNewIntent. OmniSearchIngress checks the 32 KiB SDK byte budget,
parses the typed request, and limits ASK_OMNI to 8,192 nonblank characters. OPEN_OMNI opens chat
without importing a prompt. Malformed or unsupported SHARE_TO_OMNI requests are ignored.
Public extras are never used as identity, tier, approval, auto-run, tool, session, URL or grant
instructions. The received text is a draft and nothing executes before the user presses Send.
A pre-existing composer draft is preserved and appended to; an active agent run is not cancelled.

## Launcher control via omni_link

Discover launcher.* capabilities, check launcher.health, then read settings or list_apps as needed.
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

Only known Launcher package IDs (including supported debug/nightly/play variants) and known
capabilities are accepted by OmniLinkTierCapabilityPolicy. The receiving launcher repeats these flavor ceilings. SDK verified identity is still mandatory;
package name does not authorize anything. Public search needs no signature match; control does.

set_preference accepts exactly key/value: search_provider (provider ID), omni_suggestions (boolean),
or omni_always_suggest (boolean). It cannot clear Home, alter hidden apps, enable root or consent,
or modify arbitrary settings. Supports dryRun. App opening/drawer actions need resumed Home and
return foreground_required otherwise. App inventory excludes hidden apps and other profiles and
is paged at 1–40 entries (default 20). Never claim success after a refusal.

## Validation

OmniSearchIngressTest covers Arabic, empty/malformed/oversized requests, OPEN_OMNI, rejected kinds,
and ignored authority extras. OmniLinkTierCapabilityPolicyTest covers flavor ceilings, spoofed
package IDs, unknown tiers and unknown launcher capabilities. Run `:app:testLiteDebugUnitTest`
and the normal flavor CI builds. Direct Kotlin/JUnit execution passed all 13 shared-logic tests
(including Launcher policies) using actual SDK protocol sources and serialization compiler support.
Workspace Gradle wrapper bootstrap failed locally. Launcher configured via Gradle 9.3.0 but
its build stopped because the installed Java 17 toolchain lacks JAVA_COMPILER. Device checks and
full Android compilation are still required. Test cold/warm handoff, rotation, rapid search,
existing draft/active run, signer mismatch, revoked consent, hidden apps and foreground gating.

## Maintainer and original authors

Workspace integration development, updates and maintenance are attributed to
**Abdelrahman Hussein (Obieda)**. Omni Launcher remains a Lawnchair/AOSP-derived
fork whose original authors retain their credits and licenses. The SDK also retains its separate
owner notices and license. See [ATTRIBUTION.md](../ATTRIBUTION.md). Existing rights notices and
historical documents are preserved; this guide describes only the new application contract.
