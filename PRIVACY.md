# Data handling and privacy

This document describes the reviewed source behavior of OmniDev Workspace. It is not a substitute for the policies of a model provider, a connected service or a separately distributed application. A future store/distribution privacy notice may need additional deployment-specific details.

## Local application data

The app stores conversations, knowledge, selected execution/usage records, repository indexes, diagnostics, rollback records, scheduled tasks and connected-app memory in app-private storage, including the Room database. Preferences, learned recipes/checkpoints and media records/files use their corresponding local stores.

Local persistence enables history, retrieval and interruption recovery. It does not mean every record is encrypted. The Room database is not presented as an encrypted database. Provider API keys currently use a separate app-private Preferences DataStore without universal AndroidKeyStore wrapping.

A rooted or compromised device can undermine application-private storage. Exported logs, screenshots, shared media and backups have separate exposure paths. Review the installed build and Android backup configuration before relying on a particular backup behavior.

## Configured model providers

A remote request can send the submitted message, relevant saved history/knowledge, selected attachments, repository excerpts, loaded tool definitions and permitted tool observations to the configured provider. Team tasks can make additional planner/worker requests. Provider retention, training options, regional processing and billing are governed by that provider/account.

Custom OpenAI-compatible endpoints receive requests at the URL configured by the user. Local Edge routes inference through the eligible native local implementation when correctly installed/configured. Selecting local inference does not make all other enabled integration/tool traffic local.

## Voice and sensitive input

Wake enrollment and phrase matching are local acoustic processing. Raw recordings are transient; the saved acoustic profile is AndroidKeyStore-encrypted and backup-excluded. Enrollment/listening are explicitly user-operated; there is no agent profile-export/enrollment tool.

Downloaded Vosk recognition models are installed locally after explicit download and integrity validation. Command/credential recognition uses direct PCM and does not silently use a cloud recognizer. Android TTS output requires an installed offline voice for this local flow. Ordinary recognized requests can still reach a remote chat model after unlock if that model is selected.

Private PIN/password/pattern collection is separated from ordinary chat, observation and learning. Codes are not put into provider requests, chat transcripts, tool results, recipe inputs or clipboard. The Admin saved-PIN vault uses device-local AndroidKeyStore-backed encryption and explicit authenticated authorization. Temporary JVM/native/Android buffers cannot be promised perfectly erased in every layer; the contract avoids logging/persistence and keeps them transient.

## Learned tasks and video samples

Recipes retain user-reviewed names, triggers, selectors and lesson instructions. Captured editable values become parameters; parameter maps remain transient and are not saved in resumable checkpoints. Decisions and expected-result identities support safe interruption recovery.

Importing and sampling a teaching video is local. Choosing **Interpret samples with Omni** sends selected frames to the configured vision model and can incur model usage. Deleting a recipe removes its associated local checkpoint/video evidence. An unsaved live demonstration buffer is process-local.

## Media and attachments

Imported files are copied into designated app storage for stable preview/history references. Sending an attachment or generating media exposes the request/content to the selected provider as applicable. Generated files remain in the originating conversation and can optionally be saved to device media storage.

Saving/sharing creates additional copies or temporary grants outside the private conversation store. Public HTTPS download handling uses bounded transfers and separate credential rules. Local deletion cannot remove a file already shared with another app/person or retained by a provider.

## Connected applications and MCP

OmniLink exchanges bounded context, diagnostics/memory and authorized payloads with eligible peers. Each app keeps ownership of its own state. Connected material can enter model context as untrusted evidence; verified identity alone does not make every returned assertion correct.

An enabled MCP server receives the tool requests made to it and can return data that enters the selected model context. The server's retention and backend behavior belong to its operator. Review server permissions/credentials before enabling it.

## Messaging integrations

The Telegram listener accepts its paired private owner and stores processed conversations/update state locally. Incoming media currently passes metadata rather than fetched attachment contents. Bot/service requests reach Telegram under its own terms.

The local WhatsApp bridge retains own-account session/config/queue data in Termux. It binds to same-phone loopback and filters the own private chat. It uses a separately authored WhatsApp Web library and a linked-device session. Bridge private files are excluded from Git; they still need protection on the phone.

Other configured publishing/email/social services receive authorized requests and content for the selected operation. An integration is not automatically privacy-equivalent to local execution.

## Deletion, revocation and diagnostics

Delete local sessions, recipes, wake profiles or saved PIN authorization through the corresponding controls. Clearing app data/uninstalling removes installation-local state, while device media copies, bridge data in Termux and external provider/service records may remain.

Removing a saved API key prevents future use through that local store; revoke it at the provider to invalidate the credential itself. Cancelling a job may not cancel already accepted remote work. A conversation deletion does not retrospectively erase model requests already transmitted.

Before reporting a bug, redact provider keys, bot tokens, private messages, credentials, sensitive filenames and private project contents. Share a minimal reproduction instead of an entire database/export. Use [SECURITY.md](SECURITY.md) for suspected unauthorized exposure and [SUPPORT.md](SUPPORT.md) for ordinary diagnostics.
