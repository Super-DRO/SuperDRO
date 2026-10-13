package org.superdro.core.store.repository

import kotlinx.coroutines.flow.Flow
import org.superdro.core.model.Drawing
import org.superdro.core.model.DrawingLayer
import org.superdro.core.model.DrawingSource
import org.superdro.core.store.StoreRefusal
import org.superdro.core.store.StoreResult
import org.superdro.core.store.StoreWriter
import org.superdro.core.store.db.SuperDroDatabase

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

    /** The drawing of the workspace with its layers and shapes, or null. */
    fun drawing(workspaceId: String): Flow<Drawing?> = writer.observe(
        database.drawingQueries.selectForWorkspace(workspaceId),
        database.drawingQueries.selectLayersForWorkspace(workspaceId),
        database.drawingQueries.selectShapeSeqsForWorkspace(workspaceId),
    ) { db -> db.fullDrawing(workspaceId) }

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

    private fun SuperDroDatabase.fullDrawing(workspaceId: String): Drawing? {
        val row = drawingQueries.selectForWorkspace(workspaceId).executeAsOneOrNull() ?: return null
        return Drawing(
            id = row.id,
            workspaceId = row.workspace_id,
            layers = drawingQueries.selectLayers(row.id).executeAsList().map { layer ->
                DrawingLayer(index = layer.layer_index, name = layer.name, hidden = layer.hidden)
            },
            shapes = drawingQueries.selectShapes(row.id).executeAsList().map { shape -> shape.toModel() },
            source = row.source_name?.let { name -> DrawingSource(fileName = name, format = row.source_format ?: DrawingSource().format) },
            createdOn = row.created_on,
        )
    }
}
