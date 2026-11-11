package com.omnidev.workspace.domain.teamstate

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The same fail-closed bounded envelope in Room and memory; no untrusted model JSON is decoded here. */
internal object TeamPayloadCodec {
    private const val MAX_CHARS = 1_500_000
    private val json = Json { encodeDefaults = true }
    fun encode(record: TeamRecord): String = json.encodeToString(record).also {
        check(it.length <= MAX_CHARS) { "Team checkpoint exceeds storage bound" }
    }
    fun decode(payload: String): TeamRecord {
        require(payload.length <= MAX_CHARS)
        return json.decodeFromString<TeamRecord>(payload)
    }
}
