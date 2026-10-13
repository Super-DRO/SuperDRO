package org.superdro.core.store.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType
import org.superdro.core.store.files.probe.ProbeImportContext
import org.superdro.core.store.files.probe.ProbeProfileFile
import org.superdro.core.store.files.probe.ProbeProfileImporter

/** The probe profile files `.tdprb` and SuperDRO. */
class ProbeProfileFileTest {

    private val importer = ProbeProfileImporter(ids = sequentialIds(), timeZone = TimeZone.UTC)
    private val context = ProbeImportContext("p1", "Bridgeport")

    @Test
    fun sha256GivesTheStandardDigests() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(""))
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1", Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", Sha256.hex("a".repeat(1_000_000)))
    }

    @Test
    fun theTdprbFileGivesTheProbeProfilesOfTheMachineProfile() {
        val preview = importer.read(Fixtures.bytes("probes-3.32.00.tdprb"), context)
        assertTrue(preview.usable)
        assertEquals(listOf(ImportOperation.AddAsNew), preview.operations)
        val profiles = preview.rows.mapNotNull { it.profile }
        assertEquals(listOf("Touch probe 2 mm", "Tool setter 50.00 mm", "Edge finder r", "Touch probe"), profiles.map(ProbeProfile::name))

        val probe = profiles[0]
        assertEquals(ProbeType.TouchProbe, probe.type)
        assertEquals('p', probe.port)
        assertEquals(1987.4, probe.stylusDiameterUm)
        assertEquals(1987.4, probe.effectiveDiameterUm)
        assertEquals(1.1, probe.calibrationResidualUm)
        assertEquals(Instant.parse("2026-10-11T13:58:02Z"), probe.calibratedOn)
        assertEquals(3, probe.sampleCount)
        assertEquals(20.0, probe.spreadLimitUm)
        assertEquals("""{"format":1,"profile":7,"complete":true}""", probe.calibrationSession)
        assertEquals("808DACA4E7B2E877C758C45EDEF3353AC37DA862FF17237072BC5E18D4BD3657", probe.sessionDigest)
        assertTrue(probe.metric)

        // The values that the type does not use are cleared.
        val setter = profiles[1]
        assertEquals(50000.0, setter.zOffsetUm)
        assertEquals(0.0, setter.xOffsetUm)
        assertNull(setter.port)
        assertNull(setter.spreadLimitUm)

        val edge = profiles[2]
        assertEquals(ProbeType.EdgeFinder2D, edge.type)
        assertNull(edge.port)
        assertEquals(5080.0, edge.stylusDiameterUm)
        assertEquals(0.0, edge.zOffsetUm)
        assertFalse(edge.metric)
        assertEquals("{}", edge.calibrationSession)

        val unnamed = profiles[3]
        assertEquals('p', unnamed.port)
        assertNull(unnamed.calibrationSession)
        assertNull(unnamed.sessionDigest)
        assertEquals(1, unnamed.sampleCount)

        assertEquals(
            listOf(
                "Row 3: SuperDRO has no probe type “LASER”. The import skips this profile.",
                "Row 4: “r” is not a probe port. The profile gets no port.",
                "Row 4: The calibration session has no digest. The import keeps the session, but it cannot check it.",
                "Row 5: “11” is not a valid value for “sample-count”. The import uses the default value “1”.",
                "Row 5: “s” is not a probe port. A touch probe needs a port, so the profile gets port P.",
                "Row 5: The digest of the calibration session is not correct. The import does not use the session.",
            ),
            preview.rows.flatMap { row -> row.problems.map { it.describe() } },
        )
        val plan = preview.plan()
        assertEquals(listOf("id-1", "id-2", "id-3", "id-4"), plan.add.map(ProbeProfile::id))
        assertTrue(plan.add.all { it.profileId == "p1" })
        assertEquals(listOf("4 probe profiles go to the probe library of this machine profile."), plan.report.probe.map { ImportTexts.English.text(it) })
        assertEquals(listOf("Row 3: SuperDRO has no probe type “LASER”. The import skips this profile."), plan.report.notImported.map { it.describe() })
    }

    @Test
    fun aFileOfAnotherProfileGivesAWarning() {
        val preview = importer.read(Fixtures.bytes("probes-3.32.00.tdprb"), ProbeImportContext("p2", "Mill 2"))
        assertTrue("The profiles come from “Bridgeport”. You import them into “Mill 2”." in preview.problems.map { it.describe() })
    }

    private val own = listOf(
        ProbeProfile(id = "b", profileId = "p1", type = ProbeType.EdgeFinder2D, name = "B edge", port = null, stylusDiameterUm = 6000.0, sampleCount = 1, spreadLimitUm = null),
        ProbeProfile(
            id = "a", profileId = "p1", type = ProbeType.TouchProbe, name = "A probe", port = 'q', stylusDiameterUm = 2000.0, effectiveDiameterUm = 1987.4,
            xOffsetUm = 10.0, yOffsetUm = -5.0, zOffsetUm = 1.5, metric = false, sampleCount = 4, spreadLimitUm = 15.0, calibrationResidualUm = 0.7,
            calibratedOn = Instant.parse("2026-10-01T08:00:00Z"), calibrationSession = "{\"s\":1}", sessionDigest = Sha256.hex("{\"s\":1}"),
        ),
        ProbeProfile(id = "tip", profileId = null, type = ProbeType.EdgeFinder2D, name = "Edge finder 4 mm", port = null, stylusDiameterUm = 4000.0),
        ProbeProfile(id = "other", profileId = "p9", name = "Other machine"),
    )

    @Test
    fun theTdprbExportHasOnlyTheOwnProfilesAndImportsAgainWithNoChange() {
        val file = ProbeProfileFile.exportTdprb("p1", "Bridgeport", own, CONTEXT)
        assertEquals("superdro-probe_profiles-Bridgeport-20261011_143005.tdprb", file.fileName)
        assertFalse("device" in file.text)
        assertFalse("profile-id" in file.text)
        val root = Json.parseToJsonElement(file.text).jsonObject
        assertEquals("probe-profiles", root["touchdro"]!!.jsonPrimitive.content)
        assertEquals(listOf("A probe", "B edge"), root["profiles"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertFalse("input" in root["profiles"]!!.jsonArray[1].jsonObject)

        val again = ProbeProfileImporter(ids = { "new" }, timeZone = TimeZone.UTC).read(file.bytes(), context).plan()
        val expected = ProbeProfileFile.ownProfiles("p1", own).map { it.copy(id = "new") }
        assertEquals(expected, again.add)
    }

    @Test
    fun theSuperDroFileImportsAgainAndUpdatesTheMatchingProfiles() {
        val file = ProbeProfileFile.exportSuperDro("p1", "Bridgeport", own, CONTEXT)
        assertEquals("superdro-probe_profiles-Bridgeport-20261011_143005.json", file.fileName)
        assertFalse("device" in file.text)
        val preview = importer.read(file.text, context.copy(profiles = own.take(2)))
        assertEquals(listOf(ImportOperation.AddAsNew, ImportOperation.UpdateMatching, ImportOperation.ReplaceAll), preview.operations)
        val update = preview.plan(ImportOperation.UpdateMatching)
        assertEquals(ProbeProfileFile.ownProfiles("p1", own), update.update)
        assertTrue(update.add.isEmpty())
        val replace = preview.plan(ImportOperation.ReplaceAll)
        assertEquals(listOf("b", "a"), replace.deleteIds)
        assertEquals(2, replace.add.size)
    }

    @Test
    fun aFileWithNoProfilesOrAnotherKindIsRefused() {
        assertEquals(listOf("The file has no probe profiles."), importer.read("""{"touchdro":"probe-profiles","profiles":[]}""", context).problems.map { it.describe() })
        assertEquals(
            listOf("The file contains “sub-datums”. This import needs “probe-profiles”."),
            importer.read(Fixtures.bytes("subdatums-format2-mill.csv"), context).problems.map { it.describe() },
        )
    }
}
