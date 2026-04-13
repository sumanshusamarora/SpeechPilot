package com.speechpilot.data

import kotlinx.coroutines.flow.Flow

interface SessionRepository {
    fun allSessions(): Flow<List<SessionRecord>>
    suspend fun insert(session: SessionRecord): Long
    suspend fun deleteById(id: Long)
    suspend fun clear()
}
