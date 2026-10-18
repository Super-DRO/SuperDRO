package org.superdro.core.store

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.SubDatum
import org.superdro.core.store.repository.MachineRepository

class StoreWriterTest {

    private val scope = TestScope()
    private val store = StoreFixture(scope)

    @AfterTest
    fun close() = store.close()

    @Test
    fun aBulkWriteIsOneTransaction() = scope.runTest {
        val workspaceId = store.machines.createProfile(MachineProfile.create(name = "Mill")).valueOrNull()!!.activeWorkspaceId
        assertFailsWith<IllegalStateException> {
            store.writer.write { db ->
                repeat(100) { index ->
                    db.subDatumQueries.upsert("p$index", workspaceId, "P$index", "", 1.0, null, null, null, null, null, null, store.clock.now(), store.clock.now())
                }
                error("The import fails after 100 rows.")
            }
        }
        assertEquals(0L, store.count("sub_datum"))
    }

    @Test
    fun theWritesOfManyCoroutinesGoThroughOneWriterThread() = runBlocking {
        // A real thread pool: 50 coroutines add points at the same time. The single writer gives
        // each point its own number and no transaction fails.
        val driver = inMemoryDriver()
        try {
            val writer = StoreWriter(createSuperDroDatabase(driver), readDispatcher = Dispatchers.IO)
            val machines = MachineRepository(writer, FakeStoreIOProvider())
            val workspaceId = machines.createProfile(MachineProfile.create(name = "Mill")).valueOrNull()!!.activeWorkspaceId
            (1..50).map { async(Dispatchers.IO) { machines.addSubDatum(workspaceId, SubDatum(x = 1.0)) } }.awaitAll()
            val names = machines.loadWorkspace(workspaceId)!!.subDatums.map { it.name }.toSet()
            assertEquals((1..50).map { "Point $it" }.toSet(), names)
            writer.awaitIdle()
        } finally {
            driver.close()
        }
    }

    @Test
    fun aCancelledReadOrWriteOnAClosedDriverThrowsCancellationException() = runBlocking {
        val driver = inMemoryDriver()
        val writer = StoreWriter(createSuperDroDatabase(driver))
        driver.close()
        val uncaught = mutableListOf<Throwable>()
        val scope = kotlinx.coroutines.CoroutineScope(
            Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, error -> uncaught += error },
        )
        val job = scope.async {
            kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
            writer.read { db -> db.machineProfileQueries.selectAll().executeAsList() }
        }
        job.join()
        writer.awaitIdle()
        assertEquals(emptyList(), uncaught)
    }
}
