package com.omnidev.workspace.data.repository

import androidx.room.withTransaction
import com.omnidev.workspace.data.db.OmniDevDatabase
import com.omnidev.workspace.data.db.entities.TeamRunEntity
import com.omnidev.workspace.domain.teamstate.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun roomTeamStateStore(db: OmniDevDatabase): TeamStateEngine = TeamStateEngine(RoomTeamStateBackend(db))

/** A short transaction provides the same serializable state transitions as the in-memory backend. */
internal class RoomTeamStateBackend(private val db: OmniDevDatabase) : TeamStateBackend {
    private val json = Json { encodeDefaults = true }
    override suspend fun read(id: String) = db.teamStateDao().get(id)?.let(::decode)
    override suspend fun change(id: String, transform: (TeamRecord?) -> TeamRecord): TeamRecord = db.withTransaction {
        val next = transform(db.teamStateDao().get(id)?.let(::decode))
        check(next.id == id)
        val payload = json.encodeToString(next)
        check(payload.length <= MAX_PAYLOAD_CHARS) { "Team checkpoint exceeds storage bound" }
        db.teamStateDao().save(TeamRunEntity(id, next.sessionId, next.updatedAt, payload))
        next
    }
    override suspend fun forSession(sessionId: Long, limit: Int) = db.teamStateDao().forSession(sessionId, limit.coerceIn(1, 20)).map(::decode)
    private fun decode(row: TeamRunEntity): TeamRecord {
        require(row.payload.length <= MAX_PAYLOAD_CHARS)
        return json.decodeFromString<TeamRecord>(row.payload).also {
            check(it.id == row.teamId && it.sessionId == row.sessionId) { "Team checkpoint envelope mismatch" }
        }
    }
    companion object { private const val MAX_PAYLOAD_CHARS = 1_500_000 }
}
