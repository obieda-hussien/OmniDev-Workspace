package com.omnidev.workspace.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.omnidev.workspace.data.db.entities.TeamRunEntity

@Dao
interface TeamStateDao {
    @Query("SELECT * FROM team_runs WHERE teamId = :id")
    suspend fun get(id: String): TeamRunEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(row: TeamRunEntity)
    @Query("SELECT * FROM team_runs WHERE sessionId = :sessionId ORDER BY updatedAt DESC, teamId LIMIT :limit")
    suspend fun forSession(sessionId: Long, limit: Int): List<TeamRunEntity>
}
