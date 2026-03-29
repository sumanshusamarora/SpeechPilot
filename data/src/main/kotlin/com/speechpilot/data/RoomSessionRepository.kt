package com.speechpilot.data

import kotlinx.coroutines.flow.Flow

/**
 * Room-backed implementation of [SessionRepository].
 *
 * All data is persisted locally. No network access.
 */
class RoomSessionRepository(private val dao: SessionDao) : SessionRepository {

    override fun allSessions(): Flow<List<SessionRecord>> = dao.allSessions()

    override suspend fun insert(session: SessionRecord): Long = dao.insert(session)

    override suspend fun deleteById(id: Long) = dao.deleteById(id)

    override suspend fun clear() = dao.clear()
}
