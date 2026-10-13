package org.superdro.core.ui.adapter.probe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import org.superdro.core.connection.DeviceId
import org.superdro.core.functions.tipcal.TouchBeep
import org.superdro.core.model.AppSettings
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeRoles
import org.superdro.core.model.ProbeType
import org.superdro.core.ui.adapter.RecordingSounds
import org.superdro.core.ui.sound.SoundController
import org.superdro.core.ui.sound.UiSound
import org.superdro.core.ui.tabs.english

/** The pure rules of the probe area: the functions, the fields of each type, the lengths, and the save rules. */
class ProbeRulesTest {

    private fun probe(type: ProbeType, port: Char? = null, name: String = type.name, standard: Boolean = false) =
        ProbeProfile(id = name, type = type, name = name, port = port, standardTip = standard)

    @Test
    fun eachFunctionAcceptsTheTypesOfTheTypeTable() {
        val touch = probe(ProbeType.TouchProbe, 'p')
        val edge = probe(ProbeType.EdgeFinder2D)
        val edgeWithPort = probe(ProbeType.EdgeFinder2D, 'q')
        val setter = probe(ProbeType.ToolHeightSetter, 'p')
        val camera = probe(ProbeType.Camera)
        assertEquals(listOf(touch, edge, edgeWithPort, camera), listOf(touch, edge, edgeWithPort, setter, camera).filter(ProbeFunction.EdgeFinder::accepts))
        assertEquals(listOf(touch, setter), listOf(touch, edge, edgeWithPort, setter, camera).filter(ProbeFunction.ZReference::accepts))
        assertEquals(
            listOf(touch, edgeWithPort, camera),
            listOf(touch, edge, edgeWithPort, setter, camera).filter(ProbeFunction.MeasurementInstrument::accepts),
            "The measurement instrument needs X and Y offsets and a port, or a camera.",
        )
    }

    @Test
    fun aSelectionWritesOnlyTheRoleOfItsFunction() {
        val roles = ProbeRoles(edgeFinderProfileId = "a", zReferenceProfileId = "b", measurementInstrumentProfileId = "c")
        assertEquals(ProbeRoles("x", "b", "c"), ProbeFunction.EdgeFinder.withSelection(roles, "x"))
        assertEquals(ProbeRoles("a", null, "c"), ProbeFunction.ZReference.withSelection(roles, null))
        assertEquals("c", ProbeFunction.MeasurementInstrument.selected(roles))
    }

    @Test
    fun eachTypeHasItsOwnFields() {
        assertEquals(listOf<Char?>('p', 'q'), ProbeTypeRules.ports(ProbeType.TouchProbe), "A touch probe has no None.")
        assertEquals(listOf<Char?>(null, 'p', 'q'), ProbeTypeRules.ports(ProbeType.EdgeFinder2D))
        assertEquals(emptyList(), ProbeTypeRules.ports(ProbeType.Camera), "A camera has no trigger port.")
        assertEquals('p', ProbeTypeRules.fixPort(ProbeType.TouchProbe, null), "A touch probe gets the default port P.")
        assertNull(ProbeTypeRules.fixPort(ProbeType.Camera, 'q'))
        assertEquals('q', ProbeTypeRules.fixPort(ProbeType.ToolHeightSetter, 'Q'))
        assertTrue(ProbeTypeRules.usesDiameter(ProbeType.EdgeFinder2D))
        assertFalse(ProbeTypeRules.usesDiameter(ProbeType.ToolHeightSetter))
        assertFalse(ProbeTypeRules.usesXyOffsets(ProbeType.ToolHeightSetter))
        assertTrue(ProbeTypeRules.usesZOffset(ProbeType.ToolHeightSetter))
        assertFalse(ProbeTypeRules.usesZOffset(ProbeType.Camera))
        assertTrue(ProbeTypeRules.canCalibrate(ProbeType.TouchProbe, 'p'))
        assertFalse(ProbeTypeRules.canCalibrate(ProbeType.EdgeFinder2D, null), "The calibration needs a port.")
        assertFalse(ProbeTypeRules.canCalibrate(ProbeType.Camera, null))
    }

