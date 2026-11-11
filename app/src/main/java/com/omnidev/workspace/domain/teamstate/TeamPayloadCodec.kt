package com.omnidev.workspace.domain.teamstate

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The same fail-closed bounded envelope in Room and memory; no untrusted model JSON is decoded here. */
internal object TeamPayloadCodec {
    private const val MAX_BYTES = 1_500_000
    private val json = Json { encodeDefaults = true }
    fun encode(record: TeamRecord): String = json.encodeToString(record).also {
        check(it.length <= MAX_BYTES && it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Team checkpoint exceeds storage bound" }
    }
    fun decode(payload: String): TeamRecord {
        require(payload.length <= MAX_BYTES && payload.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        return json.decodeFromString<TeamRecord>(payload)
    }
}
