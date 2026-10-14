package org.superdro.core.store.repository

import app.cash.sqldelight.Query
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.superdro.core.model.Drawing
import org.superdro.core.model.DrawingLayer
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.DrawingSource
import org.superdro.core.store.StoreRefusal
import org.superdro.core.store.StoreResult
import org.superdro.core.store.StoreWriter
import org.superdro.core.store.db.SuperDroDatabase
import org.superdro.core.store.db.Drawing as DrawingRow

/**
 * The drawing of each workspace. A workspace has 0 or 1 drawing.
 * The coordinates are integer nm in the model space of the sub-datums. A workspace delete
 * removes its drawing, and a workspace transform changes it ([MachineRepository.applyTransform]).
 *
 * A drawing write is one transaction: a drawing of 10 000 shapes
 * goes in or stays out in full. A drawing read is one transaction, so the shapes come from one
 * state of the database.
 */
class DrawingRepository(
    private val writer: StoreWriter,
) {
    private val database get() = writer.database

    /** The shared flow of the drawing of each workspace, by workspace ID. */
    private val sharedDrawings = SharedFlows<Drawing?>(CoroutineScope(SupervisorJob() + writer.readDispatcher))

    /**
     * The drawing of the workspace with its layers and shapes, or null.
     *
     * - All collectors of one workspace share one read ([SharedFlows]).
     * - The flow keeps the decoded shapes. A change of the layers only, for example "hide a
     *   layer", reads the drawing row and the layers again, and uses the same shape list. A change
     *   of the shape table or the drawing table reads the shapes again.
     * - Each read is one transaction on the writer dispatcher (see [StoreWriter.read]), so the
     *   layers and the shapes come from one state of the database.
     */
    fun drawing(workspaceId: String): Flow<Drawing?> = sharedDrawings.get(workspaceId) {
        val drawingQuery = database.drawingQueries.selectForWorkspace(workspaceId)
        val shapeQuery = database.drawingQueries.selectShapeSeqsForWorkspace(workspaceId)
        flow {
            val cache = ShapeCache()
            // The listener runs at the commit of each write to the tables of the shapes. The
            // writes and the reads run on the one writer thread, thus a read after a write always
            // sees the flag of that write.
            val listener = Query.Listener { cache.dirty = true }
            drawingQuery.addListener(listener)
            shapeQuery.addListener(listener)
            try {
                emitAll(
                    writer.observe(drawingQuery, database.drawingQueries.selectLayersForWorkspace(workspaceId), shapeQuery) { db ->
                        db.fullDrawing(workspaceId, cache)
                    },
                )
            } finally {
                drawingQuery.removeListener(listener)
                shapeQuery.removeListener(listener)
            }
        }
    }

    /** The decoded shapes of the last read of one drawing flow. */
    private class ShapeCache {
        /** True when the shape table or the drawing table changed after the last read. */
        @Volatile
        var dirty: Boolean = true
        var drawingId: String? = null
        var shapes: List<DrawingShape> = emptyList()
    }

    /** Reads the drawing of the workspace one time. */
    suspend fun loadDrawing(workspaceId: String): Drawing? = writer.read { db -> db.fullDrawing(workspaceId) }

    /**
     * Writes [drawing] as the drawing of the workspace [Drawing.workspaceId] in one transaction.
     * The old drawing of the workspace goes.
     */
    suspend fun replaceDrawing(drawing: Drawing): StoreResult<Drawing> = writer.write { db ->
        db.workspaceQueries.selectById(drawing.workspaceId).executeAsOneOrNull()
            ?: return@write StoreResult.Refused(StoreRefusal.NotFound)
        db.drawingQueries.deleteForWorkspace(drawing.workspaceId)
        db.drawingQueries.insert(
            id = drawing.id,
            workspace_id = drawing.workspaceId,
            source_name = drawing.source?.fileName,
            source_format = drawing.source?.format,
            created_on = drawing.createdOn,
        )
        drawing.layers.forEach { layer -> db.drawingQueries.insertLayer(drawing.id, layer.index, layer.name, layer.hidden) }
        db.insertShapes(drawing.id, drawing.shapes)
        StoreResult.Ok(drawing)
    }

    /** Deletes the drawing of the workspace ("delete the drawing"). */
    suspend fun deleteDrawing(workspaceId: String) {
        writer.write { db -> db.drawingQueries.deleteForWorkspace(workspaceId) }
    }

    /** Shows or hides one layer of the drawing of the workspace. */
    suspend fun setLayerHidden(workspaceId: String, layerIndex: Int, hidden: Boolean) {
        writer.write { db ->
            db.drawingQueries.selectForWorkspace(workspaceId).executeAsOneOrNull()?.let { drawing ->
                db.drawingQueries.setLayerHidden(hidden = hidden, drawingId = drawing.id, layerIndex = layerIndex)
            }
        }
    }

    /**
     * Deletes the shape at the list position [index] of the drawing of the workspace ("delete one
     * shape").
     *
     * @return False when the workspace has no drawing or no shape at [index].
     */
    suspend fun deleteShape(workspaceId: String, index: Int): Boolean = writer.write { db ->
        val drawing = db.drawingQueries.selectForWorkspace(workspaceId).executeAsOneOrNull() ?: return@write false
        val seq = db.drawingQueries.selectShapeSeqs(drawing.id).executeAsList().getOrNull(index) ?: return@write false
        db.drawingQueries.deleteShape(drawing.id, seq)
        true
    }

    private fun SuperDroDatabase.fullDrawing(workspaceId: String, cache: ShapeCache): Drawing? {
        val row = drawingQueries.selectForWorkspace(workspaceId).executeAsOneOrNull()
        if (row == null) {
            cache.drawingId = null
            cache.shapes = emptyList()
            return null
        }
        if (cache.dirty || cache.drawingId != row.id) {
            // Clear the flag before the read: a write after this point sets it again.
            cache.dirty = false
            cache.shapes = drawingQueries.selectShapes(row.id).executeAsList().map { shape -> shape.toModel() }
            cache.drawingId = row.id
        }
        return drawingOf(row, cache.shapes)
    }

    private fun SuperDroDatabase.fullDrawing(workspaceId: String): Drawing? {
        val row = drawingQueries.selectForWorkspace(workspaceId).executeAsOneOrNull() ?: return null
        return drawingOf(row, drawingQueries.selectShapes(row.id).executeAsList().map { shape -> shape.toModel() })
    }

    private fun SuperDroDatabase.drawingOf(row: DrawingRow, shapes: List<DrawingShape>): Drawing {
        return Drawing(
            id = row.id,
            workspaceId = row.workspace_id,
            layers = drawingQueries.selectLayers(row.id).executeAsList().map { layer ->
                DrawingLayer(index = layer.layer_index, name = layer.name, hidden = layer.hidden)
            },
            shapes = shapes,
            source = row.source_name?.let { name -> DrawingSource(fileName = name, format = row.source_format ?: DrawingSource().format) },
            createdOn = row.created_on,
        )
    }
}
