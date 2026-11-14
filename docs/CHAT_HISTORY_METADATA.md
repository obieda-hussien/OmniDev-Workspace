# Local chat history metadata

## Scope and current implementation

The history drawer now combines persisted topics, short title suggestions and multiple service tags with its existing search, pins, date grouping and actual-source filters. This is a deterministic extractive local baseline, **not a newly trained neural title model**. It adds no model download, cloud call, permanent service or permission. It does not depend on neural-core or enable experimental weights in the application.

New in-app sessions use automatic titles. Existing/imported names and externally supplied contact/conversation names stay authoritative; they can show a separate suggested title. A row's Rename action sets manual title ownership, and Category sets a manual topic or restores Automatic. Use local suggested title is an explicit rename. Automatic classification cannot change an entry's actual source, enable an integration, authorize a tool or manufacture completion evidence.

Topic IDs are uncategorized, development, device, research, omni_help, messaging, connected_apps, writing, personal and general. Service tags include WhatsApp, Telegram, Discord, connected apps, GitHub, MCP, OmniLink, Slack, Notion, Gmail and AndroidIDE. Arabic aliases and normalization help search and matching; the existing application UI remains English.

## Data flow and bounds

1. ChatRepository creates the session and initial metadata atomically through ChatSessionDao.
2. Saving a USER row triggers local metadata refresh. Assistant, tool and system rows do not classify a chat.
3. Refresh reads the first three and latest three USER rows, deduplicated and ordered by durable row ID. Each projection limits content to 1,500 SQLite characters. Original-input extraction reads metadata in pages of 2,048 SQLite characters, decodes only a bounded root userInput prefix and stops after 262,144 scanned UTF-16 units; the complete JSON envelope and long input value are never materialized. If that scan limit is exceeded, extraction abstains instead of deriving a title from quoted fallback context. HistoryMetadataText prefers the original composer userInput over expanded context when available and limits input to 1,500 UTF-16 units without splitting a surrogate pair.
4. ChatMetadataModel examines at most six inputs, selects the first substantive cleaned request, caps a title at 64 Unicode code points and assigns deterministic keyword/topic scores and stable service tags. It removes code fences, URLs and common secret/contact patterns from automatic titles. These patterns are not a universal privacy detector. This produces presentation metadata, not an operational checkpoint.
5. The DAO transaction rereads current ownership, preserves manual topics/names and provided titles, and retains the runtime-owned status suffix when updating an automatic title. Deletion cannot resurrect a session; the foreign key cascades its metadata.
6. Editing the latest user turn invalidates metadata in the same transaction as the edit. A crash after a USER insert is detected by the stored lastUserRowId. Opening history backfills pending sessions in cancellable batches of 20, with one invalidation boundary per batch. There is no transcript replay or background polling service.

The current small rules run inside bounded local transactions so concurrent refresh, rename, deletion and status updates serialize consistently. No network/tool operation runs inside a Room transaction. A future learned replacement with appreciable inference cost must compute outside the transaction and commit only against matching input/ownership revisions. A neural score must never override permission or evidence policies.

The drawer observes existing sessions plus their compact metadata map. Search matches stored and suggested titles, source labels, topic labels and service aliases. Source, topic, mentioned-app and query filters intersect. For example, an App conversation about WhatsApp matches Apps mentioned → WhatsApp while still failing Sources → WhatsApp bridge. A Telegram-originated development discussion can match both Sources → Telegram and Topics → Development.

## Persistence and migrations

Room version **20 → 21** adds only chat_history_metadata:

| Field | Purpose |
|---|---|
| sessionId | Primary key and cascading foreign key to chat_sessions |
| topic / topicOrigin | Stable topic ID and automatic/manual ownership |
| serviceTags | Bounded stable pipe-delimited service IDs |
| generatedTitle / titleOrigin | Suggested title and automatic/manual/provided ownership |
| modelVersion | Local derivation version; zero invalidates a row |
| lastUserRowId | Latest observed USER row for interrupted-update discovery |

MIGRATION_20_21 is registered in ALL_MIGRATIONS. Existing session/message tables, source identities, pins, chat payloads, task/team records and evidence are retained. Missing legacy metadata is created lazily with provided title ownership, preventing automatic replacement of previously chosen names. Source-provided name updates preserve both automatic and manual ownership, so background recovery cannot replace a redacted automatic title with a raw prefix. Imported records require no separate metadata import because derived metadata is rebuildable.

## Product knowledge and future training

README links to the reviewed bilingual product FAQ and its documentation-only JSONL export. The export is a preparation step for future retrieval/training, not a runtime support model, executed training job or claim of accuracy. It never reads application sources or private chats. Translations share a group ID, document hash and explicit feature/edition status. Future evaluation must split by group and test troubleshooting, stale versions, ambiguous permissions, unsupported features and abstention independently before enabling learned answers.

## Validation

Added real application tests:

- ChatMetadataModelTest: 13 JVM tests for titles, multilingual matching, source/tag separation, multiple services, common secret redaction, status spoofing, bounded inputs and Unicode truncation.
- HistoryMetadataTextTest: original composer preference, malformed legacy metadata and surrogate-safe limits. HistoryUserInputReaderTest exercises long/late fields, nested field rejection, escapes and scan limits.
- ChatHistoryMetadataRoomTest: repository save/edit flow, manual override persistence across database reopen, concurrent refresh/rename, deletion cascade, connected-source title ownership, assistant exclusion and preserved status suffixes. The migration test constructs each supported version 1–20 through actual migrations and opens version 21 with Room schema validation.
- ChatHistoryMetadataUiTest: topic and mentioned-app filters intersect with actual-source filters. This package is part of the existing API 30 regression selector.
- Product knowledge guard tests: deterministic bilingual export, grouped translations, rejected code/private/unlisted paths, invalid fields and rejected code fences. CI verifies the checked-in export before Android compilation.

Local JVM tests and Python guards can run without an Android SDK. The local Gradle attempt was blocked during wrapper download by network access; Kotlin, generated Room code and Compose instrumentation require CI validation. This document does not assert those pending checks passed. There has been no physical Infinix, peak-heap, latency or battery benchmark for this feature.

Reproduce:

```sh
python3 scripts/build_product_knowledge.py --verify
python3 scripts/test_product_knowledge.py
python3 scripts/check_project_language.py
python3 scripts/check_docs.py
bash gradlew :app:testLiteDebugUnitTest --tests '*ChatMetadataModelTest' --tests '*HistoryMetadataTextTest'
bash .github/scripts/run-ui-regressions.sh
```

The default labels are a baseline, with predictable keyword limitations. Manual correction is available. No neural training, universal secret detection, automatic support-answer generation or on-device speed claim is implied.
