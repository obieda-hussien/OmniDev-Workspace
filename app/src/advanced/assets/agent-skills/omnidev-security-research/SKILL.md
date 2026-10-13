---
name: omnidev-security-research
description: Evidence-driven Android security research for OmniDev security tools. Use for authorized APK/app assessment, vulnerability triage, manifest/DEX/native/network analysis, exploitability verification, security patch generation, and regression validation.
---

# OmniDev Security Research

Work only on targets the user is authorized to assess. Prefer evidence and minimal verification over indiscriminate exploitation.

## Investigation funnel

1. Scope and inventory: identify package/version/SDK/attack surface and available privilege backend.
2. Static triage: manifest/exported components, permissions, network security config, DEX strings/APIs, secrets, crypto, native libraries, certificates.
3. Form hypotheses: every candidate finding needs a concrete vulnerable condition and plausible impact.
4. Targeted dynamic probe: test only hypotheses supported by static/runtime evidence.
5. Minimal verification: use the least invasive PoC or safe command that confirms exploitability. Preserve expected vs observed behavior.
6. Severity: rate verified impact, reachability, prerequisites, user interaction, privileges, and confidence separately.
7. Patch: generate the smallest code/manifest/network/ProGuard/configuration correction that fixes the root cause.
8. Regression: re-run the exact detector/probe plus relevant build/tests. A patch is not complete until the original condition no longer reproduces.

## Evidence discipline

Keep `observation`, `hypothesis`, `verification`, and `impact` distinct. Regex matches, dangerous permissions, exported components, or cleartext strings are signals—not automatically vulnerabilities. Mark unverified findings explicitly.

## OmniDev routing

Use `android_security_research` / `VulnResearchToolchain` for APK research, `security_analyzer` for focused posture/verification, manifest/network tools for attack-surface evidence, and root/Shizuku only when the active tier and target require them.

## Coverage model

Map relevant findings to MASVS domains: STORAGE, CRYPTO, AUTH, NETWORK, PLATFORM, CODE, RESILIENCE, PRIVACY. Use the mapping for coverage, not as a substitute for evidence.

## Safety

Do not exfiltrate real secrets, persist destructive payloads, damage user data, or probe unrelated systems. Redact credentials in reports and generated patches.
