package com.omnidev.workspace.data.ipc

import android.util.Log
import com.omnilink.sdk.AccessController
import com.omnilink.sdk.AndroidPayloadBroker
import com.omnilink.sdk.AccessDecision
import com.omnilink.sdk.ActionError
import com.omnilink.sdk.ActionOutcome
import com.omnilink.sdk.ActionRequest
import com.omnilink.sdk.AuditLogger
import com.omnilink.sdk.CapabilityDescriptor
import com.omnilink.sdk.CapabilityExecutionMode
import com.omnilink.sdk.CapabilityRisk
import com.omnilink.sdk.CallerContext
import com.omnilink.sdk.CommunicationDirection
import com.omnilink.sdk.DataScope
import com.omnilink.sdk.ExtensionService
import com.omnilink.sdk.IdempotencySemantics
import com.omnilink.sdk.OmniJson
import com.omnilink.sdk.OmniLinkConstants
import com.omnilink.sdk.PayloadDescriptor
import com.omnilink.sdk.TrustTier
import com.omnidev.workspace.data.db.OmniDevDatabase
import com.omnidev.workspace.data.db.entities.SharedMemoryMergePolicy
import com.omnidev.workspace.data.db.entities.SharedMemoryRecordEntity
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Workspace-side OmniLink v3 bridge.
 *
 * AndroidIDE is intentionally untouched in this phase. This service defines the stable same-signer
 * contract AndroidIDE can consume later for shared memory/context/diagnostics without sharing Room
 * database files between processes.
 */
class WorkspaceLinkService : ExtensionService() {

    companion object {
        private const val TAG = "WorkspaceLinkService"
        private const val MAX_RECORD_BYTES = 12 * 1024
        private const val MAX_QUERY_LIMIT = 8
        private const val MAX_DELTA_LIMIT = 8
        private const val MAX_LEDGER_RECORDS = 10_000

        private val SAFE_RECORD_ID = Regex("[A-Za-z0-9._:@/-]{1,160}")
        private val SAFE_NAMESPACE = Regex("[A-Za-z0-9._:-]{1,96}")
        private val SAFE_KIND = Regex("[A-Za-z0-9._:-]{1,96}")
    }

    override val minSupportedVersion: Int = 4
    override val maxSupportedVersion: Int = OmniLinkConstants.CURRENT_PROTOCOL_VERSION
    override val maxInlineRequestBytes: Int = MAX_RECORD_BYTES

    override val accessController: AccessController = object : AccessController {
        override fun decide(caller: CallerContext, request: ActionRequest): AccessDecision =
            if (caller.sameSignerAsHost) AccessDecision.ALLOW else AccessDecision.DENY
    }

    override val auditLogger: AuditLogger = object : AuditLogger {
        override fun log(caller: CallerContext, request: ActionRequest, result: ActionOutcome) {
            Log.i(
                TAG,
                "caller=" + caller.callingPackage +
                    " capability=" + request.name +
                    " result=" + result::class.java.simpleName
            )
        }
    }

    override val capabilities: List<CapabilityDescriptor> = listOf(
        memoryCapability("workspace.memory.upsert", "Upsert one versioned shared-memory record"),
        memoryCapability("workspace.memory.search", "Search Workspace shared-memory records"),
        memoryCapability("workspace.memory.delta", "Read memory deltas since a timestamp"),
        memoryCapability("workspace.memory.recent", "Read recent shared-memory records"),
        memoryCapability("workspace.context.publish", "Publish IDE/project context into shared memory"),
        memoryCapability("workspace.diagnostics.publish", "Publish IDE/build diagnostics into shared memory"),
        payloadCapability("workspace.payload.ingest", "Stream a large same-device payload into Workspace")
    )

    private val database by lazy { OmniDevDatabase.getInstance(applicationContext) }
    private val dao by lazy { database.sharedMemoryDao() }

    override suspend fun onAction(
        caller: CallerContext,
        request: ActionRequest
    ): ActionOutcome = when (request.name) {
        "workspace.memory.upsert" -> upsert(caller, request)
        "workspace.context.publish" -> publishTyped(caller, request, "ide_context")
        "workspace.diagnostics.publish" -> publishTyped(caller, request, "ide_diagnostics")
        "workspace.memory.search" -> search(request)
        "workspace.memory.delta" -> delta(request)
        "workspace.memory.recent" -> recent(request)
        "workspace.payload.ingest" -> ingestPayload(caller, request)
        else -> ActionOutcome.Failure(
            ActionError("unknown_capability", "Unknown Workspace capability: " + request.name)
        )
    }

