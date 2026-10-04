# Device access and the floating assistant

The floating assistant and the full agent use the same Android grants and build-tier policy. Open **Settings → Omni on your screen → Device access and permissions**, or the shield button in the floating assistant. The latter uses an Activity-result handoff and returns to the existing conversation after setup.

## Access paths

| Path | What is checked or requested | Requirements and limits |
|---|---|---|
| Runtime permissions | Discover dangerous permissions in the merged APK through `PackageManager`, including installed companion-app permissions | Only permissions defined on the current device and supported on its API level are requested. Android may deny restricted permissions, remember previous denials, or offer partial media access. |
| Background access | Location, legacy body sensors, Android 16 background health | Separate from the foreground batch; foreground prerequisites come first. Android 11+ background location opens the app permission page. Health runtime requests route to the Health Connect permission controller. |
| Special access | Assistant role, Accessibility, overlay, notification listener, usage stats, settings, DND, shared files/media, APK installation, exact alarms, battery exclusion, IME, VPN, Device Admin | Each has its own Android setup flow and is rechecked on return. Settings pages are not reported as successful grants. Unsupported or stripped capabilities are hidden. |
| Shizuku / rish | Authorized Android shell execution and development-permission grants | ADB-backed Shizuku is shell UID 2000; root-backed Shizuku may have UID 0. Device restrictions still apply. Shizuku is explicitly authorized, rather than repeatedly prompting during bulk requests. |
| Root | Explicit `su` UID 0 verification, root-backed grants | Pro/Admin policy plus a rooted device and superuser approval. Passive diagnostics only read the short-lived root cache; an unprobed/expired cache is `NOT_PROBED`. Root is never silently chosen for a shell grant. |
| System / owner | Actual system UID and Device Owner/Profile Owner state | Build flags do not supply these entitlements. System execution is selected only for a genuine system UID. Owner authority requires Android provisioning. Device Admin alone is not Device Owner. |
| Connected applications | Termux command permission and existing OmniLink capabilities | Companion apps must be installed and independently authorized. Termux also requires `allow-external-apps=true`. OmniLink retains per-peer trust and per-capability grants. Permission bootstrap does not grant another application's private database or account session. |

The manifest adds phone-number/basic-phone-state, WAP push, Android 16 ranging, granular health sensor, and high-frequency sensor declarations. New declarations are stripped from Lite. Android 16 sensor grants have a protected permission-usage/privacy rationale destination. These declarations prepare sensor authorization; they do not add a Health Connect record reader or health monitoring. Root, system, role and signature-only permissions remain subject to their real platform entitlement.

## Permission declarations

The main manifest declares 207 unique permission names. A declaration only requests eligibility; the platform, API level, build variant and user's grants determine actual access.

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

The phone/camera/microphone, contacts/calendar/SMS, location, Bluetooth/Wi-Fi, shared media/storage, overlay, settings, notification, Shizuku and system declarations already exist in the main manifest. The access manager provides setup and diagnostics for them. Health monitoring, ranging operations and WAP message processing are not implemented merely by adding their permissions.

### Extended declarations and actual authority

The following declarations support deeper diagnostics and entitled integrations. All are removed from Lite. Protection levels below describe the Android 16 platform definition; older releases and OEMs may differ, so the installed device's `PermissionInfo` is authoritative.

| Permission suffix under `android.permission.` | Intended access | Android 16 protection |
|---|---|---|
| `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` | Start foreground services for an associated companion device; requires an implemented integration | `normal` |
| `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE` | Observe presence of an associated device; declaration alone does not register a presence observer | `normal` |
| `REQUEST_OBSERVE_DEVICE_UUID_PRESENCE` | System-level UUID-based nearby-device presence observation on Android 16 | `signature|privileged` |
| `READ_CLIPBOARD_IN_BACKGROUND` | Background clipboard eligibility for an entitled system/role holder; existing clipboard tools remain task-driven | `signature|role` |
| `GET_APP_OPS_STATS` | Read AppOps statistics; included in the verified development-grant setup | `signature|privileged|development` |
| `MANAGE_NOTIFICATIONS` | System-level notification administration | `signature` |
| `ACCESS_NOTIFICATIONS` | Protected notification access; separate from user-approved NotificationListenerService | `signature|privileged|appop` |
| `READ_PRIVILEGED_PHONE_STATE` | Protected phone-state/device information under platform restrictions | `signature|privileged|role` |
| `MODIFY_AUDIO_ROUTING` | Protected audio routing | `signature|privileged|role` |
| `NETWORK_SETTINGS` | Protected network configuration | `signature` |
| `MANAGE_USB` | Protected USB administration | `signature|privileged` |
| `CHANGE_APP_IDLE_STATE` | Protected app-idle state management | `signature|privileged` |
| `CHANGE_DEVICE_IDLE_TEMP_WHITELIST` | Protected temporary device-idle allowlisting | `signature|privileged` |
| `INTERACT_ACROSS_USERS` | Cross-user eligibility; included in development setup, subject to Android's per-operation limits | `signature|privileged|development|role` |

The access center exposes install-time/protected declaration states and the route required for each: install-time grant, runtime dialog, special access, development backend, managed-profile consent, or platform signature/privileged/role entitlement. It reports the number of existing CompanionDeviceManager associations. It does not pair devices, register presence observers or add USB/audio/network administration merely by declaring eligibility. It does not change the existing filtering of sensitive notification content or browser credentials.

### Own-app AppOps