    @Test
    fun theLengthsUseTheUnitsOfTheProfile() {
        assertEquals("0.1000", ProbeLengths.text(2540.0, metric = false), "The standard tip 0.100 in.")
        assertEquals("4.000", ProbeLengths.text(4000.0, metric = true), "The standard tip 4 mm.")
        assertEquals(12700.0, ProbeLengths.parseUm("0.5", metric = false))
        assertEquals(1987.4, ProbeLengths.parseUm("1,9874", metric = true)!!, 1e-9)
        assertNull(ProbeLengths.parseUm("abc", metric = true))
        assertNull(ProbeLengths.parseUm("", metric = true))
        assertEquals("0.2000", ProbeLengths.convertText("5.08", fromMetric = true, toMetric = false))
        assertEquals("x", ProbeLengths.convertText("x", fromMetric = true, toMetric = false))
    }

    @Test
    fun theSelectorShowsTheLastUsedProfileFirstThenTheNames() {
        val old = probe(ProbeType.TouchProbe, 'p', "Old").copy(lastUsedOn = Instant.parse("2026-10-01T00:00:00Z"))
        val new = probe(ProbeType.TouchProbe, 'p', "New").copy(lastUsedOn = Instant.parse("2026-10-10T00:00:00Z"))
        val alpha = probe(ProbeType.TouchProbe, 'p', "alpha")
        val beta = probe(ProbeType.TouchProbe, 'p', "Beta")
        assertEquals(listOf("New", "Old", "alpha", "Beta"), listOf(beta, old, alpha, new).sortedForSelector().map(ProbeProfile::name))
    }

    @Test
    fun theAutomaticNameHasTheTypeAndTheDiameter() {
        val form = ProbeProfileForm(type = ProbeType.TouchProbe, metric = true, diameter = "2")
        assertEquals("Touch probe 2.000 mm", ProbeProfileSave.autoName(form, "Touch probe", "mm"))
        assertEquals("Touch probe", ProbeProfileSave.autoName(form.copy(diameter = ""), "Touch probe", "mm"))
        assertEquals("Tool height setter", ProbeProfileSave.autoName(form.copy(type = ProbeType.ToolHeightSetter), "Tool height setter", "mm"))
        assertEquals("2D edge finder 0.2000 in", ProbeProfileSave.autoName(ProbeProfileForm(type = ProbeType.EdgeFinder2D, metric = false, diameter = "0.2"), "2D edge finder", "in"))
    }

    @Test
    fun theSaveClearsTheValuesThatTheTypeDoesNotUse() {
        val setter = ProbeProfileForm(
            type = ProbeType.ToolHeightSetter,
            metric = true,
            port = null,
            diameter = "6",
            sampleCount = "5",
            maxSpread = "0.05",
            xOffset = "1",
            yOffset = "2",
            zOffset = "50",
        )
        val result = assertIs<ProbeSaveResult.Valid>(ProbeProfileSave.build(setter, null, "mill", "Tool height setter", "new-id")).profile
        assertEquals(0.0, result.stylusDiameterUm, "Rule 2: the setter has no diameter.")
        assertEquals(0.0, result.xOffsetUm)
        assertEquals(0.0, result.yOffsetUm)
        assertEquals(50000.0, result.zOffsetUm, "The setter height in µm.")
        assertEquals(ProbeProfile.DEFAULT_SAMPLE_COUNT, result.sampleCount, "Rule 3: no port gives the default sample count.")
        assertNull(result.spreadLimitUm, "Rule 3: no port clears the spread limit.")
        assertEquals("mill", result.profileId, "Rule 5: a new profile belongs to the machine profile.")
        assertEquals("Tool height setter", result.name)
        assertEquals("new-id", result.id)

        val camera = ProbeProfileForm(type = ProbeType.Camera, metric = false, port = 'p', xOffset = "1", zOffset = "3")
        val cameraProfile = assertIs<ProbeSaveResult.Valid>(ProbeProfileSave.build(camera, null, null, "Camera", "c")).profile
        assertNull(cameraProfile.port, "A camera has no port.")
        assertEquals(25400.0, cameraProfile.xOffsetUm)
        assertEquals(0.0, cameraProfile.zOffsetUm, "A camera has no Z offset.")
        assertNull(cameraProfile.profileId, "No tab: a profile for all machine profiles.")
    }

