package org.superdro.core.store.repository

import org.superdro.core.model.DrawingShape
import org.superdro.core.store.db.Drawing_shape
import org.superdro.core.store.db.SuperDroDatabase

/**
 * The BLOB forms of the point list and the bulge map of a drawing shape (`Drawing.sq`). Each
 * BLOB starts with the version byte [VERSION]. The values are little-endian.
 */
internal object ShapeBlobs {
    const val VERSION: Byte = 1

    /** Version byte, then each value as 8 bytes. An empty list gives null. */
    fun encodePoints(points: List<Long>): ByteArray? {
        if (points.isEmpty()) return null
        val bytes = ByteArray(1 + points.size * Long.SIZE_BYTES)
        bytes[0] = VERSION
        points.forEachIndexed { index, value -> putLong(bytes, 1 + index * Long.SIZE_BYTES, value) }
        return bytes
    }

    fun decodePoints(blob: ByteArray?): List<Long> {
        if (blob == null || blob.isEmpty()) return emptyList()
        require(blob[0] == VERSION) { "Unknown point BLOB version ${blob[0]}." }
        val count = (blob.size - 1) / Long.SIZE_BYTES
        return List(count) { index -> getLong(blob, 1 + index * Long.SIZE_BYTES) }
    }

    /** Version byte, then each entry as a 4-byte segment index and the 8 bytes of the Double. */
    fun encodeBulges(bulges: Map<Int, Double>): ByteArray? {
        if (bulges.isEmpty()) return null
        val entrySize = Int.SIZE_BYTES + Long.SIZE_BYTES
        val bytes = ByteArray(1 + bulges.size * entrySize)
        bytes[0] = VERSION
        bulges.entries.sortedBy { entry -> entry.key }.forEachIndexed { index, (segment, bulge) ->
            val offset = 1 + index * entrySize
            putInt(bytes, offset, segment)
            putLong(bytes, offset + Int.SIZE_BYTES, bulge.toRawBits())
        }
        return bytes
    }

    fun decodeBulges(blob: ByteArray?): Map<Int, Double> {
        if (blob == null || blob.isEmpty()) return emptyMap()
        require(blob[0] == VERSION) { "Unknown bulge BLOB version ${blob[0]}." }
        val entrySize = Int.SIZE_BYTES + Long.SIZE_BYTES
        val count = (blob.size - 1) / entrySize
        return (0 until count).associate { index ->
            val offset = 1 + index * entrySize
            getInt(blob, offset) to Double.fromBits(getLong(blob, offset + Int.SIZE_BYTES))
        }
    }

    private fun putLong(bytes: ByteArray, offset: Int, value: Long) {
        for (shift in 0 until Long.SIZE_BYTES) bytes[offset + shift] = (value ushr (shift * 8)).toByte()
    }

    private fun getLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (shift in 0 until Long.SIZE_BYTES) value = value or ((bytes[offset + shift].toLong() and 0xFF) shl (shift * 8))
        return value
    }

    private fun putInt(bytes: ByteArray, offset: Int, value: Int) {
        for (shift in 0 until Int.SIZE_BYTES) bytes[offset + shift] = (value ushr (shift * 8)).toByte()
    }

    private fun getInt(bytes: ByteArray, offset: Int): Int {
        var value = 0
        for (shift in 0 until Int.SIZE_BYTES) value = value or ((bytes[offset + shift].toInt() and 0xFF) shl (shift * 8))
        return value
    }
}

internal fun Drawing_shape.toModel(): DrawingShape = DrawingShape(
    type = type,
    layerIndex = layer_index,
    style = style,
    groupId = group_id,
    closed = closed,
    x1 = x1_nm,
    y1 = y1_nm,
    x2 = x2_nm,
    y2 = y2_nm,
    radius = r_nm,
    startAngle = a0_rad,
    sweepAngle = da_rad,
    points = ShapeBlobs.decodePoints(points),
    bulges = ShapeBlobs.decodeBulges(bulges),
)

/** Inserts [shapes] into the drawing [drawingId] with the sequence numbers 0, 1, 2, ... */
internal fun SuperDroDatabase.insertShapes(drawingId: String, shapes: List<DrawingShape>) {
    shapes.forEachIndexed { seq, shape ->
        drawingQueries.insertShape(
            drawing_id = drawingId,
            seq = seq,
            layer_index = shape.layerIndex,
            type = shape.type,
            style = shape.style,
            group_id = shape.groupId,
            closed = shape.closed,
            x1_nm = shape.x1,
            y1_nm = shape.y1,
            x2_nm = shape.x2,
            y2_nm = shape.y2,
            r_nm = shape.radius,
            a0_rad = shape.startAngle,
            da_rad = shape.sweepAngle,
            points = ShapeBlobs.encodePoints(shape.points),
            bulges = ShapeBlobs.encodeBulges(shape.bulges),
        )
    }
}
