package org.superdro.validation.readout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.superdro.core.model.BacklashMode
import org.superdro.core.model.CalibrationStatus
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderType
import org.superdro.core.model.MapPoint
import org.superdro.core.model.ProbeContactType
import org.superdro.core.store.files.adapter.AdapterProfileImporter
import org.superdro.core.store.files.adapter.AdapterTarget

/**
 * The import of an adapter profile file `.tdap` refuses `MAP` for a scale with a message
 * and validates each map text with a problem list. The file is a `.tdap` example file.
 */
class AdapterImportValidationTest {

    private fun tdap(input: String): String = """
        {
          "touchdro": "adapter-profile",
          "format": 1,
          "app": "3.32.00, Build 2026-09-20",
          "exported": "2026-10-11 14:30:00",
          "adapter": "TouchDRO Adapter",
          "model": "tda-xxx",
          "excluded": "Plus activation, adapter identity binding, machine profile settings, sub-datums, tools",
          "inputs": [ $input ],
          "probe": { "contact-type": "AUTO", "debounce-ms": 0 }
        }
    """.trimIndent()

    private val exampleInput = """
        {
          "input": "X",
          "encoder-type": "SCALE",
          "kind": "CONTINUOUS",
          "resolution": 5080,
          "invert": false,
          "filter": false,
          "error-map": "position,correction\n0,0\n10000,4\n20000,9",
          "backlash-mode": "CONSTANT",
          "backlash-constant": 3,
          "calibration-status": "CALIBRATED",
          "calibrated-on": "2026-10-01 09:12:45"
        }
    """.trimIndent()

    @Test
    fun example_file_gives_the_encoder_profile_of_X() {
        val preview = AdapterProfileImporter().read(tdap(exampleInput), AdapterTarget.NewAdapter)
        assertTrue(preview.usable, "problems ${preview.problems}")
        val adapter = assertNotNull(preview.adapter)
        val x = adapter.encoders.getValue('x')
        assertEquals(EncoderType.Scale, x.type)
        assertEquals(EncoderKind.Linear, x.kind)
        assertEquals(5080.0, x.resolution)
        assertEquals(listOf(MapPoint(0L, 0L), MapPoint(10000L, 4L), MapPoint(20000L, 9L)), x.errorMap)
        assertEquals(BacklashMode.Constant, x.backlashMode)
        assertEquals(3L, x.backlashCounts)
        assertEquals(CalibrationStatus.Calibrated, x.calibration.status)
        assertEquals(ProbeContactType.Auto, adapter.probeContactType)
        assertEquals(0L, adapter.probeDebounceMs)
    }

    @Test
    fun import_refuses_MAP_for_a_scale_with_a_message() {
        val input = exampleInput
            .replace("\"backlash-mode\": \"CONSTANT\"", "\"backlash-mode\": \"MAP\"")
            .replace("\"backlash-constant\": 3,", "\"backlash-constant\": 3,\n  \"backlash-map\": \"position,backlash\\n0,5\\n10000,6\",")
        val preview = AdapterProfileImporter().read(tdap(input), AdapterTarget.NewAdapter)
        val row = preview.rows.single { row -> row.input == 'x' }
        assertTrue(row.problems.isNotEmpty(), "no message for MAP on a scale")
        val written = preview.adapter?.encoders?.get('x')
        if (written != null) assertNotEquals(BacklashMode.Map, written.backlashMode)
    }

    @Test
    fun import_validates_the_map_text_with_a_problem_list() {
        val input = exampleInput.replace(
            "\"error-map\": \"position,correction\\n0,0\\n10000,4\\n20000,9\"",
            "\"error-map\": \"position,correction\\n0,0\\nabc def ghi\\n10000,4\"",
        )
        val preview = AdapterProfileImporter().read(tdap(input), AdapterTarget.NewAdapter)
        val row = preview.rows.single { row -> row.input == 'x' }
        assertTrue(row.problems.isNotEmpty(), "no problem for a malformed map line")
    }

    @Test
    fun import_refuses_a_map_with_a_reversal() {
        val input = exampleInput.replace(
            "\"error-map\": \"position,correction\\n0,0\\n10000,4\\n20000,9\"",
            "\"error-map\": \"position,correction\\n0,0\\n100,-150\"",
        )
        val preview = AdapterProfileImporter().read(tdap(input), AdapterTarget.NewAdapter)
        val row = preview.rows.single { row -> row.input == 'x' }
        assertTrue(row.problems.isNotEmpty(), "no problem for a reversal")
    }
}