Authorized Shizuku/rish, genuine system execution or explicitly selected root can set five allowlisted special-access modes for **OmniDev's own package and Android user**: overlay, usage statistics, write settings, all-files access (API 30+) and media management (API 31+). Each operation checks its merged-manifest declaration and API compatibility, and records an audit event. Success requires both the raw AppOps mode and effective Android access check; foreground-only modes never prove a full allow grant. Generic package/operation arguments are not accepted.

`reset_appop_*` restores that operation to Android's default mode. Default mode can still permit access through another entitlement; resetting is not a guarantee of denial. Unavailable backends return a setup instruction rather than silently switching to root. Model-originated AppOps requests pass the existing privileged confirmation gate; access-center buttons are explicit user actions.

Cross-profile setup uses `CrossProfileApps` on API 30+ and requires the same app in an eligible work profile plus administrator authorization. This consent is distinct from the `INTERACT_ACROSS_USERS` development grant. Platform entitlements do not automatically connect OmniLink to a peer or disclose another application's account/private data.

## Implementation files

| File | Responsibility |
|---|---|
| `PermissionManagerTool.kt` | Runtime permission discovery, aliases, individual/bulk requests, protected-grant verification |
| `PermissionRequestPlan.kt` | API compatibility, foreground dialog companions, background dependencies |
| `AppOpAccessPlan.kt` | Fixed own-app operation list, API/declaration requirements and scoped allow/reset commands |
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
request_permission(permission="appop_bootstrap")
request_permission(permission="appop_all_files")
request_permission(permission="reset_appop_all_files")
request_permission(permission="appop_overlay", backend="root")
request_permission(permission="cross_profile")
request_permission(permission="termux")
```

`bootstrap_max` covers supported **runtime** permissions; background grants stay separate even when an authorized privileged backend is present. The development bootstrap covers `WRITE_SECURE_SETTINGS`, `READ_LOGS`, `DUMP`, `BATTERY_STATS`, `CHANGE_CONFIGURATION`, `GET_APP_OPS_STATS` and `INTERACT_ACROSS_USERS`. Each privileged grant is read back with `checkSelfPermission`; command output or a zero exit code alone never proves it granted. Shell arguments are quoted and the package/user target comes from the running app. Failed grants are not replayed on a more privileged backend.

## Setup step by step

1. Open the shield on the floating assistant or **AI Settings → Omni on your screen → Device access and permissions**. Review the Android version and build tier.
2. Choose **Request runtime access**, or grant individual entries. Grant foreground location/sensors before using the separate background section. Android 16 background health is requested through the runtime permission path so Android can route it to Health Connect.
3. Configure assistant selection, Accessibility, bubble/notification access and any other needed special entries. Return to the access center and check their actual status.
4. On a Shizuku-enabled build, start Shizuku and choose **Authorize Shizuku**, or configure rish in execution settings. On Pro/Admin with an already rooted device, **Verify root access** explicitly requests superuser authorization. OEM system execution requires actual ROM entitlement.
5. Choose **Grant settings and diagnostics** for the seven development declarations. Read each result; a blocked grant remains denied.
6. Under **Advanced special access**, allow individual own-app AppOps through the authorized backend. Use **Restore default** for the same backend when reverting an operation.
7. Install and authorize connected apps independently. Termux requires `allow-external-apps=true`; OmniLink needs a trusted peer and capability grants. Work-profile consent appears only when Android's managed-profile prerequisites are met.
8. Refresh the state or call `check_permission(permission="all")`. Android 11 uses its supported location/storage paths; Android 16-only health/ranging/UUID capabilities remain unavailable there.

The assistant keeps `check_permission`, `request_permission`, UI interaction and `omni_link` schemas available, includes messaging tools within the existing tier/chat gates, and receives a fresh compact access snapshot before each request. Root schemas remain selected by task relevance. Individual tools must still check their own operation's permission and report revocation or denial. Existing flavor and action-confirmation rules apply.

## Verification

`PermissionRequestPlanTest` covers storage on Android 10/11, location batching on Android 12, media on Android 13/14, notifications/nearby API boundaries, separated background steps, Android 16 sensor migration/ranging, health-controller routing and companion presence boundaries. `AppOpAccessPlanTest` checks user/package scoping, reset behavior, unsupported Android versions, stripped manifests and rejection of arbitrary shell/operation input. `AssistantFlavorPolicyTest` checks discoverability without granting absent tier capabilities.

```bash
./gradlew :app:testNormDebugUnitTest --tests '*PermissionRequestPlanTest' --tests '*AppOpAccessPlanTest' --tests '*AssistantFlavorPolicyTest'
./gradlew :app:testProDebugUnitTest --tests '*PermissionRequestPlanTest' --tests '*AppOpAccessPlanTest' --tests '*AssistantFlavorPolicyTest'
./gradlew :app:compileNormDebugKotlin :app:compileProDebugKotlin :app:compileAdminDebugKotlin
```

Device checks cover the native assistant handoff, foreground runtime dialogs, Android 11 storage, partial photo grants, Shizuku restart/revocation and a real root manager prompt. Review completed CI jobs for compilation and JVM results; XML parsing and source checks alone do not establish APK/device behavior.

## Platform references

- [Android special permissions](https://developer.android.com/training/permissions/requesting-special)
- [Android 16 platform permission definitions](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/core/res/AndroidManifest.xml)
- [Cross-profile consent](https://developer.android.com/reference/android/content/pm/CrossProfileApps)
- [Background location](https://developer.android.com/develop/sensors-and-location/location/permissions/background)
- [Android 16 sensor migration](https://developer.android.com/about/versions/16/behavior-changes-16#health-fitness)
- [Health permission rationale](https://developer.android.com/health-and-fitness/guides/health-connect/develop/get-started)
- [Shizuku identity and limitations](https://github.com/RikkaApps/Shizuku)
