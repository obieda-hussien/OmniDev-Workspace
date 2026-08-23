package com.omnidev.workspace.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.omnidev.workspace.data.db.entities.ScheduledTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledTaskDao {
    @Query("SELECT * FROM scheduled_tasks")
    fun getAllTasks(): Flow<List<ScheduledTaskEntity>>

    @Query("SELECT * FROM scheduled_tasks WHERE status = :status")
    suspend fun getTasksByStatus(status: String): List<ScheduledTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: ScheduledTaskEntity)

    @Update
    suspend fun updateTask(task: ScheduledTaskEntity)

    @Query("DELETE FROM scheduled_tasks WHERE id = :taskId")
    suspend fun deleteTask(taskId: String)
}
