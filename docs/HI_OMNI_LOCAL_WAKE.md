# Hi Omni: local wake phrase and personal acoustic model

Available in Norm, Pro, OEM and Admin. Lite keeps the normal assistant gesture but excludes the continuous microphone service and enrollment Activity.

## Setup

1. Open assistant settings → **Hi Omni · local voice wake**, or the same entry in Device access.
2. Select Omni as Android's default digital assistant. Grant microphone permission; allow notifications so the listening controls remain visible.
3. Agree to record and save a local profile. Record **Hi Omni** five times, with small natural changes in speed and distance. Pause after each phrase.
4. Record two different short phrases as negative examples, then a fresh **Hi Omni** recording for validation. The model is saved only after training and this held-out check pass.
5. Press **Start listening**. Say the phrase by itself, pause briefly, then use the panel. Optional **Listen for my request after waking** starts Android dictation after the panel appears.
6. Stop from settings or the notification. **Delete my voice profile** stops listening and removes both the saved model and its Keystore key.

For screen-off detection, enable the separate authenticated **Wake screen** permission in Device access. For a locked screen, also enable **Assistant on lock screen** and **Listen while the screen is locked**. The existing private lock-screen panel applies: no conversation history, attachments or automatic dictation. A wake match never arms or consumes a saved PIN permit.

## What is local and what is trained

The wake engine has no network calls, external SpeechRecognizer, model download or binary dependency. It uses 16 kHz mono PCM, 20 ms chunks, a 400 ms ambient-noise calibration, adaptive energy-based speech segmentation, 160 ms pre-roll and a 400 ms silence endpoint. Enrollment shows a speak prompt after calibration. Candidate buffers are capped at three seconds; long speech and short noise bursts are rejected. Raw audio stays in memory and is zeroed after feature extraction or cancellation.

The feature frontend applies pre-emphasis, 25 ms Hamming windows, a 512-point FFT, 26 mel filters and twelve MFCCs with delta features. Phrase templates remove cepstral means; the uncentered mean vector separately captures coarse voice timbre. Five positive examples and two negative examples train a bounded time-warp template classifier and a diagonal acoustic voice profile. Threshold calibration uses leave-one-out positive distances and a negative-example margin. A separate eighth recording checks the model before persistence. Recognition compares the best two templates, the nearest negative and, optionally, the voice profile. It never silently learns from background audio or an unverified match.

This is **experimental few-shot acoustic learning**, not neural-network fine-tuning, pretrained speech transcription, linguistic validation or a secure biometric. Enrollment labels are supplied by the user: any consistently recorded phrase could be learned, regardless of its words. A replay, similar voice, noise or different microphone may match or fail. Synthetic unit tests do not establish real speech accuracy, false-accept rates or battery performance. Validate on the intended devices and accents before a production release. A pretrained neural keyword/speaker model would be a separate integration with its own weights, licensing and device evaluation.

The model and timbre profile are encrypted with AES-GCM using a device-local AndroidKeyStore key, written atomically under credential-encrypted `noBackupFilesDir`, and have a versioned, bounded, finite-value-validated binary format. Wake preferences are excluded from cloud backup and device transfer. There is no agent tool for recording enrollment, reading/exporting profiles, enabling listening, changing permissions or deleting them. Enrollment is an unexported, unlocked, `FLAG_SECURE` Activity; the shared UI action/capture guard treats it as user-operated.

Optional command dictation uses the existing installed **Android speech provider**, which may use its network service. It is separately opt-in and disabled while locked. The chat response provider remains the user's existing local or remote model selection. Training the wake model does not fine-tune that chat model.

## Android lifecycle and constraints

- Listening starts from an explicit foreground settings action with `RECORD_AUDIO`, the microphone foreground-service type and a persistent notification with Stop. It is `START_NOT_STICKY`; there is no boot, worker or hidden restart path. After Android terminates it or the device restarts, start it again in settings.
- The selected `OmniVoiceInteractionService` invokes `showSession` to open the existing floating native assistant. If the native voice service is unavailable, a tap notification provides the supported fallback; the service does not force a background Activity or full-screen notification.
- Capture stops while the assistant is active, during phone/communication modes, during audio playback, when device permissions are revoked, and during an eight-second wake cooldown. AudioRecord is released before the panel or command recognizer starts. Lock-screen permission changes are checked during capture and immediately before invocation.
- A single coroutine mutex coordinates local recording and enrollment. Backgrounding enrollment cancels it. Each recording checks the device lock state. Errors stop listening and require the user to restart it.
- A renewable, timeout-bounded partial wake lock is held only during permitted capture and released on pause/stop. Continuous software detection costs battery; this is not a hardware DSP hotword implementation.
- Android microphone privacy controls, permission revocation, OEM battery restrictions, unsupported microphone formats and role changes can prevent listening. Default-assistant shutdown stops the detector. No attempt is made to bypass Android restrictions.

Official platform references: [selected VoiceInteractionService](https://developer.android.com/reference/android/service/voice/VoiceInteractionService), [microphone foreground-service requirements](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone), and [background/while-in-use restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

## Verification

Unit tests cover fresh/time-warped examples, different phrases, coarse voice preference, ambiguous enrollment, finite/gain-invariant features, malformed serialization, bounded segmentation, resets and the full lock-screen consent matrix. Android instrumentation tests cover encrypted save/reload/delete and tamper rejection. CI runs these through the existing flavor test jobs.

Device acceptance: enroll using a real voice; test wake at different distances and with TV/noise/another speaker; confirm the panel appears over another app; measure recognition latency and false accepts; check Screen off with each grant independently revoked; check private lock UI, replay behavior, microphone privacy switch, calls/playback, role removal, force-stop/reboot, enrollment cancellation, deletion and long-term battery use. Do not describe the voice profile as authentication or publish accuracy claims without measured results.
