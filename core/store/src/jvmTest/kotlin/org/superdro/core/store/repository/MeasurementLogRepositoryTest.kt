package org.superdro.core.store.repository

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds
import org.superdro.core.model.CollectedPoint
import org.superdro.core.model.LoggedResult
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MeasurementLogEntry
import org.superdro.core.store.StoreFixture
import org.superdro.core.store.valueOrNull

class MeasurementLogRepositoryTest {

    private val scope = TestScope()
    private val store = StoreFixture(scope)

    @AfterTest
    fun close() = store.close()

    @Test
    fun theSequenceStartsAt1InEachWorkspace() = scope.runTest {
        val profile = store.machines.createProfile(MachineProfile.create(name = "Mill")).valueOrNull()!!
        val first = profile.activeWorkspaceId
        val second = store.machines.createWorkspace(profile.id, "B").valueOrNull()!!.id
        // A circle from 3 points: center (20000, 15000) µm, Ø 50.000 mm.
        val circle = MeasurementLogEntry(
            verb = "circle_from_3_points",
            title = "Circle from 3 points",
            results = listOf(LoggedResult("center", x = 20000.0, y = 15000.0), LoggedResult("diameter", value = 50000.0)),
            points = listOf(CollectedPoint(0.0, 0.0), CollectedPoint(40000.0, 0.0), CollectedPoint(0.0, 30000.0)),
            notes = listOf("UNCOMPENSATED"),
        )

        assertEquals(1, store.logs.append(first, circle).valueOrNull()!!.sequence)
        store.clock.advance(1.seconds)
        assertEquals(2, store.logs.append(first, circle.copy(id = "second")).valueOrNull()!!.sequence)
        assertEquals(1, store.logs.append(second, circle.copy(id = "other")).valueOrNull()!!.sequence)

        val entries = store.logs.entries(first).first()
        assertEquals(listOf(1, 2), entries.map { it.sequence })
        assertEquals(circle.copy(workspaceId = first, sequence = 1, createdOn = store.clock.now() - 1.seconds), entries.first())
        assertEquals(2L, store.logs.count(first).first())

        store.logs.clear(first)
        assertEquals(1, store.logs.append(first, circle.copy(id = "third")).valueOrNull()!!.sequence)
    }

    @Test
    fun aRowThatDoesNotDecodeIsSkipped() = scope.runTest {
        val workspace = store.machines.createProfile(MachineProfile.create(name = "Mill")).valueOrNull()!!.activeWorkspaceId
        store.logs.append(workspace, MeasurementLogEntry(verb = "point"))
        store.driver.execute(null, "INSERT INTO measurement_log VALUES ('bad', '$workspace', 2, 'point', '{not json', '2026-10-12T08:00:00Z')", 0)
        assertEquals(listOf(1), store.logs.entries(workspace).first().map { it.sequence })
        assertEquals(3, store.logs.append(workspace, MeasurementLogEntry(verb = "point")).valueOrNull()!!.sequence)
    }
}
