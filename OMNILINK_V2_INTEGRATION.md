# OmniDev Workspace + OmniLinkSDK 2.0

This document describes the Workspace side of the OmniLink 2.0 integration.

AndroidIDE is intentionally **not modified by this change**. Workspace is being prepared first so a
future AndroidIDE integration can connect to a stable, verified contract.

## Trust boundaries

### Trusted Omni applications

Privileged Android extension discovery is no longer based on an intent action alone.

Workspace uses `TrustedServiceResolver` and accepts a provider only when:

- the service is exported;
- it declares exactly `com.omnilink.sdk.permission.BIND_EXTENSION`;
- its final installed APK has the same Android signing identity as Workspace;
- discovery is converted to an explicit component before binding.

The privileged permissions are declared by OmniDev Workspace, the core host, rather than by the
OmniLink AAR. Importing a library cannot make an application the permission owner.

### Unknown third-party applications

Unknown applications use `omni-link-public`.

`PublicOmniGatewayActivity` accepts only bounded Ask/Share/Open requests. It:

- enforces the public 32 KiB request limit;
- rate-limits ingress;
- persists the request as an ordinary external chat message;
- labels source context as UNTRUSTED;
- opens a user-visible Workspace chat;
- never starts Agent or Team mode automatically.

Public inbound access and Omni's outbound ability to operate another application are independent
policies.

## Shared memory

Workspace owns a local `shared_memory_records` ledger. AndroidIDE will eventually exchange deltas;
it will not open or share Workspace's Room database.

Each record has:

- stable `recordId`;
- namespace and kind;
- JSON content and metadata;
- authenticated source package;
- monotonically comparable revision;
- update timestamp;
- tombstone flag;
- SHA-256 checksum.

Merge rule:

1. a higher revision wins;
2. for equal revision, the later update timestamp wins;
3. stale updates are rejected.

The ledger is capped to prevent unbounded database growth.

Current namespaces prepared for IDE integration include:

- `ide_context`
- `ide_diagnostics`
- `ide_payload`

## Agent memory integration

Connected-app context participates in `search_knowledge` and selected recent IDE context/diagnostics
can be injected into agent context.

It is always labelled:

```text
CONNECTED APP CONTEXT (UNTRUSTED DATA)
```

Content from an IDE, build log, file or diagnostic is evidence/data. Embedded text is never promoted
to system instructions or automatic privileged actions.

## Large same-device payloads

`workspace.payload.ingest` accepts a small OmniLink `PayloadDescriptor`.

The payload bytes move through `AndroidPayloadBroker` using a Content URI/file-descriptor data
plane. Workspace:

- streams incrementally;
- bounds total size;
- verifies declared length;
- verifies SHA-256 when supplied;
- writes to a temporary file first;
- commits only after validation;
- stores only payload metadata/reference in shared memory.

This avoids pushing images, videos, build archives or long logs through a single Binder transaction.

The future AndroidIDE caller must grant the URI read permission to Workspace for the duration of the
handoff.

## Large network/desktop payloads

OmniLink 2.0 transport provides resumable encrypted `_transfer.*` capabilities. The protocol uses
bounded chunks, chunk hashes, final SHA-256, durable partial files and resume offsets.

Workspace-side desktop transport wiring can reuse the same transfer protocol without changing the
shared-memory record model.

## AndroidIDE future contract

The planned direction is:

```text
AndroidIDE -> Workspace
  workspace.context.publish
  workspace.diagnostics.publish
  workspace.memory.upsert
  workspace.payload.ingest

AndroidIDE <- Workspace
  workspace.memory.search
  workspace.memory.delta
  workspace.memory.recent

Workspace -> AndroidIDE
  verified AndroidIDE extension capabilities
  project/build/file operations explicitly declared by AndroidIDE
```

The last direction will be enabled only after AndroidIDE itself implements its v2 provider. Workspace
is already prepared to reject a fake provider with the same intent action.

## Reliability rules

- no shared Room database file across processes;
- stable IDs and idempotent updates;
- bounded inline data;
- streaming for large payloads;
- explicit provider identity verification;
- Binder death invalidates and rebinds the connection;
- transport reconnect/resume preserves transfer progress;
- remote content never becomes trusted instructions merely because the peer is first-party.
