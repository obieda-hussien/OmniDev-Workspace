# Security maintenance review

Reviewed baseline: `6d94fcf49dbc4db22742089940f25fd900524ceb`, plus the changes in PR #186.
This is a source and dependency review. It does not establish that every privileged
execution path is exploitable or that the entire application is vulnerability-free.

## Credential storage and backup

`ApiKeyRepository` already encrypts provider keys with AES-GCM using Android
Keystore and migrates legacy plaintext preferences. The main manifest already
sets `android:allowBackup="false"`. A claim that this baseline enables Auto Backup
or stores all provider keys as plaintext would be incorrect.

This maintenance change additionally excludes `datastore/omnidev_api_keys.preferences_pb`
and `copilot_session_secure_v1.xml` from legacy backup, cloud backup and device
transfer rules. Existing exclusions for device/voice consent remain in place.

## Broadcast boundaries

- `BootReceiver` is now non-exported. Android system boot, unlock and package
  replacement broadcasts remain supported; the receiver also checks its action.
- `OmniSmsReceiver` stays exported for telephony delivery and requires the
  system sender permission `android.permission.BROADCAST_SMS`. It only accepts
  the expected SMS action. Lite and OEM already remove this receiver.
- `RECEIVE_BOOT_COMPLETED` is a receiver capability, not a system-only sender
  restriction, and must not be suggested as a spoofing fix.

`scripts/verify_flavors.py` checks these manifest and backup invariants alongside
the existing flavor boundaries. Physical-device boot/SMS delivery is a separate
validation step, especially for manufacturer-specific quick-boot broadcasts.

## Dependency boundaries

The vulnerable JGit version is an application dependency in advanced flavors;
the update uses its patched 6.10.1 maintenance line and keeps it out of Lite.

The inspected Netty alert comes through Android test tooling. The inspected
Jackson/jsoup alerts come through OWASP Dependency-Check. These build-classpath
copies must be repaired at their source; adding them as APK implementation
dependencies would not repair the build tool's classloader.

This change updates Dependency-Check, aligns build-classpath security minimums,
and checks resolved inventories for build tools and all five APK runtime graphs.
The runtime check also prevents unrelated updates from raising Core above the
explicit 1.17.0 baseline. No Dependabot alert is dismissed on the basis of this file.

## Privileged command execution

Shell execution is an intentional tool capability. Its presence alone is not
proof of unauthenticated network RCE. Review the caller, consent gate, flavor
policy and untrusted argument construction before assigning an exploit path.

For a fixed executable, pass a command/argument list directly to `ProcessBuilder`.
When shell syntax is necessary, bind or quote each untrusted argument. Quoting
the entire command string turns it into a literal executable name and is not a
valid replacement for argument handling. This maintenance change does not remove
authorized shell capabilities or claim a complete privileged-execution audit.

## Historical Telegram credential

GitHub reports a historical public Telegram bot-token leak. The current CI uses
GitHub Secrets. Current-tree scanning now detects Telegram credentials including
valid tokens ending with a hyphen, with synthetic boundary regressions.

Moving a token to Secrets or deleting its source does not revoke it. Revocation
and replacement must happen at Telegram, followed by updating the protected CI
secret. Provider revocation has not been verified by this source review; keep
the historical alert open until that is established. Never reproduce the token
in reports, pull-request discussions or build logs.
