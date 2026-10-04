# Device access and the floating assistant

The floating assistant and the full agent use the same Android grants and build-tier policy. Open **Settings → Omni on your screen → Device access and permissions**, or the shield button in the floating assistant. The latter uses an Activity-result handoff and returns to the existing conversation after setup.

## Access paths

| Path | What is checked or requested | Requirements and limits |
|---|---|---|
| Runtime permissions | Discover dangerous permissions in the merged APK through `PackageManager`, including installed companion-app permissions | Only permissions defined on the current device and supported on its API level are requested. Android may deny restricted permissions, remember previous denials, or offer partial media access. |
| Background access | Location, legacy body sensors, Android 16 background health | Separate from the foreground batch; foreground prerequisites come first. Android 11+ background location opens the app permission page. Health access can require the health permission controller. |
| Special access | Assistant role, Accessibility, overlay, notification listener, usage stats, settings, DND, shared files/media, APK installation, exact alarms, battery exclusion, IME, VPN, Device Admin | Each has its own Android setup flow and is rechecked on return. Settings pages are not reported as successful grants. Unsupported or stripped capabilities are hidden. |
| Shizuku / rish | Authorized Android shell execution and development-permission grants | ADB-backed Shizuku is shell UID 2000; root-backed Shizuku may have UID 0. Device restrictions still apply. Shizuku is explicitly authorized, rather than repeatedly prompting during bulk requests. |
| Root | Explicit `su` UID 0 verification, root-backed grants | Pro/Admin policy plus a rooted device and superuser approval. Passive diagnostics only read the short-lived root cache; an unprobed/expired cache is `NOT_PROBED`. Root is never silently chosen for a shell grant. |
| System / owner | Actual system UID and Device Owner/Profile Owner state | Build flags do not supply these entitlements. System execution is selected only for a genuine system UID. Owner authority requires Android provisioning. Device Admin alone is not Device Owner. |
| Connected applications | Termux command permission and existing OmniLink capabilities | Companion apps must be installed and independently authorized. Termux also requires `allow-external-apps=true`. OmniLink retains per-peer trust and per-capability grants. Permission bootstrap does not grant another application's private database or account session. |

The manifest adds phone-number/basic-phone-state, WAP push, Android 16 ranging, granular health sensor, and high-frequency sensor declarations. New declarations are stripped from Lite. Android 16 sensor grants have a protected permission-usage/privacy rationale destination. These declarations prepare sensor authorization; they do not add a Health Connect record reader or health monitoring. Root, system, role and signature-only permissions remain subject to their real platform entitlement.

## Permission declarations

The main manifest declares 193 unique permission names. A declaration only requests eligibility; the platform, API level, build variant and user's grants determine actual access.

| Permission | Purpose | Access type |
|---|---|---|
| `android.permission.READ_PHONE_NUMBERS` | Read phone numbers when Android exposes them to the caller | Runtime; Android/provider restrictions apply |
| `android.permission.READ_BASIC_PHONE_STATE` | Basic non-sensitive phone-state information | Normal install-time permission on supported Android versions |
| `android.permission.RECEIVE_WAP_PUSH` | Receive WAP push messages | Runtime; restricted telephony permission |
| `android.permission.RANGING` | Android 16's unified device ranging API | Runtime; supported device/hardware required |
| `android.permission.health.READ_HEART_RATE` | Authorize heart-rate sensor access | Android 16 sensor authorization |
| `android.permission.health.READ_OXYGEN_SATURATION` | Authorize oxygen-saturation sensor access | Android 16 sensor authorization |
| `android.permission.health.READ_SKIN_TEMPERATURE` | Authorize skin-temperature sensor access | Android 16 sensor authorization |
| `android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND` | Authorize supported background health/sensor access | Separate background step after foreground authorization |
| `android.permission.HIGH_SAMPLING_RATE_SENSORS` | Allow supported sensor sampling above 200 Hz | Normal install-time permission on supported Android versions |

The phone/camera/microphone, contacts/calendar/SMS, location, Bluetooth/Wi-Fi, shared media/storage, overlay, settings, notification, Shizuku and system declarations already exist in the main manifest. The access manager provides setup and diagnostics for them. The development bootstrap requests the existing `WRITE_SECURE_SETTINGS`, `READ_LOGS`, `DUMP` and `BATTERY_STATS` declarations through an authorized backend and verifies each grant. Health monitoring, ranging operations and WAP message processing are not implemented merely by adding their permissions.