    private suspend fun upsert(
        caller: CallerContext,
        request: ActionRequest
    ): ActionOutcome {
        val payload = request.payload.jsonObject
        val clientRecordId = payload.string("recordId")?.trim().orEmpty()
        if (!SAFE_RECORD_ID.matches(clientRecordId)) return failure("invalid_record_id")
        // Namespace every key using the authenticated Binder caller; never trust client provenance.
        val recordId = caller.callingPackage + ":" +
            clientRecordId.removePrefix(caller.callingPackage + ":")
        val namespace = payload.string("namespace")?.trim().orEmpty()
        val kind = payload.string("kind")?.trim().orEmpty()
        val content = payload["content"] ?: JsonObject(emptyMap())
        val metadata = payload["metadata"] ?: JsonObject(emptyMap())
        val revision = payload.long("revision") ?: 1L
        val updatedAt = payload.long("updatedAt") ?: System.currentTimeMillis()
        val tombstone = payload.boolean("tombstone") ?: false

        if (!SAFE_RECORD_ID.matches(recordId)) return failure("invalid_record_id")
        if (!SAFE_NAMESPACE.matches(namespace)) return failure("invalid_namespace")
        if (!SAFE_KIND.matches(kind)) return failure("invalid_kind")
        if (revision < 0L || updatedAt < 0L ||
            updatedAt > System.currentTimeMillis() + 300_000L
        ) return failure("invalid_revision")

        val contentJson = content.toString()
        val metadataJson = metadata.toString()
        val recordBytes = contentJson.toByteArray().size + metadataJson.toByteArray().size
        if (recordBytes > MAX_RECORD_BYTES) return failure("record_too_large")

        val current = dao.findById(recordId)
        val accepted = SharedMemoryMergePolicy.shouldAccept(
            currentRevision = current?.revision,
            currentUpdatedAt = current?.updatedAt,
            incomingRevision = revision,
            incomingUpdatedAt = updatedAt
        )
        if (!accepted) {
            return ActionOutcome.Success(
                buildJsonObject {
                    put("accepted", false)
                    put("recordId", recordId)
                    put("reason", "stale_revision")
                }
            )
        }

        val checksumInput = listOf(
            recordId,
            namespace,
            kind,
            contentJson,
            metadataJson,
            revision.toString(),
            updatedAt.toString(),
            tombstone.toString()
        ).joinToString("\u0000")
        val checksum = sha256(checksumInput)

        dao.upsert(
            SharedMemoryRecordEntity(
                recordId = recordId,
                namespace = namespace,
                kind = kind,
                contentJson = contentJson,
                metadataJson = metadataJson,
                sourcePackage = caller.callingPackage,
                revision = revision,
                updatedAt = updatedAt,
                tombstone = tombstone,
                checksumSha256 = checksum
            )
        )
        enforceLedgerQuota()

        return ActionOutcome.Success(
            buildJsonObject {
                put("accepted", true)
                put("recordId", recordId)
                put("checksumSha256", checksum)
            }
        )
    }

    private suspend fun publishTyped(
        caller: CallerContext,
        request: ActionRequest,
        namespace: String
    ): ActionOutcome {
        val payload = request.payload.jsonObject
        val fallbackId = caller.callingPackage + ":" + namespace + ":" +
            (request.idempotencyKey ?: request.requestId ?: System.nanoTime().toString())
        val recordId = (payload.string("recordId") ?: fallbackId)
            .take(160 - caller.callingPackage.length - 1)

        val normalized = buildJsonObject {
            put("recordId", JsonPrimitive(recordId))
            put("namespace", JsonPrimitive(namespace))
            put("kind", JsonPrimitive(payload.string("kind") ?: namespace))
            put("content", payload["content"] ?: payload)
            put("metadata", payload["metadata"] ?: JsonObject(emptyMap()))
            put("revision", JsonPrimitive(payload.long("revision") ?: 1L))
            put("updatedAt", JsonPrimitive(payload.long("updatedAt") ?: System.currentTimeMillis()))
            put("tombstone", JsonPrimitive(false))
        }
        return upsert(caller, request.copy(payload = normalized))
    }

    private suspend fun ingestPayload(
        caller: CallerContext,
        request: ActionRequest
    ): ActionOutcome {
        val payload = request.payload.jsonObject
        val descriptorElement = payload["descriptor"]
            ?: return failure("missing_payload_descriptor")
        val descriptor = runCatching {
            OmniJson.instance.decodeFromString<PayloadDescriptor>(descriptorElement.toString())
        }.getOrElse {
            return ActionOutcome.Failure(
                ActionError("invalid_payload_descriptor", it.message ?: "Invalid payload descriptor")
            )
        }

        val recordId = (payload.string("recordId")
            ?: "payload:" + caller.callingPackage + ":" +
                (request.idempotencyKey ?: request.requestId ?: System.nanoTime().toString()))
            .take(160)
        if (!SAFE_RECORD_ID.matches(recordId)) return failure("invalid_record_id")

        val safeName = descriptor.payloadId
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(96)
            .ifBlank { "payload" }
        val destinationDir = java.io.File(filesDir, "omnilink-payloads")
        val destination = java.io.File(destinationDir, safeName + ".bin")

        val copied = runCatching {
            AndroidPayloadBroker.copyContentUri(
                context = applicationContext,
                descriptor = descriptor,
                destination = destination
            )
        }.getOrElse { error ->
            return ActionOutcome.Failure(
                ActionError("payload_copy_failed", error.message ?: "Payload streaming failed", retryable = true)
            )
        }

        val memoryPayload = buildJsonObject {
            put("recordId", recordId)
            put("namespace", "ide_payload")
            put("kind", payload.string("kind") ?: "payload")
            put(
                "content",
                buildJsonObject {
                    put("localPath", destination.absolutePath)
                    put("payloadId", descriptor.payloadId)
                    put("lengthBytes", copied)
                    put("mimeType", descriptor.mimeType ?: "application/octet-stream")
                    descriptor.sha256?.let { put("sha256", it) }
                }
            )
            put("metadata", payload["metadata"] ?: JsonObject(emptyMap()))
            put("revision", payload.long("revision") ?: 1L)
            put("updatedAt", payload.long("updatedAt") ?: System.currentTimeMillis())
            put("tombstone", false)
        }
        return upsert(caller, request.copy(payload = memoryPayload))
    }

