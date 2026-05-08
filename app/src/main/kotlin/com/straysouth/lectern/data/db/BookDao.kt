package com.straysouth.lectern.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: String): Book?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: Book)

    @Query("UPDATE books SET lastOpenedAt = :timestamp WHERE id = :id")
    suspend fun updateLastOpened(id: String, timestamp: Long)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: String)

    // COALESCE maps null lastOpenedAt to 0 so never-opened books sort last.
    @Query("SELECT * FROM books ORDER BY COALESCE(lastOpenedAt, 0) DESC")
    fun observeAllByLastOpened(): Flow<List<Book>>
}
