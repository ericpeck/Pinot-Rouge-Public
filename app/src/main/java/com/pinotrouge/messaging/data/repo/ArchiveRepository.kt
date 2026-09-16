package com.pinotrouge.messaging.data.repo

import com.pinotrouge.messaging.data.room.ArchivedThreadDao
import com.pinotrouge.messaging.data.room.ArchivedThreadEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local archive filing. Telephony is never written or deleted here —
 * Archive must never be a synonym for delete.
 *
 * Shape mirrors [QuarantineRepository]: observe / archive / unarchive / isArchived.
 * Archived conversations are kept forever (no expiry column, no worker).
 */
@Singleton
class ArchiveRepository @Inject constructor(
    private val archivedThreadDao: ArchivedThreadDao,
) {
    fun observeArchived(): Flow<List<ArchivedThreadEntity>> =
        archivedThreadDao.observeAll()

    fun observeArchivedIds(): Flow<Set<Long>> =
        archivedThreadDao.observeThreadIds().map { it.toSet() }

    fun observeCount(): Flow<Int> = archivedThreadDao.observeCount()

    suspend fun archive(threadIds: Collection<Long>) = withContext(Dispatchers.IO) {
        if (threadIds.isEmpty()) return@withContext
        archivedThreadDao.upsertAll(
            threadIds.distinct().map { ArchivedThreadEntity(threadId = it) },
        )
    }

    suspend fun archive(threadId: Long) = archive(listOf(threadId))

    suspend fun unarchive(threadIds: Collection<Long>) = withContext(Dispatchers.IO) {
        if (threadIds.isEmpty()) return@withContext
        archivedThreadDao.deleteAll(threadIds.distinct())
    }

    suspend fun unarchive(threadId: Long) = unarchive(listOf(threadId))

    suspend fun isArchived(threadId: Long): Boolean = withContext(Dispatchers.IO) {
        archivedThreadDao.get(threadId) != null
    }

    suspend fun getArchivedIds(): Set<Long> = withContext(Dispatchers.IO) {
        archivedThreadDao.getThreadIds().toSet()
    }
}
