package org.superdro.core.ui.mcp.populate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.superdro.core.mcp.GateDecision
import org.superdro.core.mcp.GateTexts

class ImageToolsTest {

    @Test
    fun theHeaderReaderGivesTheSizeOfAPngAndAJpeg() {
        assertEquals(ImageHeader("image/png", 32, 16), ImageHeader.read(PNG_32_16))
        assertEquals(ImageHeader("image/jpeg", 640, 480), ImageHeader.read(JPEG_640_480))
        assertNull(ImageHeader.read("%PDF".encodeToByteArray()))
        assertNull(ImageHeader.read(PNG_32_16.copyOf(20)))
        assertNull(ImageHeader.read(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())))
    }

    private fun identity() = jsonList(100.0, 0.0, 0.0, 0.0, 100.0, 0.0, 0.0, 0.0, 1.0)

    @Test
    fun setReferenceImageWithAMatrixWritesTheFileAndTheWorkspace() = runTest {
        val fixture = PopulateFixture(this).start()
        val workspace = fixture.millWorkspace()
        val fileId = fixture.upload("image/png", PNG_32_16)
        val result = fixture.ok("set_reference_image", args { put("machine", "Mill"); put("workspace", workspace.id); put("fileId", fileId); put("matrix", identity()); put("alpha", "127") })
        assertEquals("Reference image set: image 32 × 16, quality Good, 100 µm for each pixel", result.text)
        val stored = assertNotNull(fixture.machines.loadWorkspace(workspace.id)!!.referenceImage)
        assertTrue(stored.fileName.startsWith("images/") && stored.fileName.endsWith(".png"), stored.fileName)
        assertEquals(127, stored.alpha)
        assertEquals(listOf(100.0, 0.0, 0.0, 0.0, 100.0, 0.0, 0.0, 0.0, 1.0), stored.matrix)
        assertTrue(fixture.storeIO.files.getValue(stored.fileName).contentEquals(PNG_32_16))
        assertEquals(stored.fileName, result.obj.str("fileName"))
        assertEquals(emptyList(), fixture.gate.requests)
        fixture.close()
    }

    @Test
    fun setReferenceImageWithReferencePairsFitsTheMatrixInTheUnits() = runTest {
        val fixture = PopulateFixture(this).start()
        val fileId = fixture.upload("image/jpeg", JPEG_640_480)
        val reference = { px: Double, py: Double, h: Double, v: Double ->
            buildJsonObject { put("pixel", jsonList(px, py)); put("machine", jsonList(h, v)) }
        }
        val result = fixture.ok(
            "set_reference_image",
            args {
                put("machine", "Mill")
                put("workspace", "Default Workspace")
                put("fileId", fileId)
                put("units", "inch")
                put("references", jsonList(reference(0.0, 0.0, 0.0, 0.0), reference(640.0, 480.0, 6.4, 4.8)))
            },
        )
        assertEquals("Good", result.obj.str("quality"))
        val matrix = result.obj.list("matrix").map { it.toString().toDouble() }
        assertEquals(254.0, matrix[0], 1e-6)
        assertEquals(254.0, kotlin.math.abs(matrix[4]), 1e-6)
        val stored = assertNotNull(fixture.millWorkspace().referenceImage)
        assertTrue(stored.fileName.endsWith(".jpg"))
        assertEquals(255, stored.alpha)
        fixture.close()
    }

    @Test
    fun setReferenceImagePreviewWritesNoFile() = runTest {
        val fixture = PopulateFixture(this).start()
        val fileId = fixture.upload("image/png", PNG_32_16)
        val result = fixture.ok("set_reference_image", args { put("machine", "Mill"); put("workspace", "Default Workspace"); put("fileId", fileId); put("matrix", identity()); put("preview", true) })
        assertEquals("Preview: image 32 × 16, quality Good, 100 µm for each pixel", result.text)
        assertTrue(fixture.storeIO.files.isEmpty())
        assertNull(fixture.millWorkspace().referenceImage)
        fixture.close()
    }

    @Test
    fun setReferenceImageRefusesBadArguments() = runTest {
        val fixture = PopulateFixture(this).start()
        val fileId = fixture.upload("image/png", PNG_32_16)
        val base = { extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit ->
            args { put("machine", "Mill"); put("workspace", "Default Workspace"); put("fileId", fileId); extra() }
        }
        assertEquals("Give either matrix or references, not both and not none.", fixture.error("set_reference_image", base {}).message)
        assertEquals("The matrix needs 9 numbers, not 4.", fixture.error("set_reference_image", base { put("matrix", jsonList(1, 0, 0, 1)) }).message)
        assertEquals("The alpha must be 255, 127, or 0, not \"50\".", fixture.error("set_reference_image", base { put("matrix", identity()); put("alpha", "50") }).message)
        val three = buildJsonObject { put("pixel", jsonList(0, 0)); put("machine", jsonList(0, 0)) }
        val threeRefs = fixture.error("set_reference_image", base { put("references", jsonList(three, three, three)) })
        assertEquals("Three reference pairs cannot be fitted. Give two or four.", threeRefs.message)
        val notDiagonal = fixture.error(
            "set_reference_image",
            base { put("references", jsonList(three, buildJsonObject { put("pixel", jsonList(10, 0)); put("machine", jsonList(10, 0)) })) },
        )
        assertEquals("Two reference pairs must be diagonally apart: different X and different Y.", notDiagonal.message)
        val text = fixture.upload("text/plain", "hello".encodeToByteArray())
        val notImage = fixture.error("set_reference_image", args { put("machine", "Mill"); put("workspace", "Default Workspace"); put("fileId", text); put("matrix", identity()) })
        assertEquals("The upload \"$text\" is not a JPEG or a PNG image with a readable size.", notImage.message)
        fixture.close()
    }

    @Test
    fun clearReferenceImageAsksTheGateAndDeletesTheFile() = runTest {
        val fixture = PopulateFixture(this).start()
        val workspace = fixture.millWorkspace()
        val none = fixture.ok("clear_reference_image", args { put("machine", "Mill"); put("workspace", workspace.id) })
        assertEquals("The workspace \"Default Workspace\" has no reference image", none.text)
        val fileId = fixture.upload("image/png", PNG_32_16)
        fixture.ok("set_reference_image", args { put("machine", "Mill"); put("workspace", workspace.id); put("fileId", fileId); put("matrix", identity()) })
        val fileName = fixture.millWorkspace().referenceImage!!.fileName
        val preview = fixture.ok("clear_reference_image", args { put("machine", "Mill"); put("workspace", workspace.id); put("preview", true) })
        assertEquals("Preview: the reference image $fileName would be removed", preview.text)
        assertEquals(emptyList(), fixture.gate.requests)
        fixture.gate.decision = GateDecision.Refused
        assertEquals(GateTexts.REFUSED, fixture.error("clear_reference_image", args { put("machine", "Mill"); put("workspace", workspace.id) }).message)
        assertTrue(fileName in fixture.storeIO.files)
        fixture.gate.decision = GateDecision.Allowed
        val result = fixture.ok("clear_reference_image", args { put("machine", "Mill"); put("workspace", workspace.id) })
        assertEquals("Reference image removed", result.text)
        assertNull(fixture.millWorkspace().referenceImage)
        assertTrue(fileName !in fixture.storeIO.files)
        fixture.close()
    }
}
