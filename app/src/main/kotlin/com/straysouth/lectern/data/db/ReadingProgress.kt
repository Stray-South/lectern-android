package com.straysouth.lectern.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reading_progress")
data class ReadingProgress(
    @PrimaryKey val id: String,
    val bookId: String?,
    val locatorJson: String?,
    val totalProgression: Double?,
    val updatedAt: Long?,
)
