package org.superdro.core.store.repository

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.store.StoreFixture

class McpCallRepositoryTest {

    private val scope = TestScope()
    private val store = StoreFixture(scope)

    @AfterTest
    fun close() = store.close()

    private fun row(atMs: Long, tool: String? = "list_tools", previous: String? = null) = McpCallRow(
        id = 0,
        atMs = atMs,
        client = "192.168.1.20",
        era = "Modern",
        tool = tool,
        method = if (tool == null) "resources/read" else "tools/call",
        argumentsJson = if (tool == null) null else """{"machine":"Mill"}""",
        result = "ok",
        durationMs = 12,
        previousJson = previous,
    )

    @Test
    fun anInsertGivesTheIdAndTheLastRowsComeNewestFirst() = scope.runTest {
        val first = store.mcpCalls.insert(row(1_000))
        val second = store.mcpCalls.insert(row(3_000, tool = null))
        val third = store.mcpCalls.insert(row(2_000, tool = "put_tools", previous = """{"id":"t1"}"""))
        assertEquals(listOf(1L, 2L, 3L), listOf(first, second, third))

        val last = store.mcpCalls.last(2).first()
        assertEquals(listOf(second, third), last.map { it.id })
        assertEquals(row(3_000, tool = null).copy(id = second), last[0])
        assertEquals(row(2_000, tool = "put_tools", previous = """{"id":"t1"}""").copy(id = third), last[1])
        assertEquals(3L, store.mcpCalls.count())
        assertEquals(3, store.mcpCalls.last(10).first().size)
    }

    @Test
    fun pruneDeletesTheRowsBeforeTheTimeAndTheFlowGivesTheChange() = scope.runTest {
        store.mcpCalls.insert(row(1_000))
        store.mcpCalls.insert(row(2_000))
        store.mcpCalls.insert(row(3_000))
        val seen = mutableListOf<List<Long>>()
        val job = launch(store.dispatcher) { store.mcpCalls.last(10).collect { rows -> seen += rows.map { it.atMs } } }
        advanceUntilIdle()
        store.mcpCalls.prune(olderThanMs = 2_000)
        advanceUntilIdle()
        job.cancel()
        assertEquals(listOf(listOf(3_000L, 2_000L, 1_000L), listOf(3_000L, 2_000L)), seen)
        // The ID of a new row never repeats an ID of a deleted row.
        assertEquals(4L, store.mcpCalls.insert(row(4_000)))
    }
}
