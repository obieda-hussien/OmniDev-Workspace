package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey

/** Local-only model identity; never stores keys, prompts or tool arguments. */
@Entity(
    tableName = "auto_model_pins",
    primaryKeys = ["sessionId", "scope"],
    foreignKeys = [ForeignKey(entity = ChatSessionEntity::class, parentColumns = ["id"],
        childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)]
)
data class AutoModelPinEntity(val sessionId: Long, val scope: String, val modelId: String)
