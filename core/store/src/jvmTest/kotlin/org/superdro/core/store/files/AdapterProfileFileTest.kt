package org.superdro.core.store.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.AxisId
import org.superdro.core.model.BacklashMode
import org.superdro.core.model.CalibrationStatus
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.EncoderType
import org.superdro.core.model.IdentitySource
import org.superdro.core.model.MachineType
import org.superdro.core.model.MapPoint
import org.superdro.core.model.ProbeContactType
import org.superdro.core.store.files.adapter.AdapterProfileFile
import org.superdro.core.store.files.adapter.AdapterProfileImporter
import org.superdro.core.store.files.adapter.AdapterTarget

/** The adapter profile files `.tdap` and SuperDRO. */
class AdapterProfileFileTest {

    private val importer = AdapterProfileImporter(now = { FIXED_TIME }, ids = sequentialIds(), timeZone = TimeZone.UTC)

    private fun imported(): AdapterImportResult {
        val preview = importer.read(Fixtures.bytes("adapter-3.32.00.tdap"), AdapterTarget.NewAdapter)
        return AdapterImportResult(preview, checkNotNull(preview.adapter))
    }

    private data class AdapterImportResult(val preview: org.superdro.core.store.files.adapter.AdapterImportPreview, val adapter: AdapterProfile)

    @Test
    fun theTdapFileGivesAnAdapterProfileWithItsEncoderProfiles() {
        val (preview, adapter) = imported()
        assertTrue(preview.usable)
        assertEquals("id-1", adapter.id)
        assertEquals("Shop Adapter", adapter.alias)
        assertEquals("tda-xxx", adapter.model)
        // The file has "24:0A:C4:12:34:58". The import writes the identity in lower case, the same as the live link.
        assertEquals("24:0a:c4:12:34:58", adapter.identity)
        assertEquals(IdentitySource.BtMac, adapter.identitySource)
        assertEquals(ProbeContactType.NormallyClosed, adapter.probeContactType)
        assertEquals(25L, adapter.probeDebounceMs)

        val x = adapter.encoders.getValue('x')
        assertEquals(EncoderType.Scale, x.type)
        assertEquals(EncoderKind.Linear, x.kind)
        assertEquals(5080.0, x.resolution)
        assertEquals(listOf(MapPoint(0, 0), MapPoint(10000, 4), MapPoint(20000, 9)), x.errorMap)
        assertEquals(BacklashMode.Constant, x.backlashMode)
        assertEquals(3L, x.backlashCounts)
        assertEquals(CalibrationStatus.Calibrated, x.calibration.status)
        assertEquals(Instant.parse("2026-10-01T09:12:45Z"), x.calibration.calibratedOn)
        assertEquals(MachineType.VerticalMill, x.calibration.machineType)
        assertEquals(AxisId.X, x.calibration.axisRole)

        val z = adapter.encoders.getValue('z')
        assertEquals(EncoderType.LeadscrewEncoder, z.type)
        assertEquals(2000.0, z.countsPerScrewRev)
        assertEquals(BacklashMode.Map, z.backlashMode)
        assertEquals(listOf(MapPoint(0, 30), MapPoint(5000, 40)), z.backlashMap)
        assertTrue(z.reversed)

        val w = adapter.encoders.getValue('w')
        assertEquals(EncoderType.RotaryEncoder, w.type)
        assertEquals(EncoderKind.Angular, w.kind)
        assertEquals("Rotary table", w.label)
        assertTrue(w.errorMapCyclic)

        val t = adapter.encoders.getValue('t')
        assertEquals(4.0, t.resolution)
        assertEquals(CalibrationStatus.UserSet, t.calibration.status)

        // U has a resolution of 0: the input keeps its default. V is not in the file.
        assertEquals(EncoderProfile.defaultFor('u'), adapter.encoders.getValue('u'))
        assertEquals(EncoderProfile.defaultFor('v'), adapter.encoders.getValue('v'))
        assertEquals(
            listOf('t' to RowAction.Add, 'x' to RowAction.Add, 'y' to RowAction.Add, 'z' to RowAction.Add, 'w' to RowAction.Add, 'u' to RowAction.Skip, 'v' to RowAction.NotInFile),
            preview.rows.map { it.input to it.action },
        )
        assertEquals(listOf("The probe contact type NORMALLY_CLOSED and the debounce of 25 ms go to the adapter profile."), preview.report.probe.map { ImportTexts.English.text(it) })
    }

    @Test
    fun aMapOnAScaleIsRefusedWithAMessage() {
        val (preview, adapter) = imported()
        val y = adapter.encoders.getValue('y')
        assertEquals(BacklashMode.None, y.backlashMode)
        assertTrue(
            "Input Y has the encoder type SCALE. A backlash map is permitted only on a leadscrew encoder. The import uses no backlash compensation." in
                preview.report.warnings.map { it.describe() },
        )
    }

    @Test
    fun eachMapTextGetsTheValidation() {
        val (preview, adapter) = imported()
        assertTrue(adapter.encoders.getValue('z').errorMap.isEmpty())
        assertTrue(adapter.encoders.getValue('w').errorMap.isEmpty())
        val warnings = preview.report.warnings.map { it.describe() }
        assertTrue("The error map of input Z is not valid. The import does not use it." in warnings, warnings.toString())
        assertTrue("Line 2 is not a pair of whole numbers." in warnings)
        assertTrue("The error map of input W is not valid. The import does not use it." in warnings)
        assertTrue("The corrected position moves back between positions 0 and 100." in warnings)
        assertEquals(
            listOf(
                "SuperDRO does not use input “A”. The import ignores it.",
                "The file has input X more than one time. The import uses the first one.",
                "The resolution “0” of input U is not usable. The import skips this input.",
            ),
            preview.report.notImported.map { it.describe() },
        )
    }

