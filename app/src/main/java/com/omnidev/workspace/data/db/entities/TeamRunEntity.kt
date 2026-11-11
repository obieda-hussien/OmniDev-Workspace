package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Indexed envelope plus bounded versioned runtime aggregate. No raw tool arguments or private thinking. */
@Entity(tableName = "team_runs", indices = [Index(value = ["sessionId", "updatedAt"])])
data class TeamRunEntity(@PrimaryKey val teamId: String, val sessionId: Long?, val updatedAt: Long,
    val payload: String)
