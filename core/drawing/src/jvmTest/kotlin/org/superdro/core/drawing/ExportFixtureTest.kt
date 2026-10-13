package org.superdro.core.drawing

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume
import org.superdro.core.drawing.dxf.DxfConverter
import org.superdro.core.drawing.export.DxfExportRequest
import org.superdro.core.drawing.export.DxfExporter
import org.superdro.core.model.AxisId
import org.superdro.core.model.Drawing
import org.superdro.core.model.DrawingLayer
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.Guide
import org.superdro.core.model.Plane
import org.superdro.core.model.ShapeType
import org.superdro.core.model.SubDatum
import org.superdro.core.model.Workspace

/**
 * The DXF export samples. The files `export/<name>.dxf` are the output of this version, checked
 * with ezdxf (`fixtures/check_export.py`: `ezdxf.readfile` and the auditor, no error and no fix).
 *
 * - Set `SUPERDRO_WRITE_EXPORTS=1` to write the files again after a change of the exporter.
 * - Set `SUPERDRO_EZDXF_PYTHON` to a Python with ezdxf 1.4.4 to run the ezdxf check in the test.
 */
class ExportFixtureTest {

    private fun drawingOf(path: String): Drawing {
        val result = DxfConverter.convert(Fixtures.bytes(path), now = Fixtures.NOW)
        return DrawingConversion.toDrawing(result.wireframe!!, "workspace", id = "drawing", createdOn = Fixtures.NOW)
    }

    private val rich = Workspace(
        subDatums = listOf(
            SubDatum(name = "Bohrung ä Ø", tags = listOf("holes", "extra"), x = 1000.0, y = 2000.0, z = -500.0),
            SubDatum(name = "Corner 1", tags = listOf("Corner"), x = 0.0, y = 0.0),
            SubDatum(name = "", x = 5000.0, y = 7000.0),
            SubDatum(name = "Odd", tags = listOf("a:b<c>"), x = 3000.0, y = 3000.0),
        ),
        guides = listOf(Guide(axis = AxisId.X, valueUm = 20_000.0), Guide(axis = AxisId.Y, valueUm = -4000.0), Guide(axis = AxisId.Z, valueUm = 1.0)),
    )

    private fun samples(): Map<String, DxfExportRequest> {
        val entities = drawingOf("dxf/entities_r2000_mm.dxf")
        val example = Workspace(
            subDatums = listOf(SubDatum(name = "Point 1", tags = listOf("Holes"), x = 10_000.0, y = 5_000.0)),
            guides = listOf(Guide(axis = AxisId.X, valueUm = 20_000.0)),
        )
        val line = Drawing(layers = listOf(DrawingLayer(0, "PART")), shapes = listOf(DrawingShape(ShapeType.Line, x1 = 0, y1 = 0, x2 = 20_000_000, y2 = 5_000_000)))
        return linkedMapOf(
            "example_13_6" to DxfExportRequest(example, line),
            "rich_mm" to DxfExportRequest(rich, entities),
            "rich_inch_hidden" to DxfExportRequest(rich, entities, metric = false, includeHiddenLayers = true),
            "lathe_mm" to DxfExportRequest(rich.copy(plane = Plane.ZX), entities),
            "blocks_inch" to DxfExportRequest(Workspace(), drawingOf("dxf/blocks_r2000_inch.dxf"), metric = false),
            "curves_mm" to DxfExportRequest(Workspace(), drawingOf("dxf/curves_r2000_mm.dxf")),
            "empty_mm" to DxfExportRequest(Workspace()),
        )
    }

    @Test
    fun theExportsAreTheCheckedFiles() {
        val write = System.getenv("SUPERDRO_WRITE_EXPORTS") == "1"
        for ((name, request) in samples()) {
            val bytes = DxfExporter.export(request)
            if (write) {
                File("src/jvmTest/resources/export").mkdirs()
                File("src/jvmTest/resources/export/$name.dxf").writeBytes(bytes)
            } else {
                assertTrue(bytes.contentEquals(Fixtures.bytes("export/$name.dxf")), "export/$name.dxf is not the same")
            }
        }
    }

    @Test
    fun theImportOfAnExportGivesTheSameShapes() {
        val drawing = drawingOf("dxf/entities_r2000_mm.dxf")
        val shown = drawing.layers.filter { layer -> !layer.hidden }.map { layer -> layer.index }.toSet()
        val expected = drawing.shapes.filter { shape -> shape.layerIndex in shown }
        val exported = DxfExporter.export(DxfExportRequest(rich, drawing))
        val result = DxfConverter.convert(exported, now = Fixtures.NOW)
        assertEquals(ConvertStatus.Ready, result.status)
        val shapes = DrawingConversion.toShapes(result.wireframe!!)
        // The origin mark (3 entities) comes first, then the drawing.
        val back = shapes.subList(3, 3 + expected.size)
        expected.zip(back).forEach { (original, imported) ->
            assertEquals(original.type, imported.type)
            assertEquals(original.style, imported.style)
            assertEquals(original.copy(layerIndex = 0, style = 1, startAngle = null, sweepAngle = null), imported.copy(layerIndex = 0, style = 1, startAngle = null, sweepAngle = null))
            if (original.startAngle != null) {
                assertEquals(original.startAngle!!, imported.startAngle!!, 1e-9)
                assertEquals(original.sweepAngle!!, imported.sweepAngle!!, 1e-9)
            }
        }
        // The sub-datum points come after the drawing; XLINE and TEXT give warnings.
        assertEquals(4, shapes.drop(3 + expected.size).count { shape -> shape.type == ShapeType.Point })
        assertEquals(
            listOf("Dropped 3 TEXT entities (not geometry)", "Dropped 2 XLINE entities (not supported)"),
            result.warnings.map { warning -> warning.message },
        )
    }

    @Test
    fun ezdxfReadsAndAuditsTheExports() {
        val python = System.getenv("SUPERDRO_EZDXF_PYTHON")
        Assume.assumeTrue("SUPERDRO_EZDXF_PYTHON is not set", !python.isNullOrBlank())
        val directory = File("build/tmp/dxf-export").apply { mkdirs() }
        val files = samples().map { (name, request) -> File(directory, "$name.dxf").apply { writeBytes(DxfExporter.export(request)) } }
        val process = ProcessBuilder(listOf(python!!, "-I", File("src/jvmTest/fixtures/check_export.py").absolutePath) + files.map { file -> file.absolutePath })
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS))
        println(output)
        assertEquals(0, process.exitValue(), output)
        assertEquals(files.size, output.lines().count { line -> line.contains("\"errors\": []") && line.contains("\"fixes\": []") })
    }
}
