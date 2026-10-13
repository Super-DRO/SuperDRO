package org.superdro.core.ui.adapter.probe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.DeviceId
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeRoles
import org.superdro.core.model.ProbeType
import org.superdro.core.store.repository.ProbeRepository
import org.superdro.core.ui.adapter.AdapterFixture

/** The probe selector of each function. */
class ProbeSelectorTest {

    private val mill = DeviceId("mill")

    @Test
    fun theListHasTheAcceptedProfilesAndNoProbeSelectedOnlyWithNoSelection() = runTest {
        val fixture = AdapterFixture(this)
        fixture.machineWithAdapter(mill)
        fixture.probes.saveProbeProfile(ProbeProfile(id = "touch", profileId = "mill", type = ProbeType.TouchProbe, name = "Touch", port = 'p'))
        fixture.probes.saveProbeProfile(ProbeProfile(id = "setter", profileId = "mill", type = ProbeType.ToolHeightSetter, name = "Setter", port = null))
        fixture.probes.saveProbeProfile(
            ProbeProfile(id = "used", profileId = "mill", type = ProbeType.TouchProbe, name = "Used", port = 'q', lastUsedOn = Instant.parse("2026-10-01T00:00:00Z")),
        )
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val edge = ProbeSelectorDelegate(fixture.deps, mill, ProbeFunction.EdgeFinder, scope)
        val z = ProbeSelectorDelegate(fixture.deps, mill, ProbeFunction.ZReference, scope)
        advanceUntilIdle()
        assertEquals(
            listOf("Used", "Edge finder 0.100\"", "Edge finder 0.200\"", "Edge finder 0.500\"", "Edge finder 4 mm", "Edge finder 6 mm", "Edge finder 10 mm", "Touch"),
            edge.state.value.options.map(ProbeOption::name),
            "The last used profile first, then by name in the natural order. A tool height setter is not an edge finder.",
        )
        assertEquals(listOf("Used", "Setter", "Touch"), z.state.value.options.map(ProbeOption::name))
        assertTrue(edge.state.value.showNoSelection)
        assertNull(edge.state.value.selectedId)

        edge.select("touch")
        advanceUntilIdle()
        assertEquals("touch", fixture.store.machines.loadProfile("mill")!!.probeRoles.edgeFinderProfileId)
        assertEquals(fixture.store.wallClock.current, fixture.probes.loadProbeProfile("touch")!!.lastUsedOn, "A selection records the time of use.")
        assertEquals("touch", edge.state.value.selectedId)
        assertFalse(edge.state.value.showNoSelection, "\"No probe selected\" shows only with no selection.")
        assertTrue(edge.state.value.canEdit)
        assertEquals("Touch", edge.state.value.options.first().name, "The new use moves the profile to the top.")

        val tip = ProbeRepository.STANDARD_TIP_IDS.first()
        edge.select(tip)
        advanceUntilIdle()
        assertFalse(edge.state.value.canEdit, "A standard tip has no edit button.")
        scope.cancel()
        fixture.close()
    }

    @Test
    fun aNewProfileBecomesTheSelection() = runTest {
        val fixture = AdapterFixture(this)
        fixture.machineWithAdapter(mill)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val z = ProbeSelectorDelegate(fixture.deps, mill, ProbeFunction.ZReference, scope)
        advanceUntilIdle()
        z.requestNewProfile()
        fixture.probes.saveProbeProfile(ProbeProfile(id = "edge", profileId = "mill", type = ProbeType.EdgeFinder2D, name = "Edge", port = null))
        advanceUntilIdle()
        assertNull(fixture.store.machines.loadProfile("mill")!!.probeRoles.zReferenceProfileId, "A profile that the function does not accept is not the selection.")
        fixture.probes.saveProbeProfile(ProbeProfile(id = "setter", profileId = "mill", type = ProbeType.ToolHeightSetter, name = "Setter", port = null))
        advanceUntilIdle()
        assertEquals("setter", fixture.store.machines.loadProfile("mill")!!.probeRoles.zReferenceProfileId)
        assertEquals("setter", z.state.value.selectedId)
        scope.cancel()
        fixture.close()
    }

    @Test
    fun aSelectionThatIsNotInTheLibraryShowsAsNoSelection() = runTest {
        val fixture = AdapterFixture(this)
        fixture.machineWithAdapter(mill) { profile -> profile.copy(probeRoles = ProbeRoles(measurementInstrumentProfileId = "gone")) }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val instrument = ProbeSelectorDelegate(fixture.deps, mill, ProbeFunction.MeasurementInstrument, scope)
        advanceUntilIdle()
        assertNull(instrument.state.value.selectedId)
        assertTrue(instrument.state.value.showNoSelection)
        assertTrue(instrument.state.value.options.isEmpty(), "A standard tip has no port, so it is not a measurement instrument.")
        instrument.select(null)
        advanceUntilIdle()
        assertNull(fixture.store.machines.loadProfile("mill")!!.probeRoles.measurementInstrumentProfileId)
        scope.cancel()
        fixture.close()
    }
}
