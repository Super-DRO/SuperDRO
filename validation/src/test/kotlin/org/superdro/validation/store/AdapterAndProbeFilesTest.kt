package org.superdro.validation.store

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.BacklashMode
import org.superdro.core.model.Calibration
import org.superdro.core.model.CalibrationStatus
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.EncoderType
import org.superdro.core.model.MapPoint
import org.superdro.core.model.ProbeContactType
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType
import org.superdro.core.model.SuperDroJson
import org.superdro.core.store.files.FileSource
import org.superdro.core.store.files.MessageKey
import org.superdro.core.store.files.RowAction
import org.superdro.core.store.files.adapter.AdapterImportPreview
import org.superdro.core.store.files.adapter.AdapterProfileFile
import org.superdro.core.store.files.adapter.AdapterProfileImporter
import org.superdro.core.store.files.adapter.AdapterTarget
import org.superdro.core.store.files.probe.ProbeImportContext
import org.superdro.core.store.files.probe.ProbeProfileFile
import org.superdro.core.store.files.probe.ProbeProfileImporter

/**
 * The import of the `.tdap` file and of the `.tdprb` file, and the SuperDRO export
 * files.
 */
class AdapterAndProbeFilesTest {

    private val adapters = AdapterProfileImporter(timeZone = TimeZone.UTC)
    private val probes = ProbeProfileImporter(timeZone = TimeZone.UTC)
    private val probeContext = ProbeImportContext(profileId = "mill-1", profileName = "Mill")

    private fun tdap(vararg inputs: JsonObject, probe: JsonObject? = null): String = buildJsonObject {
        put("touchdro", "adapter-profile")
        put("format", 1)
        put("adapter", "Bench")
        put("inputs", JsonArray(inputs.toList()))
        if (probe != null) put("probe", probe)
    }.toString()

    private fun input(letter: String, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("input", letter)
        block()
    }

    private fun profileOf(preview: AdapterImportPreview, input: Char): EncoderProfile =
        assertNotNull(assertNotNull(preview.adapter, "problems ${preview.problems}").encoders[input], "input $input")

    @Test
    fun import_of_the_tdap_example_of_error_compensation_11_1() {
        val preview = adapters.read(StoreFixtures.text("error-compensation-11.1.tdap"), AdapterTarget.NewAdapter)
        assertTrue(preview.usable, "problems ${preview.problems}")
        assertEquals(FileSource.TouchDro, preview.source)
        assertEquals("TouchDRO Adapter", preview.adapterName)
        assertEquals("tda-xxx", preview.model)
        val adapter = assertNotNull(preview.adapter)
        assertEquals("TouchDRO Adapter", adapter.alias)
        assertEquals(ProbeContactType.Auto, adapter.probeContactType)
        assertEquals(0L, adapter.probeDebounceMs)
        val x = profileOf(preview, 'x')
        assertEquals(EncoderType.Scale, x.type)
        assertEquals(EncoderKind.Linear, x.kind)
        assertEquals(5080.0, x.resolution)
        assertFalse(x.reversed)
        assertFalse(x.filter)
        assertEquals(listOf(MapPoint(0, 0), MapPoint(10_000, 4), MapPoint(20_000, 9)), x.errorMap)
        assertEquals(BacklashMode.Constant, x.backlashMode)
        assertEquals(3L, x.backlashCounts)
        assertEquals(CalibrationStatus.Calibrated, x.calibration.status)
        assertEquals(Instant.parse("2026-10-01T09:12:45Z"), x.calibration.calibratedOn)
        // An input that the file does not have keeps the default of a new input.
        assertEquals(EncoderProfile.defaultFor('y'), profileOf(preview, 'y'))
        assertEquals(RowAction.NotInFile, preview.rows.single { it.input == 'y' }.action)
        assertTrue(StoreFixtures.UUID_FORM.matches(adapter.id))
    }

