package org.superdro.core.functions.measurement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant
import org.superdro.core.model.CollectedPoint
import org.superdro.core.model.LogDisplayFormat
import org.superdro.core.model.LoggedResult
import org.superdro.core.model.MeasurementLogEntry
import org.superdro.core.model.PointSource

class MeasurementLogTest {

    private val createdOn = Instant.parse("2026-10-11T21:25:30Z")
    private val mm3 = LogDisplayFormat(metric = true, precision = 3)

    private fun latch(x: Double, y: Double) =
        CollectedPoint(x = x, y = y, z = -1250.0, source = PointSource.Latch)

    private fun circleSession(note: String = "Bore A"): MeasurementSession =
        MeasurementSession.open(MeasurementVerb.Circle, 20.0)
            .capture(latch(0.0, 0.0)).session
            .capture(latch(40000.0, 0.0)).session
            .capture(latch(0.0, 30000.0)).session
            .withNote(note)

    private fun circleEntry(note: String = "Bore A"): MeasurementLogEntry = MeasurementLog.buildEntry(
        session = circleSession(note),
        title = "Circle from 3 points",
        workspaceId = "w1",
        workspaceName = "Default",
        sequence = 1,
        display = mm3,
        axisAlignmentArcSec = 0.0,
        createdOn = createdOn,
        id = "e1",
    )!!

    private val examplePayload =
        """{"v":1,"verb":"circle_from_3_points","title":"Circle from 3 points","units":"mm","precision":3,""" +
            """"workspace":"Default","axisAngle":0,""" +
            """"results":[{"key":"center","value":{"x":20000,"y":15000}},{"key":"diameter","value":50000}],""" +
            """"points":[{"x":0,"y":0,"z":-1250,"source":"LATCH","kind":"SURFACE_POINT","frame":{"xOffset":0,"yOffset":0}},""" +
            """{"x":40000,"y":0,"z":-1250,"source":"LATCH","kind":"SURFACE_POINT","frame":{"xOffset":0,"yOffset":0}},""" +
            """{"x":0,"y":30000,"z":-1250,"source":"LATCH","kind":"SURFACE_POINT","frame":{"xOffset":0,"yOffset":0}}],""" +
            """"note":"Bore A"}"""

    // The sequence starts at 1 in each workspace
    @Test
    fun theSequenceIsTheLargestSequencePlusOne() {
        assertEquals(1, MeasurementLog.nextSequence(emptyList()))
        assertEquals(6, MeasurementLog.nextSequence(listOf(1, 5, 2)))
    }

    @Test
    fun theEntryHoldsTheResultsWithAValueThePointsAndTheStoredFormat() {
        val entry = circleEntry(note = "  Bore A  ")
        assertEquals("circle_from_3_points", entry.verb)
        assertEquals(
            listOf(LoggedResult(key = "center", x = 20000.0, y = 15000.0), LoggedResult(key = "diameter", value = 50000.0)),
            entry.results,
        )
        assertEquals(3, entry.points.size)
        assertEquals("Bore A", entry.note)
        assertEquals(mm3, entry.display)
        assertEquals(1, entry.sequence)
        assertEquals("Default", entry.workspaceName)
        assertNull(circleEntry(note = "   ").note)
    }

    @Test
    fun theEntryHasTheUnionOfTheNotesAndTheAlignmentInDegrees() {
        val probe = CollectedPoint(x = 0.0, y = 0.0, source = PointSource.Probe, tipRadiusUm = 1000.0, approachX = 1)
        val session = MeasurementSession.open(MeasurementVerb.Distance, 20.0)
            .capture(probe, FrameAngles()).session
            .capture(probe.copy(x = 30000.0, y = 40000.0), FrameAngles(alignmentArcSec = 3.0)).session
        val entry = MeasurementLog.buildEntry(session, "Point-to-Point Distance", "w1", "Default", 2, mm3, 3600.0, createdOn)!!
        assertEquals(listOf("UNCOMPENSATED", "FRAME_CHANGED"), entry.notes)
        assertEquals(1.0, entry.axisAngle, 1e-12)
        assertEquals(listOf("distance", "dx", "dy", "midpoint"), entry.results.map { result -> result.key })
    }

