---
name: omnidev-quality-gate
description: Independent verification and definition-of-done gate for OmniDev changes. Use after code/configuration/automation work, during swarm verification, or whenever correctness must be demonstrated with builds, tests, lint, state checks, or artifact evidence.
---

# OmniDev Quality Gate

Verification is a separate engineering phase. Judge the resulting artifact and observable state against the original request; do not accept the executor's claim that it worked.

## Build a frozen rubric

Before judging, derive a short checklist from the user's requirements and repository constraints. Do not silently invent unrelated requirements after implementation.

## Evidence hierarchy

Prefer deterministic evidence in this order where applicable: compile/build exit status, focused automated tests, migration/schema tests, lint/static checks, runtime/state queries, read-back/diff inspection, UI/DOM verification, then reasoned review. A green low-level check does not replace a required higher-level behavior check.

## Failure handling

Return failures with: criterion, evidence, likely cause, smallest corrective action, and exact re-check. Do not rewrite PASS around a failing required check. If a check cannot run because the environment lacks a dependency, distinguish `BLOCKED` from `FAIL`.

## Android matrix

Select variants based on changed boundaries. Shared/core/policy/database/build logic generally requires multiple flavors. Tier-specific work must compile its own flavor and verify it did not leak forbidden dependencies/permissions into lower tiers.

## Security and browser work

Security findings require reproducible evidence and regression of the original probe. Browser automation requires final URL/DOM/state confirmation and error inspection, not only successful click calls.

## Final verdict

Use PASS only when every required criterion is supported by evidence. Otherwise use FAIL or BLOCKED and preserve partial successes explicitly.