    @Test
    fun the_defaults_of_the_keys_that_a_tdap_file_does_not_have() {
        val preview = adapters.read(tdap(input("Z")), AdapterTarget.NewAdapter)
        val z = profileOf(preview, 'z')
        // SCALE, CONTINUOUS, 25400, NONE, NOT_CALIBRATED.
        assertEquals(EncoderType.Scale, z.type)
        assertEquals(EncoderKind.Linear, z.kind)
        assertEquals(25_400.0, z.resolution)
        assertEquals(BacklashMode.None, z.backlashMode)
        assertEquals(CalibrationStatus.NotCalibrated, z.calibration.status)
    }

    @Test
    fun a_tdap_value_that_is_not_valid_gets_the_default_and_a_warning() {
        val preview = adapters.read(
            tdap(
                input("X") { put("kind", "SPIRAL"); put("resolution", 5080) },
                input("Y") { put("resolution", -5) },
                input("Q") { put("resolution", 1000) },
                input("X") { put("resolution", 1) },
                probe = buildJsonObject { put("contact-type", "MAGNETIC"); put("debounce-ms", 10) },
            ),
            AdapterTarget.NewAdapter,
        )
        val x = profileOf(preview, 'x')
        assertEquals(EncoderKind.Linear, x.kind, "an unknown enum name gives the default")
        assertEquals(5080.0, x.resolution, "the first entry of an input is used")
        assertTrue(preview.rows.single { it.input == 'x' }.problems.any { it.message.key == MessageKey.AdapterInvalidValue })
        // A resolution that is not more than 0 skips the row.
        assertEquals(RowAction.Skip, preview.rows.single { it.input == 'y' }.action)
        assertEquals(EncoderProfile.defaultFor('y'), profileOf(preview, 'y'))
        assertTrue(preview.report.notImported.any { it.message.key == MessageKey.AdapterUnknownInput }, "SuperDRO does not use input Q")
        assertTrue(preview.report.notImported.any { it.message.key == MessageKey.AdapterDuplicateInput })
        assertEquals(ProbeContactType.Auto, preview.adapter?.probeContactType)
        assertEquals(10L, preview.adapter?.probeDebounceMs)
    }

    @Test
    fun backlash_mode_map_on_a_scale_is_refused_with_a_message() {
        val preview = adapters.read(
            tdap(
                input("X") {
                    put("encoder-type", "SCALE")
                    put("backlash-mode", "MAP")
                    put("backlash-map", "0,5\n1000,6")
                },
            ),
            AdapterTarget.NewAdapter,
        )
        val x = profileOf(preview, 'x')
        assertFalse(x.backlashMode == BacklashMode.Map, "MAP is refused for a scale")
        assertTrue(preview.rows.single { it.input == 'x' }.problems.any { it.message.key == MessageKey.AdapterMapOnScale })
        // A leadscrew encoder (ROTARY) can use a backlash map.
        val leadscrew = adapters.read(
            tdap(
                input("X") {
                    put("encoder-type", "ROTARY")
                    put("counts-per-screw-rev", 2000)
                    put("backlash-mode", "MAP")
                    put("backlash-map", "0,5\n1000,6")
                },
            ),
            AdapterTarget.NewAdapter,
        )
        val screw = profileOf(leadscrew, 'x')
        assertEquals(BacklashMode.Map, screw.backlashMode)
        assertEquals(listOf(MapPoint(0, 5), MapPoint(1000, 6)), screw.backlashMap)
    }