    @Test
    fun aSetThatCannotBeLoggedGivesNoEntry() {
        val open = MeasurementSession.open(MeasurementVerb.Circle, 20.0)
        assertNull(MeasurementLog.buildEntry(open, "Circle from 3 points", "w1", "Default", 1, mm3, 0.0, createdOn))
        val logged = circleSession().markLogged(1)
        assertNull(MeasurementLog.buildEntry(logged, "Circle from 3 points", "w1", "Default", 2, mm3, 0.0, createdOn))
    }

    // The example payload
    @Test
    fun theCircleEntryGivesTheExamplePayload() {
        assertEquals(examplePayload, MeasurementLogPayload.encode(circleEntry()))
    }

    @Test
    fun theExamplePayloadDecodesToTheEntry() {
        val decoded = MeasurementLogPayload.decode(examplePayload, id = "e1", workspaceId = "w1", sequence = 1, createdOn = createdOn)
        assertEquals(circleEntry(), decoded)
    }

    @Test
    fun aProbeEntryMakesARoundTrip() {
        val entry = MeasurementLogEntry(
            id = "e2",
            workspaceId = "w1",
            sequence = 2,
            verb = "distance_2_points",
            title = "Point-to-Point Distance",
            results = listOf(LoggedResult(key = "distance", value = 50000.25), LoggedResult(key = "midpoint", x = 15000.0, y = -20000.5)),
            points = listOf(
                CollectedPoint(
                    x = 0.5,
                    y = 0.0,
                    z = 12.0,
                    source = PointSource.Probe,
                    tipRadiusUm = 1000.0,
                    approachX = -1,
                    approachY = 1,
                    approachZ = 0,
                    instrumentId = "probe-7",
                    instrumentXOffsetUm = 12.5,
                    instrumentYOffsetUm = -3.0,
                    notes = listOf("UNCOMPENSATED"),
                ),
                CollectedPoint(x = 30000.0, y = 40000.0, source = PointSource.SubDatum, sourceId = "s9"),
            ),
            notes = listOf("UNCOMPENSATED"),
            note = null,
            workspaceName = "Shop \"A\"",
            display = LogDisplayFormat(metric = false, precision = 4),
            axisAngle = -1.25,
            createdOn = createdOn,
        )
        val payload = MeasurementLogPayload.encode(entry)
        assertEquals(entry, MeasurementLogPayload.decode(payload, "e2", "w1", 2, createdOn))
        val json = kotlinx.serialization.json.Json.parseToJsonElement(payload).toString()
        assertEquals(true, json.contains(""""systemNotes":["UNCOMPENSATED"]"""))
        assertEquals(false, json.contains(""""note""""))
        assertEquals(true, json.contains(""""kind":"TIP_CENTER""""))
        assertEquals(true, json.contains(""""units":"in""""))
    }

    @Test
    fun aPayloadThatCannotBeReadGivesNull() {
        assertNull(MeasurementLogPayload.decode("not json"))
        assertNull(MeasurementLogPayload.decode("[1, 2]"))
        assertNull(MeasurementLogPayload.decode("""{"v":1}"""))
        val decoded = assertNotNull(
            MeasurementLogPayload.decode(
                """{"v":1,"verb":"point","future":true,"results":[{"key":"unknown","value":1},{"key":"position","value":{"x":1,"y":2}}],"points":[{"x":1,"y":2,"source":"LATCH"}]}""",
            ),
        )
        assertEquals(listOf(LoggedResult(key = "position", x = 1.0, y = 2.0)), decoded.results)
        assertEquals(1, decoded.points.size)
    }
}
