# Profile personalization and reference photos

## Set up your profile

Open Settings → Your profile. Nickname and background/custom preferences remain optional. Choose a base reply style (Default, Friendly, Professional, Direct or Detailed), then select Less/Default/More for warmth, enthusiasm, headings/lists and emoji. **Save changes** writes these text fields and style options in one DataStore transaction. Leaving with unsaved text changes asks whether to discard them.

**Personalize replies** controls name, background and style inclusion in new in-app Chat, Agent and Team requests, including their background recovery paths. Turning it off retains your editable values. It does not erase earlier conversation history or the separate knowledge/memory stores. Preferences are bounded background/style context, not permission to invoke a tool or change an authorization boundary. Provider compliance with tone guidance is not guaranteed.

## Add your likeness references

| Slot | Guidance | Actions |
| --- | --- | --- |
| Face photo | Clear, front-facing, recent photo in good lighting | Add, preview, replace, remove |
| Full-body photo | Head and feet inside the frame; clear lighting | Add, preview, replace, remove |

Photos save immediately, independently of the text-profile Save button. One or both slots can be used. The system file picker grants access only to the selected file. Imports accept up to 12 MB, check decoding/dimensions, sample to at most 1600 pixels on the longest side, apply EXIF orientation and write a JPEG at quality 90 capped at 4 MB. Re-encoding removes the original metadata. The app does not verify that a face/body is present or automatically score framing or identity.

Normalized photos and reference permission are stored privately under `noBackupFilesDir/profile-references`. A failed import preserves the previous reference. Index writes are atomic, and replacement/removal deletes the old normalized file. No new gallery scanning permission is required. This store is separate from conversation attachment storage.

## Generate an image of yourself

1. Add your own face and/or full-body photo.
2. Enable **Use for images of me**, which starts disabled and requires at least one photo.
3. Enable a supported image model in Model Selection and connect its provider account.
4. Ask for your likeness: “Create a professional portrait of me using my reference photos” or “Make a full-body image of me in a new outfit.”

The `media_generation` tool has an optional `use_profile_references` boolean, false by default. Its guidance limits selection to a requested likeness/reference task; unrelated generations keep their ordinary path. The request uses both available slots in face/body order and keeps the user's selected model/provider. It does not automatically insert these photos into ordinary chat/agent prompts or train a personal model.

| Request | Implemented reference transport |
| --- | --- |
| Gemini image model | JPEG `inlineData` parts alongside the prompt |
| OpenAI GPT Image | Multipart `image[]` inputs to `/v1/images/edits`; high input fidelity for GPT Image 1 and 1.5 |
| Image without reference flag | Existing text-to-image generation path, no profile read |
| Video, music, xAI or OpenRouter with reference flag | Explicit unsupported error; no provider fallback or silent removal of references |

Support follows the existing media model catalog and selected account's access. See the [Gemini image API](https://ai.google.dev/gemini-api/docs/generate-content/image-generation) and [OpenAI image editing API](https://developers.openai.com/api/reference/resources/images/methods/edit) for upstream transport contracts. Recognition and fidelity depend on the model; a reference is not a guaranteed exact likeness.

## Queued jobs, deletion and consent

Jobs persist selected reference filenames rather than duplicate photo bytes. The client rechecks current reference permission and exact current slot membership before building a provider request. Disabling use, deleting or replacing a selected photo makes unsent old jobs fail visibly. A newly uploaded replacement is never substituted into an existing job. Send a new request to use the new references. Re-enabling permission permits a still-current selection; removing all photos also disables permission.

Removing a photo or disabling reference use cannot recall a request already sent to a provider. Existing generated media remains governed by the normal chat media lifecycle. Reference files are excluded from Android backup; moving devices requires adding photos and granting reference permission again. General device/root access can undermine app-private files. See [Privacy](../PRIVACY.md).

## Source and verification

- `ProfilePersonalization`: stable-name preference encoding, bounded prompt construction and opt-out.
- `SettingsRepository`, `UserProfileScreen`: atomic text/style save, loading/error states and draft protection.
- `ProfileReferenceCard`, `ProfileReferenceStore`: picker, previews, normalized files, atomic index and reference consent.
- `ProfileReferencePolicy`, `MediaGenerationTool`, `MediaJobStore`, `MediaGenerationClient`: selected-provider validation, durable selection and real provider payloads.
- JVM regressions: codec compatibility, opt-out, prompt bounds, supported providers, consent/replacement and filename confinement.
- API 30 regressions in `ui.settings.ProfileReferencesTest`: import/restart/remove/replace, failed import preservation, Gemini input parts, OpenAI multipart editing and rejection before upload when references cannot be read.

Live-provider likeness, image costs and real-device picker/RTL acceptance require device/account testing; mock payload tests do not establish generation quality.