    @Test
    fun a_tdap_map_text_gets_the_validation() {
        fun map(text: String): Pair<List<MapPoint>, List<MessageKey>> {
            val preview = adapters.read(tdap(input("X") { put("error-map", text) }), AdapterTarget.NewAdapter)
            val problems = preview.rows.single { it.input == 'x' }.problems.map { it.message.key }
            return profileOf(preview, 'x').errorMap to problems
        }
        // Valid: comments, a header line, the separators `,`, TAB, and space.
        val (good, goodProblems) = map("# TouchDRO error correction map\nposition,correction\n0,0\n10000\t4\n20000 9\n")
        assertEquals(listOf(MapPoint(0, 0), MapPoint(10_000, 4), MapPoint(20_000, 9)), good)
        assertEquals(emptyList(), goodProblems)
        // Not valid: the map is refused and the problem list tells why.
        val cases = mapOf(
            "0,0\n100,5,x\n200,10" to MessageKey.MapMalformedLine,
            "0,0\n100,1.5" to MessageKey.MapMalformedLine,
            "0,0\n0,5" to MessageKey.MapDuplicatePosition,
            "0,0\n100,-150" to MessageKey.MapReversal,
            "0,0\n100,-100" to MessageKey.MapDeadBand,
        )
        cases.forEach { (text, key) ->
            val (points, problems) = map(text)
            assertEquals(emptyList(), points, "the map $text is refused")
            assertTrue(MessageKey.AdapterErrorMapRefused in problems, "a message for $text")
            assertTrue(key in problems, "the problem $key for $text: $problems")
        }
    }

    @Test
    fun a_map_with_more_than_20000_points_is_refused() {
        val tooMany = (0..20_000).joinToString("\n") { index -> "${index * 10},0" }
        val preview = adapters.read(tdap(input("X") { put("error-map", tooMany) }), AdapterTarget.NewAdapter)
        assertEquals(emptyList(), profileOf(preview, 'x').errorMap)
        val exact = (0 until 20_000).joinToString("\n") { index -> "${index * 10},${index % 2}" }
        val accepted = adapters.read(tdap(input("X") { put("error-map", exact) }), AdapterTarget.NewAdapter)
        assertEquals(20_000, profileOf(accepted, 'x').errorMap.size)
    }

    @Test
    fun the_tdap_export_has_the_touchdro_form_and_no_device_identity() {
        val adapter = AdapterProfile(
            id = "a1",
            alias = "Bench DRO",
            model = "tda-xxx",
            probeContactType = ProbeContactType.NormallyOpen,
            probeDebounceMs = 25,
            encoders = mapOf(
                'x' to EncoderProfile(
                    input = 'x', resolution = 5080.0, errorMap = listOf(MapPoint(0, 0), MapPoint(10_000, 4)),
                    backlashMode = BacklashMode.Constant, backlashCounts = 3,
                    calibration = Calibration(status = CalibrationStatus.Calibrated),
                ),
                't' to EncoderProfile.defaultFor('t'),
                'z' to EncoderProfile.defaultFor('z').copy(reversed = true, filter = true),
            ),
        )
        val file = AdapterProfileFile.exportTdap(adapter, StoreFixtures.exportContext)
        assertEquals("superdro-adapter_profile-Bench_DRO-20261011_143005.tdap", file.fileName)
        val root = SuperDroJson.parseToJsonElement(file.text).jsonObject
        assertEquals("adapter-profile", root["touchdro"]?.jsonPrimitive?.content)
        assertEquals("1", root["format"]?.jsonPrimitive?.content)
        assertFalse("device" in root.keys)
        // The input order T, X, Y, Z, W, U, V.
        assertEquals(listOf("T", "X", "Z"), root.getValue("inputs").jsonArray.map { it.jsonObject.getValue("input").jsonPrimitive.content })
        val again = adapters.read(file.text, AdapterTarget.NewAdapter)
        val x = profileOf(again, 'x')
        assertEquals(5080.0, x.resolution)
        assertEquals(listOf(MapPoint(0, 0), MapPoint(10_000, 4)), x.errorMap)
        assertEquals(BacklashMode.Constant, x.backlashMode)
        assertEquals(3L, x.backlashCounts)
        assertEquals(CalibrationStatus.Calibrated, x.calibration.status)
        assertTrue(profileOf(again, 'z').reversed)
        assertTrue(profileOf(again, 'z').filter)
        assertEquals(ProbeContactType.NormallyOpen, again.adapter?.probeContactType)
        assertEquals(25L, again.adapter?.probeDebounceMs)
    }

