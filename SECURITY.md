# Security policy

OmniDev Workspace combines model requests, local files, Android capabilities and connected services. Security reports should identify the actual trust boundary and reproducible effect, rather than relying only on a tool/model description.

## Maintained scope

The current `main` branch is the primary maintenance target. There is no separately promised long-term-support release series or fixed security-response service level. When reporting a packaged build, include its commit/build reference and edition so the issue can be compared with current source.

All five flavors can be relevant to a report. Distinguish edition eligibility from actual device authorization: a broad Admin policy is not intended as a public consumer default, and an OEM flag does not make a sideloaded APK platform privileged.

## Report privately

Use the repository's **Security → Report a vulnerability** route when GitHub private vulnerability reporting is enabled:

[Open the private vulnerability-reporting form](https://github.com/obieda-hussien/OmniDev-Workspace/security/advisories/new).

The existence of this file does not itself enable GitHub's private reporting setting. If the form is unavailable, use a private contact explicitly listed by the [maintainer](https://github.com/obieda-hussien). If no such contact is available, open a public issue titled **Security contact requested**, with no exploit, credential, affected-user details or private evidence, and request a private route.

Do not disclose an unresolved exploit in a public PR/issue or upload private files as public attachments. Coordinate publication of technical details with the maintainer after impact and a fix have been assessed. This project does not currently advertise a bounty program or a guaranteed response deadline.

## Useful report details

- Affected commit/build, flavor, Android API level, device/OEM and relevant connected-app versions.
- Required permissions, account state and whether the device is rooted/provisioned.
- Expected boundary and actual unauthorized action/data exposure.
- Minimal reproduction using accounts/devices/files you control.
- Sanitized logs or a minimal proof of concept that does not contain real credentials.
- Impact, reliability and suggested mitigation if known.
- Whether a side effect was accepted by an external service, and any uncertainty about the outcome.

Distinguish a source-only hypothesis from a reproduced device/service behavior. Describe any constraints that materially affect exploitability.

## Important review areas

| Area | Boundary to evaluate |
|---|---|
| Tool proposals | Exact exposed names, whole-batch validation and action-dependent arguments |
| Flavor/tool policy | Catalog filtering and execution-time rechecks, including revocation |
| Files and projects | Target Context containment, traversal, symlinks, bounded reads/writes and rollback scope |
| Connected apps | Real signer/UID, receiver-side consent, accepted capabilities and payload grants |
| MCP and observations | Untrusted content, unknown effects, credential destinations and remote-server behavior |
| Media/jobs | Bounded output, redirect/key separation, uncertain create outcomes and late revision delivery |
| Voice/credentials | Protected local collection, no ordinary observation/capture during input and one-attempt policy |
| Learned tasks | User approval, fresh selectors, checkpoint durability and uncertain-mutation handling |
| Messaging | Owner filtering, token-bound cursors and repeated-delivery boundaries |
| Build/distribution | Signing identity, secret handling, pinned automation and private Admin delivery |

## Data-storage qualifications

Do not assume every application store is encrypted. Provider keys currently use a dedicated app-private Preferences DataStore, and the Room database is not described as universally encrypted. Wake profiles and the saved-PIN vault use separate AndroidKeyStore-backed encrypted storage; Telegram pairing also has an integration-specific encrypted store. Rooted/compromised device access has a different exposure model from an ordinary sandboxed app.

Do not claim that acoustic wake matching is secure biometric authentication. Lock credentials are still validated by Android, and a spoken code can be overheard/replayed. Physical OEM keyguard behavior needs separate acceptance testing.

## Repository checks

```sh
python3 scripts/public_repo_guard.py
python3 scripts/public_repo_guard.py --history
```

The existing guard checks tracked files, selected credential patterns, sensitive filenames, workflow settings and optional Git history without printing matched values. This reduces accidental exposure; it is not a full static/dynamic audit. Advisory dependency tooling is not a substitute for reviewing affected dependencies and packaged artifacts.

If a real credential leaks, revoke/rotate it at the issuing provider before treating source removal as a fix. Deleting a tracked value does not remove prior copies/history or terminate a provider credential.

## Security-related changes

A fix should preserve or strengthen receiver/runtime enforcement, include a regression that reproduces the boundary failure and state its device/provider validation limits. Coordinate cross-app fixes when the caller and receiver share a protocol. Keep credentials, signing keys and real private reports out of public test fixtures.

See [Privacy](PRIVACY.md), [Contributing](CONTRIBUTING.md), [tool contracts](docs/TOOL_EXECUTION_CONTRACT.md) and [device-lock access](docs/DEVICE_LOCK_ACCESS.md).
