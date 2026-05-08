package com.straysouth.lectern.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingProgressDao {
    @Query("SELECT * FROM reading_progress")
    fun observeAll(): Flow<List<ReadingProgress>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: ReadingProgress)

    @Query("DELETE FROM reading_progress WHERE bookId = :bookId")
    suspend fun deleteByBookId(bookId: String)
}