    @Test
    fun the_superdro_adapter_file_has_the_superdro_header_and_no_device() {
        val adapter = AdapterProfile(id = "a1", alias = "Bench")
        val file = AdapterProfileFile.exportSuperDro(adapter, StoreFixtures.exportContext)
        assertEquals("superdro-adapter_profile-Bench-20261011_143005.json", file.fileName)
        val root = SuperDroJson.parseToJsonElement(file.text).jsonObject
        assertEquals("adapter-profile", root["superdro"]?.jsonPrimitive?.content)
        assertEquals("1", root["format"]?.jsonPrimitive?.content)
        assertEquals("2026-10-11 14:30:05", root["exported"]?.jsonPrimitive?.content)
        assertNotNull(root["app"])
        assertFalse("device" in root.keys)
        val again = adapters.read(file.text, AdapterTarget.NewAdapter)
        assertTrue(again.usable)
        assertEquals("Bench", again.adapter?.alias)
        assertEquals(adapter.encoders, again.adapter?.encoders)
    }

    @Test
    fun import_of_the_tdprb_example_of_probe_edge_finder_13_12_1() {
        val preview = probes.read(StoreFixtures.text("probe-edge-finder-13.12.1.tdprb"), probeContext)
        assertTrue(preview.usable)
        assertEquals(FileSource.TouchDro, preview.source)
        assertEquals("Bridgeport", preview.fileProfileName)
        // The profiles come from a different machine profile: a warning.
        assertTrue(preview.problems.any { it.message.key == MessageKey.ProbeOtherProfile })
        val touch = assertNotNull(preview.rows[0].profile)
        assertEquals(ProbeType.TouchProbe, touch.type)
        assertEquals("Touch probe 2 mm", touch.name)
        assertEquals('p', touch.port)
        assertEquals(1987.4, touch.stylusDiameterUm)
        assertEquals(1987.4, touch.effectiveDiameterUm)
        assertEquals(1.1, touch.calibrationResidualUm)
        assertTrue(touch.metric)
        assertEquals(3, touch.sampleCount)
        assertEquals(20.0, touch.spreadLimitUm)
        assertEquals(Instant.parse("2026-10-11T13:58:02Z"), touch.calibratedOn)
        // The digest is not the SHA-256 of the session: the session and the digest are dropped, with a warning.
        assertNull(touch.calibrationSession)
        assertNull(touch.sessionDigest)
        assertTrue(preview.rows[0].problems.any { it.message.key == MessageKey.ProbeSessionDropped })
        val setter = assertNotNull(preview.rows[1].profile)
        assertEquals(ProbeType.ToolHeightSetter, setter.type)
        assertEquals("Tool setter 50.00 mm", setter.name)
        assertEquals(50_000.0, setter.zOffsetUm)
        assertNull(setter.port)
        val plan = preview.plan()
        assertEquals(2, plan.add.size)
        plan.add.forEach { profile ->
            assertEquals("mill-1", profile.profileId)
            assertFalse(profile.standardTip, "the import never makes a standard tip")
            assertTrue(StoreFixtures.UUID_FORM.matches(profile.id), "New IDs")
        }
    }

