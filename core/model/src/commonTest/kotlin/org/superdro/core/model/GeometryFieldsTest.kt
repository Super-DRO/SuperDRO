package org.superdro.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fields of the point name and the map comments. Each field has a default, so an old file
 * loads.
 */
class GeometryFieldsTest {

    @Test
    fun aPointShapeKeepsItsName() {
        assertNull(DrawingShape().name)
        val shape = DrawingShape(type = ShapeType.Point, x1 = 1000L, y1 = 2000L, name = "A1")
        val text = SuperDroJson.encodeToString(DrawingShape.serializer(), shape)
        assertEquals(shape, SuperDroJson.decodeFromString(DrawingShape.serializer(), text))
        assertNull(SuperDroJson.decodeFromString(DrawingShape.serializer(), """{"type":"Point","x1":1,"y1":2}""").name)
    }

    @Test
    fun anErrorMapKeepsTheCommentsOfItsText() {
        assertTrue(EncoderProfile().errorMapComments.isEmpty())
        val profile = EncoderProfile(errorMapComments = listOf("# wizard 2026-10-12", "# reference input w"))
        val text = SuperDroJson.encodeToString(EncoderProfile.serializer(), profile)
        assertEquals(profile, SuperDroJson.decodeFromString(EncoderProfile.serializer(), text))
        assertTrue(SuperDroJson.decodeFromString(EncoderProfile.serializer(), "{}").errorMapComments.isEmpty())
    }
}
