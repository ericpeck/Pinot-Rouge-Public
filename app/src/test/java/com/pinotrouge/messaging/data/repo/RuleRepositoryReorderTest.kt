package com.pinotrouge.messaging.data.repo

import com.pinotrouge.messaging.data.room.RuleDao
import com.pinotrouge.messaging.data.room.RuleEntity
import com.pinotrouge.messaging.data.room.RuleStatsDao
import com.pinotrouge.messaging.data.room.RuleStatsEntity
import com.pinotrouge.messaging.rules.Action
import com.pinotrouge.messaging.rules.Condition
import com.pinotrouge.messaging.rules.MatchMode
import com.pinotrouge.messaging.rules.Rule
import com.pinotrouge.messaging.rules.TextOp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistence path for reorder: one [RuleDao.upsertAll] call with dense orders.
 */
class RuleRepositoryReorderTest {

    private fun rule(id: String, order: Int) = Rule(
        id = id,
        name = id,
        order = order,
        match = MatchMode.ANY,
        conditions = listOf(Condition.Text(TextOp.CONTAINS_ANY, "x")),
        actions = setOf(Action.HOLD),
    )

    @Test
    fun reorder_writesDenseOrdersInOneBatch() = runBlocking {
        val dao = RecordingRuleDao()
        val repo = RuleRepository(dao, NoopRuleStatsDao())

        val dropped = listOf(
            rule("c", order = 2),
            rule("a", order = 0),
            rule("b", order = 1),
        )
        repo.reorder(dropped)

        assertEquals(1, dao.upsertAllCalls.size)
        val written = dao.upsertAllCalls.single()
        assertEquals(listOf("c", "a", "b"), written.map { it.id })
        assertEquals(listOf(0, 1, 2), written.map { it.order })
        assertTrue(written.map { it.order }.toSet().size == written.size)
    }

    @Test
    fun reorder_emptyList_writesEmptyBatch() = runBlocking {
        val dao = RecordingRuleDao()
        val repo = RuleRepository(dao, NoopRuleStatsDao())
        repo.reorder(emptyList())
        assertEquals(1, dao.upsertAllCalls.size)
        assertTrue(dao.upsertAllCalls.single().isEmpty())
    }

    /**
     * feat/rule-deletion: removing a middle rule must not scramble the rest.
     * Engine is first-match-wins ordered by `order` ASC — relative order of
     * survivors is what matters (holes are fine; densify happens on reorder).
     */
    @Test
    fun delete_fromMiddle_preservesRelativeOrderOfRemaining() = runBlocking {
        val dao = RecordingRuleDao()
        val stats = RecordingRuleStatsDao()
        val repo = RuleRepository(dao, stats)

        repo.save(rule("a", order = 0))
        repo.save(rule("b", order = 1))
        repo.save(rule("c", order = 2))
        stats.upsert(
            RuleStatsEntity(ruleId = "b", caughtCount = 3, lastCaughtAt = 1L),
        )

        repo.delete("b")

        val remaining = repo.getRules().sortedBy { it.order }
        assertEquals(listOf("a", "c"), remaining.map { it.id })
        assertTrue(remaining[0].order < remaining[1].order)
        // rule_stats is intentionally left behind (orphan proves "Deleted filter").
        assertEquals(1, stats.rows.size)
        assertEquals("b", stats.rows.single().ruleId)
    }

    private class RecordingRuleDao : RuleDao {
        val upsertAllCalls = mutableListOf<List<RuleEntity>>()
        private val rows = MutableStateFlow<List<RuleEntity>>(emptyList())

        override fun observeAll(): Flow<List<RuleEntity>> = rows
        override suspend fun getAll(): List<RuleEntity> = rows.value
        override suspend fun getById(id: String): RuleEntity? = rows.value.find { it.id == id }
        override suspend fun upsert(rule: RuleEntity) {
            rows.value = rows.value.filterNot { it.id == rule.id } + rule
        }
        override suspend fun upsertAll(rules: List<RuleEntity>) {
            upsertAllCalls += rules
            val byId = rules.associateBy { it.id }
            rows.value = (rows.value.filterNot { it.id in byId } + rules)
                .sortedBy { it.order }
        }
        override suspend fun deleteById(id: String) {
            rows.value = rows.value.filterNot { it.id == id }
        }
        override suspend fun deleteAll() {
            rows.value = emptyList()
        }
    }

    private class NoopRuleStatsDao : RuleStatsDao {
        override fun observeAll(): Flow<List<RuleStatsEntity>> = flowOf(emptyList())
        override suspend fun get(ruleId: String): RuleStatsEntity? = null
        override suspend fun upsert(stats: RuleStatsEntity) = Unit
        override suspend fun incrementCaught(ruleId: String, at: Long): Int = 0
        override suspend fun incrementSwept(ruleId: String): Int = 0
    }

    /** Records upserts so delete-leaves-stats is assertable. */
    private class RecordingRuleStatsDao : RuleStatsDao {
        val rows = mutableListOf<RuleStatsEntity>()
        override fun observeAll(): Flow<List<RuleStatsEntity>> = flowOf(rows.toList())
        override suspend fun get(ruleId: String): RuleStatsEntity? =
            rows.find { it.ruleId == ruleId }
        override suspend fun upsert(stats: RuleStatsEntity) {
            rows.removeAll { it.ruleId == stats.ruleId }
            rows += stats
        }
        override suspend fun incrementCaught(ruleId: String, at: Long): Int = 0
        override suspend fun incrementSwept(ruleId: String): Int = 0
    }
}