    @Test
    fun a_session_with_its_correct_digest_stays_and_a_session_with_no_digest_gets_a_warning() {
        val session = """{"format":1,"profile":7,"complete":true}"""
        val digest = MessageDigest.getInstance("SHA-256").digest(session.encodeToByteArray()).joinToString("") { "%02x".format(it) }
        val text = buildJsonObject {
            put("touchdro", "probe-profiles")
            put("format", 1)
            put(
                "profiles",
                buildJsonArray {
                    add(buildJsonObject { put("type", "TOUCH_PROBE"); put("name", "A"); put("input", "q"); put("calibration-session", session); put("session-digest", digest.uppercase()) })
                    add(buildJsonObject { put("type", "touch_probe"); put("name", "B"); put("calibration-session", session) })
                    add(buildJsonObject { put("type", "LASER"); put("name", "C") })
                    add(buildJsonObject { put("type", "EDGE_FINDER_2D"); put("stylus-diameter", 6000) })
                    add(buildJsonObject { put("type", "EDGE_FINDER_2D"); put("name", "N".repeat(60)); put("input", "x") })
                    add(buildJsonObject { put("type", "CAMERA"); put("name", "Scope"); put("metric", "no"); put("x-offset", 25400); put("input", "p") })
                },
            )
        }.toString()
        val rows = probes.read(text, probeContext).rows
        val a = assertNotNull(rows[0].profile)
        assertEquals(session, a.calibrationSession, "the digest compare ignores the case")
        assertEquals('q', a.port)
        val b = assertNotNull(rows[1].profile, "the type compare ignores the case")
        assertEquals(session, b.calibrationSession)
        assertTrue(rows[1].problems.any { it.message.key == MessageKey.ProbeSessionUnsigned })
        assertNull(rows[2].profile, "an unknown type skips the row")
        assertTrue(rows[2].problems.any { it.message.key == MessageKey.ProbeUnknownType })
        assertTrue(assertNotNull(rows[3].profile).name.isNotBlank(), "no name gives the type name")
        val long = assertNotNull(rows[4].profile)
        assertEquals(50, long.name.length, "a name of more than 50 characters is cut")
        assertNull(long.port, "x is not a probe port")
        val camera = assertNotNull(rows[5].profile)
        assertFalse(camera.metric)
        assertNull(camera.port, "a camera has no trigger source")
        assertEquals(25_400.0, camera.xOffsetUm)
    }

    @Test
    fun the_probe_exports_have_only_the_own_profiles_and_no_device() {
        val profiles = listOf(
            ProbeProfile(id = "p2", profileId = "mill-1", type = ProbeType.ToolHeightSetter, name = "Setter", port = null, zOffsetUm = 50_000.0),
            ProbeProfile(id = "p1", profileId = "mill-1", type = ProbeType.TouchProbe, name = "Probe", port = 'p', stylusDiameterUm = 2000.0, sampleCount = 3),
            ProbeProfile(id = "s1", profileId = null, type = ProbeType.EdgeFinder2D, name = "Edge finder 4 mm", stylusDiameterUm = 4000.0, standardTip = true),
            ProbeProfile(id = "o1", profileId = "other", type = ProbeType.TouchProbe, name = "Other"),
        )
        val tdprb = ProbeProfileFile.exportTdprb("mill-1", "Bridgeport", profiles, StoreFixtures.exportContext)
        assertEquals("superdro-probe_profiles-Bridgeport-20261011_143005.tdprb", tdprb.fileName)
        val root = SuperDroJson.parseToJsonElement(tdprb.text).jsonObject
        assertEquals("probe-profiles", root["touchdro"]?.jsonPrimitive?.content)
        assertFalse("device" in root.keys)
        assertEquals(listOf("Probe", "Setter"), root.getValue("profiles").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content })
        val again = probes.read(tdprb.text, ProbeImportContext("mill-2", "Bridgeport")).rows.map { assertNotNull(it.profile) }
        assertEquals(listOf(ProbeType.TouchProbe, ProbeType.ToolHeightSetter), again.map { it.type })
        assertEquals(2000.0, again[0].stylusDiameterUm)
        assertEquals(3, again[0].sampleCount)
        assertEquals(50_000.0, again[1].zOffsetUm)
        val json = ProbeProfileFile.exportSuperDro("mill-1", "Bridgeport", profiles, StoreFixtures.exportContext)
        assertEquals("superdro-probe_profiles-Bridgeport-20261011_143005.json", json.fileName)
        val superRoot = SuperDroJson.parseToJsonElement(json.text).jsonObject
        assertEquals("probe-profiles", superRoot["superdro"]?.jsonPrimitive?.content)
        assertFalse("device" in superRoot.keys)
        assertEquals(2, superRoot.getValue("profiles").jsonArray.size)
    }
}
