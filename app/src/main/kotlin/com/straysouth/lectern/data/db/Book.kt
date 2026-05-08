package com.straysouth.lectern.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class Book(
    @PrimaryKey val id: String,
    val title: String?,
    val filePath: String?,
    val coverPath: String?,
    val addedAt: Long?,
    val lastOpenedAt: Long?,
    val format: String,          // "EPUB" | "PDF" — set at import, never null
)