## Implementation files

| File | Responsibility |
|---|---|
| `PermissionManagerTool.kt` | Runtime permission discovery, aliases, individual/bulk requests, protected-grant verification |
| `PermissionRequestPlan.kt` | API compatibility, foreground dialog companions, background dependencies |
| `DeviceAccessCatalog.kt` | Special access, exact service components, backend and owner diagnostics |
| `PermissionRequestBridge.kt` | Foreground Activity reference for Android permission dialogs |
| `DeviceAccessActivity.kt` | User-facing setup, request buttons and status refresh |
| `HealthAccessRationaleActivity.kt` | Health/sensor permission-usage explanation |
| `AssistantFlavorPolicy.kt` | Available assistant tool domains and setup tools |
| `AssistantInputActivity.kt`, `AssistantRuntime.kt` | Result handoff and preservation of the current assistant session |
| `CompositeToolManager.kt`, `ChatViewModel.kt` | Tool dispatch, confirmation gate and current access context |

Tool files live in `data/tools/`, assistant state/policy in `data/assistant/`, interface Activities in `ui/assistant/`, and the chat ViewModel in `ui/chat/`, under `app/src/main/java/com/omnidev/workspace/`.

Accessibility's existing key-event filter declaration now includes its required capability attribute. This enables Android's filter capability; it does not add logging of key events. Protected screenshot windows remain unavailable.

## Agent tools

```text
check_permission(permission="all")
request_permission(permission="access_center")
request_permission(permission="bootstrap_max")
request_permission(permission="background_location")
request_permission(permission="shizuku")
request_permission(permission="root")
request_permission(permission="privileged_bootstrap")
request_permission(permission="privileged_bootstrap", backend="root")
request_permission(permission="termux")
```

`bootstrap_max` covers supported **runtime** permissions, not all signature permissions or all special access. The development bootstrap covers `WRITE_SECURE_SETTINGS`, `READ_LOGS`, `DUMP` and `BATTERY_STATS`. Each privileged grant is read back with `checkSelfPermission`; command output or a zero exit code alone never proves it granted. Shell arguments are quoted and the package/user target comes from the running app. Failed grants are not replayed on a more privileged backend.

The assistant keeps `check_permission`, `request_permission`, UI interaction and `omni_link` schemas available, includes messaging tools within the existing tier/chat gates, and receives a fresh compact access snapshot before each request. Root schemas remain selected by task relevance. Individual tools must still check their own operation's permission and report revocation or denial. Existing flavor and action-confirmation rules apply.

## Verification

`PermissionRequestPlanTest` covers storage on Android 10/11, location batching on Android 12, media on Android 13/14, notifications/nearby API boundaries, separated background steps, Android 16 sensor migration/ranging and companion permission handling. `AssistantFlavorPolicyTest` checks discoverability without granting absent tier capabilities.

```bash
./gradlew :app:testNormDebugUnitTest --tests '*PermissionRequestPlanTest' --tests '*AssistantFlavorPolicyTest'
./gradlew :app:testProDebugUnitTest --tests '*PermissionRequestPlanTest' --tests '*AssistantFlavorPolicyTest'
./gradlew :app:compileNormDebugKotlin :app:compileProDebugKotlin :app:compileAdminDebugKotlin
```

Device checks cover the native assistant handoff, foreground runtime dialogs, Android 11 storage, partial photo grants, Shizuku restart/revocation and a real root manager prompt. Review completed CI jobs for compilation and JVM results; XML parsing and source checks alone do not establish APK/device behavior.

## Platform references

- [Android special permissions](https://developer.android.com/training/permissions/requesting-special)
- [Background location](https://developer.android.com/develop/sensors-and-location/location/permissions/background)
- [Android 16 sensor migration](https://developer.android.com/about/versions/16/behavior-changes-16#health-fitness)
- [Health permission rationale](https://developer.android.com/health-and-fitness/guides/health-connect/develop/get-started)
- [Shizuku identity and limitations](https://github.com/RikkaApps/Shizuku)
