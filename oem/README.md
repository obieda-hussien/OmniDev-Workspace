# Omni OEM System integration

The OEM assistant is a ROM component, not a Device Owner/EMM application. The model,
browser and UI retain their own UID. `:oem-system` is a separate platform-signed
APK with a fixed Binder action contract; no model-generated shell is accepted.
There is no `android.uid.system` shared UID in either APK.

## Build and install through the ROM

1. Build `:app:assembleOemRelease` and `:oem-system:assembleRelease`.
2. In the manufacturer's private ROM checkout, copy the resulting APKs to
   `prebuilts/OmniAssistant.apk` and `prebuilts/OmniSystemService.apk` under this directory.
3. Include `product.mk` and the Soong namespace. The platform signing key stays
   exclusively in the manufacturer's signing infrastructure; never commit it here.
4. Verify the resulting APK certificates and the same-partition privileged grant XML.
5. Provision the assistant role during device setup using the ROM's role configuration.
   Keep the user's ability to change or disable the assistant.
6. Validate Binder calls from the actual assistant UID and reject other package identities,
   differently signed APKs and shared-UID callers.

No globally permissive SELinux policy is supplied. Review generated app-domain
policy on the target ROM with enforcing mode enabled; the broker uses framework
APIs and needs no root daemon, shell execution, broad filesystem rules or `neverallow`
exceptions. A vendor requiring a custom HAL must provide a narrow domain/interface
and validate it independently before enabling that backend.

## Implemented system actions

`status`, selected System settings read/write, media/alarm volume, and Wi-Fi state
read/change are implemented. Settings and volume writes are read back. Wi-Fi changes
may return `pending=true`; that is not a verified completion. Mutations require an
unlocked device and the assistant's explicit confirmation gate. Requests cannot
read credentials, wipe the device, install arbitrary APKs, inject arbitrary input,
or invoke an unrestricted intent/shell.

Application actions use OmniLink's authenticated peer capabilities and consent.
OEM adapters for Gallery, Camera, Notes, Calendar and other manufacturer apps must
implement and advertise real actions; opening an app does not prove integration.

## Hardware voice and local models

The OEM voice service can use an already-enrolled manufacturer SoundTrigger keyphrase
through `AlwaysOnHotwordDetector` in the optional `com.omnidev.hardware` ROM library,
compiled against `system_current`. The Gradle APK uses a fixed adapter interface
without importing hidden platform classes. Availability is reported rather than inferred
from a build flag. Unsupported/un-enrolled hardware falls back to the existing trained
offline software listener. The manufacturer must supply HAL support, matching locale,
keyphrase enrollment and compatible sound models. This repository does not fabricate
a DSP sound model or claim hardware wake support on arbitrary phones.

Local GGUF inference, offline dictation and local speech output remain available.
Model redistribution requires the model's actual license. CPU inference is implemented;
vendor NPU/DSP model acceleration is not claimed without a tested vendor adapter.

## Required device verification

- Default-assistant invocation, manual invocation, trained software wake and enrolled hardware wake.
- Microphone privacy switch, revoked permissions, role changes and stopping listening.
- Locked-device mutation denial and unavailable protected screen context.
- Different signer/package/shared UID rejection, missing ROM grants and missing broker.
- Read-back of changes, Wi-Fi pending state, process death and concurrent calls.
- Multi-user/profile isolation, battery/temperature, memory pressure and OTA update compatibility.
- Manufacturer CTS/VTS and signing/permission/SELinux integration checks.

Host compilation and contract tests do not replace these device tests. No ROM has
been flashed and no manufacturer-specific Camera/Gallery/NPU integration is claimed.