    @Test
    fun anExistingAdapterKeepsItsNameAndTheRowsShowTheChanges() {
        val (_, first) = imported()
        val existing = first.copy(id = "a1", alias = "Mill adapter", model = "tda-yyy")
        val again = importer.read(Fixtures.text("adapter-3.32.00.tdap"), AdapterTarget.Existing(existing))
        val adapter = checkNotNull(again.adapter)
        assertEquals("a1", adapter.id)
        assertEquals("Mill adapter", adapter.alias)
        assertTrue(again.rows.filter { it.input != 'u' && it.input != 'v' }.all { it.action == RowAction.Unchanged }, again.rows.toString())
        assertEquals(RowAction.NotInFile, again.rows.single { it.input == 'v' }.action)
        assertTrue("The file is for the adapter model “tda-xxx”. This adapter has the model “tda-yyy”." in again.problems.map { it.describe() })

        val changed = existing.copy(encoders = existing.encoders + ('x' to existing.encoders.getValue('x').copy(resolution = 2540.0, errorMapCyclic = false)))
        val update = importer.read(Fixtures.text("adapter-3.32.00.tdap"), AdapterTarget.Existing(changed))
        assertEquals(RowAction.Update, update.rows.single { it.input == 'x' }.action)
        val updated = checkNotNull(update.adapter)
        assertEquals(5080.0, updated.encoders.getValue('x').resolution)
        // A `.tdap` file has no "cyclic" switch: the switch of the input stays.
        assertFalse(updated.encoders.getValue('x').errorMapCyclic)
    }

    @Test
    fun theTdapExportHasTheTouchDroFormAndImportsAgainWithNoChange() {
        val (_, adapter) = imported()
        val file = AdapterProfileFile.exportTdap(adapter, CONTEXT)
        assertEquals("superdro-adapter_profile-Shop_Adapter-20261011_143005.tdap", file.fileName)
        assertFalse("device" in file.text)
        val root = Json.parseToJsonElement(file.text).jsonObject
        assertEquals("adapter-profile", root["touchdro"]!!.jsonPrimitive.content)
        assertEquals(listOf("T", "X", "Y", "Z", "W", "U", "V"), root["inputs"]!!.jsonArray.map { it.jsonObject["input"]!!.jsonPrimitive.content })
        val x = root["inputs"]!!.jsonArray[1].jsonObject
        assertEquals("position,correction\n0,0\n10000,4\n20000,9", x["error-map"]!!.jsonPrimitive.content)
        assertEquals("5080", x["resolution"]!!.jsonPrimitive.content)
        assertEquals("2026-10-01 09:12:45", x["calibrated-on"]!!.jsonPrimitive.content)

        val again = AdapterProfileImporter(now = { FIXED_TIME }, ids = { "id-2" }, timeZone = TimeZone.UTC).read(file.bytes(), AdapterTarget.NewAdapter)
        assertTrue(again.report.warnings.isEmpty(), again.report.warnings.toString())
        val result = checkNotNull(again.adapter)
        assertEquals(adapter.encoders, result.encoders)
        assertEquals(adapter.copy(id = "id-2"), result)
    }

    @Test
    fun theSuperDroFileImportsAgainWithNoChangeAndItsMapsAreChecked() {
        val (_, adapter) = imported()
        val stored = adapter.copy(encoders = adapter.encoders + ('w' to adapter.encoders.getValue('w').copy(errorMapCyclic = false, errorMap = listOf(MapPoint(0, 0), MapPoint(50, 3)))))
        val file = AdapterProfileFile.exportSuperDro(stored, CONTEXT)
        assertEquals("superdro-adapter_profile-Shop_Adapter-20261011_143005.json", file.fileName)
        assertFalse("device" in file.text)
        val result = checkNotNull(AdapterProfileImporter(ids = { "new" }).read(file.text, AdapterTarget.NewAdapter).adapter)
        assertEquals(stored.copy(id = "new"), result)

        val bad = stored.copy(encoders = stored.encoders + ('w' to stored.encoders.getValue('w').copy(errorMap = listOf(MapPoint(0, 0), MapPoint(0, 3)))))
        val preview = AdapterProfileImporter(ids = { "new" }).read(AdapterProfileFile.exportSuperDro(bad, CONTEXT).text, AdapterTarget.NewAdapter)
        assertTrue(preview.adapter!!.encoders.getValue('w').errorMap.isEmpty())
        assertTrue("Position 0 is in the map more than one time." in preview.report.warnings.map { it.describe() })
    }

    @Test
    fun aSettingsFileIsNotAnAdapterProfile() {
        assertEquals(
            listOf("The file contains “settings”. This import needs “adapter-profile”."),
            importer.read(Fixtures.bytes("settings-3.32.00.tdset"), AdapterTarget.NewAdapter).problems.map { it.describe() },
        )
        assertEquals(listOf("The file has no inputs."), importer.read("""{"touchdro":"adapter-profile","inputs":[]}""", AdapterTarget.NewAdapter).problems.map { it.describe() })
    }
}
