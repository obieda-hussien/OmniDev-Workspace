# Source-backed chat history recall

OmniDev stores original conversations in its Room database. The Agent runtime can recall
excerpts when the user refers to older work, and the history tools can search or page through
the local archive. This feature runs on the phone; it does not need a desktop companion.

## Agent tools

- `list_chat_sessions(query?, offset?, limit?)`: find a conversation by title/date.
- `search_messages(query, sessionId?, limit?)`: ranked original USER/ASSISTANT messages,
  across saved sessions by default.
- `read_chat_session(sessionId, beforeId?, limit?)`: newest-first pages of source messages.
- `read_chat_message(sessionId, messageId, offset?)`: retrieve a long message in chunks.

Results use `[session:ID message:ID]` references. A request in a USER message establishes
what was requested, not what was completed. An ASSISTANT message is a historical claim; verify
current repository or tool state before reporting completion. When sources conflict, check
their timestamps and the original messages. When the archive has no evidence, say so.

Only bounded original excerpts are injected automatically for explicit old-work cues. Generated
summaries are not silently promoted to facts. Deleted sessions are excluded by the existing
foreign-key cascade. Connected-app and messaging sessions may search only their own conversation.

## Limits

The search uses bounded lexical candidates with Arabic spelling normalization and title matching;
it is not a complete semantic index of every message. Very broad queries may miss old matches:
try a distinctive term or list sessions and read one directly. No history can be recovered if
the local database was deleted or never contained it. Source grounding reduces unsupported
answers but cannot guarantee that a language model never makes an error.