    @Test
    fun theSaveKeepsTheCalibrationValues() {
        val stored = ProbeProfile(
            id = "p1",
            profileId = "mill",
            type = ProbeType.TouchProbe,
            name = "Probe",
            port = 'q',
            stylusDiameterUm = 1987.4,
            effectiveDiameterUm = 1987.4,
            calibrationResidualUm = 1.1,
            calibratedOn = Instant.parse("2026-10-11T13:58:02Z"),
            calibrationSession = null,
            sessionDigest = "abc",
        )
        val form = ProbeProfileForm.of(stored).copy(sampleCount = "3", maxSpread = "")
        val saved = assertIs<ProbeSaveResult.Valid>(ProbeProfileSave.build(form, stored, "other", "x", "unused")).profile
        assertEquals(1987.4, saved.effectiveDiameterUm, "Rule 4: the effective diameter stays.")
        assertEquals(1.1, saved.calibrationResidualUm)
        assertEquals(stored.calibratedOn, saved.calibratedOn)
        assertNull(saved.sessionDigest, "Rule 4: a digest with no session does not stay.")
        assertEquals("mill", saved.profileId, "A saved profile keeps its machine profile.")
        assertEquals("p1", saved.id)
        assertEquals(3, saved.sampleCount)
        assertNull(saved.spreadLimitUm, "An empty spread field gives the default rule.")
        assertEquals('q', saved.port)
    }

    @Test
    fun theSaveRefusesTheFieldsThatAreNotValid() {
        val form = ProbeProfileForm(type = ProbeType.TouchProbe, port = 'p', diameter = "", sampleCount = "11", maxSpread = "-1", xOffset = "a")
        val errors = assertIs<ProbeSaveResult.Invalid>(ProbeProfileSave.build(form, null, "mill", "x", "id")).errors
        assertEquals("Enter the tip diameter.", errors.getValue(ProbeField.Diameter).english())
        assertEquals("Enter a whole number from 1 to 10.", errors.getValue(ProbeField.SampleCount).english())
        assertEquals("Enter a number of 0 or more.", errors.getValue(ProbeField.MaxSpread).english())
        assertEquals("Enter a number.", errors.getValue(ProbeField.XOffset).english())
        val negative = assertIs<ProbeSaveResult.Invalid>(ProbeProfileSave.build(form.copy(diameter = "-2", sampleCount = "", maxSpread = "", xOffset = ""), null, null, "x", "id"))
        assertEquals(setOf(ProbeField.Diameter), negative.errors.keys)
        val name = assertIs<ProbeSaveResult.Valid>(ProbeProfileSave.build(form.copy(name = "N".repeat(60), diameter = "2", sampleCount = "", maxSpread = "", xOffset = ""), null, null, "x", "id"))
        assertEquals(50, name.profile.name.length, "The name has a maximum of 50 characters.")
    }

    @Test
    fun theProbeSoundsUseTheShortTheLongAndTheErrorSound() {
        val recorded = RecordingSounds()
        val sounds = ProbeSounds(SoundController(recorded, MutableStateFlow(AppSettings()), MutableStateFlow(emptySet()), { 0L }), DeviceId("mill"))
        sounds.sample(last = false)
        sounds.sample(last = true)
        sounds.refused()
        sounds.touch(TouchBeep.ShortBeep)
        sounds.touch(TouchBeep.LongBeep)
        assertEquals(listOf(UiSound.Short, UiSound.Long, UiSound.Error, UiSound.Short, UiSound.Long), recorded.played)
        val muted = RecordingSounds()
        ProbeSounds(SoundController(muted, MutableStateFlow(AppSettings(muteAllSounds = true)), MutableStateFlow(emptySet()), { 0L }), null).sample(true)
        assertEquals(emptyList(), muted.played, "The mute switch stops the probe sounds.")
    }
}
