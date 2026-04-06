package com.speechpilot.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Query("SELECT * FROM session_records ORDER BY startedAtMs DESC")
    fun allSessions(): Flow<List<SessionRecord>>

    @Insert
    suspend fun insert(session: SessionRecord): Long

    @Query("DELETE FROM session_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM session_records")
    suspend fun clear()
}
