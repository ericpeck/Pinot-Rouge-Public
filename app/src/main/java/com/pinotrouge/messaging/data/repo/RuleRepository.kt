package com.pinotrouge.messaging.data.repo

import com.pinotrouge.messaging.data.room.RuleDao
import com.pinotrouge.messaging.data.room.RuleStatsDao
import com.pinotrouge.messaging.data.room.RuleStatsEntity
import com.pinotrouge.messaging.rules.Rule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renumber [rules] densely to 0, 1, 2, … in list order.
 * Exposed for tests of the reorder renumbering path.
 */
internal fun withDenseOrder(rules: List<Rule>): List<Rule> =
    rules.mapIndexed { index, rule ->
        if (rule.order == index) rule else rule.copy(order = index)
    }

@Singleton
class RuleRepository @Inject constructor(
    private val ruleDao: RuleDao,
    private val ruleStatsDao: RuleStatsDao,
) {
    fun observeRules(): Flow<List<Rule>> =
        ruleDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeStats(): Flow<List<RuleStatsEntity>> = ruleStatsDao.observeAll()

    suspend fun getRules(): List<Rule> = withContext(Dispatchers.IO) {
        ruleDao.getAll().map { it.toDomain() }
    }

    suspend fun getRule(id: String): Rule? = withContext(Dispatchers.IO) {
        ruleDao.getById(id)?.toDomain()
    }

    suspend fun save(rule: Rule) = withContext(Dispatchers.IO) {
        ruleDao.upsert(rule.toEntity())
    }

    suspend fun saveAll(rules: List<Rule>) = withContext(Dispatchers.IO) {
        ruleDao.upsertAll(rules.map { it.toEntity() })
    }

    /**
     * Persist a full list order in one write: renumber densely to 0, 1, 2, …
     * and upsert all rows together so a crash cannot leave duplicate positions.
     *
     * [rules] must already be in the desired top-to-bottom evaluation order.
     */
    suspend fun reorder(rules: List<Rule>) = withContext(Dispatchers.IO) {
        ruleDao.upsertAll(withDenseOrder(rules).map { it.toEntity() })
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        ruleDao.deleteById(id)
    }

    suspend fun recordCatch(ruleId: String, atMillis: Long) = withContext(Dispatchers.IO) {
        ruleStatsDao.recordCatch(ruleId, atMillis)
    }

    /**
     * Inbox sweep moved a message into Filtered under [ruleId].
     * Does not write [RuleStatsEntity.lastCaughtAt] — that is arrival-only.
     */
    suspend fun recordSweep(ruleId: String) = withContext(Dispatchers.IO) {
        ruleStatsDao.recordSweep(ruleId)
    }

    suspend fun getStats(ruleId: String): RuleStatsEntity? = withContext(Dispatchers.IO) {
        ruleStatsDao.get(ruleId)
    }
}