    private suspend fun search(request: ActionRequest): ActionOutcome {
        val payload = request.payload.jsonObject
        val query = payload.string("query")?.take(1000).orEmpty()
        val namespace = payload.string("namespace")?.take(96).orEmpty()
        val limit = (payload.long("limit") ?: 8L).coerceIn(1L, MAX_QUERY_LIMIT.toLong()).toInt()
        return recordsOutcome(dao.search(query, namespace, limit))
    }

    private suspend fun delta(request: ActionRequest): ActionOutcome {
        val payload = request.payload.jsonObject
        val since = payload.long("sinceEpochMs") ?: 0L
        val limit = (payload.long("limit") ?: 8L).coerceIn(1L, MAX_DELTA_LIMIT.toLong()).toInt()
        return recordsOutcome(dao.changedSince(since.coerceAtLeast(0L), limit))
    }

    private suspend fun recent(request: ActionRequest): ActionOutcome {
        val payload = request.payload.jsonObject
        val namespace = payload.string("namespace")?.take(96).orEmpty()
        val limit = (payload.long("limit") ?: 8L).coerceIn(1L, MAX_QUERY_LIMIT.toLong()).toInt()
        return recordsOutcome(dao.recent(namespace, limit))
    }

    private fun recordsOutcome(records: List<SharedMemoryRecordEntity>): ActionOutcome =
        ActionOutcome.Success(
            buildJsonObject {
                put(
                    "records",
                    buildJsonArray {
                        records.forEach { record ->
                            add(
                                buildJsonObject {
                                    put("recordId", record.recordId)
                                    put("namespace", record.namespace)
                                    put("kind", record.kind)
                                    put("contentJson", record.contentJson)
                                    put("metadataJson", record.metadataJson)
                                    put("sourcePackage", record.sourcePackage)
                                    put("revision", record.revision)
                                    put("updatedAt", record.updatedAt)
                                    put("tombstone", record.tombstone)
                                    put("checksumSha256", record.checksumSha256)
                                }
                            )
                        }
                    }
                )
            }
        )

    private suspend fun enforceLedgerQuota() {
        val count = dao.count()
        if (count > MAX_LEDGER_RECORDS) {
            dao.evictOldest((count - MAX_LEDGER_RECORDS).coerceAtLeast(100))
        }
    }

    private fun payloadCapability(name: String, description: String): CapabilityDescriptor =
        CapabilityDescriptor(
            name = name,
            description = description,
            executionMode = CapabilityExecutionMode.ASYNC,
            supportsStreaming = true,
            requiredTrustTier = TrustTier.FIRST_PARTY,
            communicationDirection = CommunicationDirection.BIDIRECTIONAL,
            risk = CapabilityRisk.MEDIUM,
            idempotency = IdempotencySemantics.IDEMPOTENT_WITH_KEY,
            supportsDryRun = false,
            timeoutMillis = 10 * 60 * 1000L,
            maxInlinePayloadBytes = 64 * 1024,
            dataScopes = setOf(DataScope.OMNI_ECOSYSTEM)
        )

    private fun memoryCapability(name: String, description: String): CapabilityDescriptor =
        CapabilityDescriptor(
            name = name,
            description = description,
            executionMode = CapabilityExecutionMode.ASYNC,
            supportsStreaming = false,
            requiredTrustTier = TrustTier.FIRST_PARTY,
            communicationDirection = CommunicationDirection.BIDIRECTIONAL,
            risk = CapabilityRisk.LOW,
            idempotency = IdempotencySemantics.IDEMPOTENT_WITH_KEY,
            supportsDryRun = false,
            timeoutMillis = 30_000,
            maxInlinePayloadBytes = MAX_RECORD_BYTES,
            dataScopes = setOf(DataScope.OMNI_ECOSYSTEM)
        )

    private fun failure(code: String): ActionOutcome.Failure =
        ActionOutcome.Failure(ActionError(code, code.replace('_', ' ')))

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun JsonObject.string(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.long(name: String): Long? =
        this[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

    private fun JsonObject.boolean(name: String): Boolean? =
        this[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
}
