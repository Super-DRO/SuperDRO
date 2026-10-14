package org.superdro.core.store.repository

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.Drawing
import org.superdro.core.model.DrawingLayer
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.ShapeType
import org.superdro.core.store.StoreFixture
import org.superdro.core.store.inMemoryDriver
import org.superdro.core.store.valueOrNull

/** The collectors of one drawing share one read, and a layer change does not read the shapes again. */
class DrawingSharingTest {

    private companion object {
        /** The select of the shapes of a drawing. */
        const val SHAPE_READ = "drawing_shape.bulges FROM drawing_shape WHERE drawing_id"
    }

    private val scope = TestScope()
    private val driver = CountingDriver(inMemoryDriver())
    private val store = StoreFixture(scope, driver)

    @AfterTest
    fun close() = store.close()

    private fun line(x: Long) = DrawingShape(type = ShapeType.Line, x1 = x, y1 = 0, x2 = x + 10, y2 = 0)

    @Test
    fun theCollectorsShareTheReadsAndALayerChangeKeepsTheShapes() = scope.runTest {
        val id = store.machines.createProfile(MachineProfile.create(name = "Mill")).valueOrNull()!!.activeWorkspaceId
        store.drawings.replaceDrawing(
            Drawing(workspaceId = id, layers = listOf(DrawingLayer(0, "0"), DrawingLayer(1, "Holes")), shapes = listOf(line(0), line(1), line(2))),
        )
        driver.clear()
        val first = mutableListOf<Drawing?>()
        val second = mutableListOf<Drawing?>()
        val jobs = listOf(first, second).map { seen ->
            launch(store.dispatcher) { store.drawings.drawing(id).collect { drawing -> seen += drawing } }
        }
        advanceUntilIdle()
        assertEquals(1, driver.count(SHAPE_READ), "One read for two collectors.")
        assertEquals(3, first.single()!!.shapes.size)

        // A layer change reads no shape.
        driver.clear()
        store.drawings.setLayerHidden(id, 1, hidden = true)
        advanceUntilIdle()
        assertEquals(0, driver.count(SHAPE_READ))
        assertEquals(true, first.last()!!.layers[1].hidden)
        assertSame(first[0]!!.shapes, first.last()!!.shapes, "The flow uses the same shape list.")
        assertEquals(first, second)

        // A shape delete reads the shapes again.
        driver.clear()
        assertTrue(store.drawings.deleteShape(id, 0))
        advanceUntilIdle()
        assertEquals(1, driver.count(SHAPE_READ))
        assertEquals(listOf(line(1), line(2)), first.last()!!.shapes)

        // New shapes with the same sequence numbers, as a workspace transform writes them.
        store.writer.write { db ->
            val drawingId = db.drawingQueries.selectForWorkspace(id).executeAsOne().id
            db.drawingQueries.deleteShapes(drawingId)
            db.insertShapes(drawingId, listOf(line(50), line(60)))
        }
        advanceUntilIdle()
        assertEquals(listOf(line(50), line(60)), first.last()!!.shapes)

        // A new drawing replaces the old one.
        store.drawings.replaceDrawing(Drawing(workspaceId = id, layers = listOf(DrawingLayer(0, "0")), shapes = listOf(line(7))))
        advanceUntilIdle()
        assertEquals(listOf(line(7)), first.last()!!.shapes)
        assertEquals(listOf(line(7)), store.drawings.loadDrawing(id)!!.shapes)

        store.drawings.deleteDrawing(id)
        advanceUntilIdle()
        assertNull(first.last())
        assertEquals(first, second)
        jobs.forEach { job -> job.cancel() }
    }
}
