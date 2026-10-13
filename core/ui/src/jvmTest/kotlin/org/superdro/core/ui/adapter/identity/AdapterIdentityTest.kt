package org.superdro.core.ui.adapter.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.AdapterIdentity
import org.superdro.core.connection.AdapterInfo
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.IdentityCheck
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.DeclaredInput
import org.superdro.core.model.IdentitySource
import org.superdro.core.protocol.AdapterField
import org.superdro.core.protocol.AdapterValues
import org.superdro.core.protocol.ValueKey
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.adapter.AdapterFixture
import org.superdro.core.ui.adapter.ScriptedSession

/** The adapter identity rules and the "new adapter" prompt. */
class AdapterIdentityTest {

    private val device = DeviceId("mill")
    private val link = AdapterIdentity("24:6f:28:a1:b2:c5", IdentitySource.BtMac)

    @Test
    fun theDecisionTableOfTheIdentityRules() {
        val stored = AdapterProfile(id = "stored", alias = "Stored", identity = link.value, identitySource = IdentitySource.BtMac)
        val other = AdapterProfile(id = "other", alias = "Other", identity = link.value, identitySource = IdentitySource.BtMac)
        val fresh = AdapterProfile(id = "fresh", alias = "Fresh")
        val foreign = AdapterProfile(id = "foreign", alias = "Foreign", identity = "aa:bb:cc:dd:ee:ff", identitySource = IdentitySource.BtMac)

        assertEquals(IdentityDecision.Link(stored), AdapterIdentityRules.decide(link, null, listOf(stored), legacyAdapter = false))
        assertEquals(IdentityDecision.Prompt(PromptReason.NoProfile, emptyList(), false), AdapterIdentityRules.decide(link, null, emptyList(), false))
        assertEquals(IdentityDecision.Prompt(PromptReason.NoProfile, listOf(stored, other), false), AdapterIdentityRules.decide(link, null, listOf(stored, other), false))
        assertEquals(IdentityDecision.StoreIdentity, AdapterIdentityRules.decide(link, fresh, emptyList(), false))
        assertEquals(IdentityDecision.Prompt(PromptReason.Known, listOf(stored), false), AdapterIdentityRules.decide(link, fresh, listOf(stored), false))
        assertEquals(IdentityDecision.None, AdapterIdentityRules.decide(link, stored, listOf(stored), false), "The identity confirms the profile.")
        assertEquals(IdentityDecision.None, AdapterIdentityRules.decide(AdapterIdentity("24:6F:28:A1:B2:C5", IdentitySource.BtMac), stored, listOf(stored), false))
        assertEquals(IdentityDecision.Prompt(PromptReason.Differs, listOf(stored), false), AdapterIdentityRules.decide(link, foreign, listOf(stored), false))
        // A link with no identity yet: wait; a legacy adapter: declare the inputs.
        assertEquals(IdentityDecision.Wait, AdapterIdentityRules.decide(null, fresh, emptyList(), legacyAdapter = false))
        assertEquals(IdentityDecision.Prompt(PromptReason.Legacy, emptyList(), true), AdapterIdentityRules.decide(null, fresh, emptyList(), true))
        assertEquals(IdentityDecision.Prompt(PromptReason.Legacy, emptyList(), false), AdapterIdentityRules.decide(null, null, emptyList(), true))
        assertEquals(IdentityDecision.None, AdapterIdentityRules.decide(null, fresh.copy(legacy = true), emptyList(), true))
        assertEquals(IdentityDecision.None, AdapterIdentityRules.decide(null, stored, emptyList(), true), "No identity on the link: the rules cannot check it.")
    }

    @Test
    fun aLinkWithNoIdentityIsALegacyAdapterAfterTheIdGroupTime() {
        val connected = SessionState.Connected(1_000L)
        val info = AdapterInfo()
        assertFalse(AdapterIdentityRules.legacyAdapter(info, connected, 1_000L + 9_999L))
        assertTrue(AdapterIdentityRules.legacyAdapter(info, connected, 1_000L + 10_000L))
        val fetching = info.copy(values = AdapterValues().fetching(ValueKey.Field(AdapterField.Mac), 9_000L))
        assertFalse(AdapterIdentityRules.legacyAdapter(fetching, connected, 20_000L), "A query of the id group is still on the wire.")
        assertTrue(AdapterIdentityRules.legacyAdapter(info.copy(twoWayCommunication = false), connected, 1_000L), "With two-way communication off, no value comes.")
        assertFalse(AdapterIdentityRules.legacyAdapter(info.copy(identity = link), connected, 20_000L))
        assertFalse(AdapterIdentityRules.legacyAdapter(info, SessionState.NoData, 20_000L))
    }

    @Test
    fun theModeAnswersWriteTheDeclaredInputsAndTheModel() {
        val values = listOf("x000f0002", "y00030002", "z00010001", "w00050004", "u00090008", "v00010001")
            .fold(AdapterValues().withInfo(0x04, "tda-new", 0L)) { all, text -> all.withInfo(0x01, text, 0L) }
        val updated = AdapterIdentityRules.declaredUpdate(AdapterProfile(alias = "A"), AdapterInfo(values = values))
        assertNotNull(updated)
        assertEquals(setOf(DeclaredInput.X, DeclaredInput.Y, DeclaredInput.W, DeclaredInput.Probe, DeclaredInput.Tach), updated.declaredInputs)
        assertEquals("tda-new", updated.model)
        assertNull(AdapterIdentityRules.declaredUpdate(updated, AdapterInfo(values = values)), "No change: no write.")
        assertNull(AdapterIdentityRules.declaredUpdate(AdapterProfile(alias = "A"), AdapterInfo(values = values, twoWayCommunication = false)))
    }

    private class Setup(val fixture: AdapterFixture, val session: ScriptedSession, val presenter: AdapterIdentityPresenter)

    private fun identityTest(
        tab: AdapterProfile?,
        stored: List<AdapterProfile> = emptyList(),
        info: AdapterInfo = AdapterInfo(identity = link),
        state: SessionState = SessionState.Connected(0L),
        body: suspend TestScope.(Setup) -> Unit,
    ) = runTest {
        val fixture = AdapterFixture(this)
        stored.forEach { profile -> fixture.store.adapters.saveAdapterProfile(profile) }
        if (tab == null) fixture.machineWithoutAdapter(device) else fixture.machineWithAdapter(device, tab)
        val session = fixture.connect(ScriptedSession(device, initialState = state))
        session.adapterInfo.value = info
        val presenter = AdapterIdentityPresenter(device, fixture.deps)
        try {
            runCurrent()
            body(Setup(fixture, session, presenter))
        } finally {
            presenter.close()
            fixture.close()
        }
    }

    private suspend fun Setup.machineAdapterId(): String? = fixture.store.machines.loadProfile(device.value)?.adapterProfileId

    @Test
    fun aMachineWithNoAdapterProfileUsesTheOneStoredProfileWithTheIdentity() = identityTest(
        tab = null,
        stored = listOf(AdapterProfile(id = "known", alias = "Known", identity = "24:6f:28:a1:b2:c5", identitySource = IdentitySource.BtMac)),
    ) { setup ->
        runCurrent()
        assertEquals("known", setup.machineAdapterId())
        assertNull(setup.presenter.prompt.value)
    }

    @Test
    fun aNewAdapterProfileGetsTheIdentityOfTheLinkWithNoPrompt() = identityTest(
        tab = AdapterProfile(id = "fresh", alias = "Fresh"),
        info = AdapterInfo(identity = link, values = AdapterValues().withInfo(0x04, "tda-x", 0L)),
    ) { setup ->
        runCurrent()
        val stored = setup.fixture.store.adapters.loadAdapterProfile("fresh")!!
        assertEquals("24:6f:28:a1:b2:c5", stored.identity)
        assertEquals(IdentitySource.BtMac, stored.identitySource)
        assertEquals("tda-x", stored.model)
        assertNull(setup.presenter.prompt.value)
    }

    @Test
    fun aMachineWithNoAdapterProfileAndAnUnknownIdentityGetsThePromptAndCreatesTheProfile() = identityTest(tab = null) { setup ->
        runCurrent()
        val prompt = assertNotNull(setup.presenter.prompt.value)
        assertEquals(PromptReason.NoProfile, prompt.reason)
        assertEquals("24:6f:28:a1:b2:c5", prompt.identity)
        assertTrue(setup.presenter.needsPush(prompt.id))
        setup.presenter.onShown(prompt.id)
        assertFalse(setup.presenter.needsPush(prompt.id))
        setup.presenter.onName("Bridgeport adapter")
        setup.presenter.onConfirm()
        runCurrent()
        assertNull(setup.presenter.prompt.value, "The prompt closes after the write.")
        val id = assertNotNull(setup.machineAdapterId())
        val created = setup.fixture.store.adapters.loadAdapterProfile(id)!!
        assertEquals("Bridgeport adapter", created.alias)
        assertEquals("24:6f:28:a1:b2:c5", created.identity)
        assertFalse(created.legacy)
    }

    @Test
    fun anIdentityThatDiffersOpensThePromptOneTimeForTheLink() = identityTest(
        tab = AdapterProfile(id = "tab", alias = "Tab adapter", identity = "aa:bb:cc:dd:ee:ff", identitySource = IdentitySource.BtMac),
        info = AdapterInfo(identity = link, identityCheck = IdentityCheck.Differs("aa:bb:cc:dd:ee:ff", link)),
    ) { setup ->
        runCurrent()
        val prompt = assertNotNull(setup.presenter.prompt.value)
        assertEquals(PromptReason.Differs, prompt.reason)
        assertEquals("Tab adapter", prompt.currentAlias)
        setup.presenter.onDismiss()
        setup.session.adapterInfo.value = setup.session.adapterInfo.value.copy(values = AdapterValues().withInfo(0x04, "tda-y", 1L))
        runCurrent()
        assertNull(setup.presenter.prompt.value, "A dismissed prompt does not show again for this link.")
        assertEquals("tab", setup.machineAdapterId())
        // A new link (a new Connected period) checks again.
        setup.session.state.value = SessionState.Connected(50_000L)
        runCurrent()
        assertNotNull(setup.presenter.prompt.value)
    }

    @Test
    fun theUserCanSelectAStoredProfileWithTheIdentity() = identityTest(
        tab = AdapterProfile(id = "fresh", alias = "Fresh"),
        stored = listOf(AdapterProfile(id = "known", alias = "Known", identity = "24:6f:28:a1:b2:c5", identitySource = IdentitySource.BtMac)),
    ) { setup ->
        runCurrent()
        val prompt = assertNotNull(setup.presenter.prompt.value)
        assertEquals(PromptReason.Known, prompt.reason)
        assertEquals(listOf("known" to "Known"), prompt.matches)
        setup.presenter.onUse("known")
        setup.presenter.onConfirm()
        runCurrent()
        assertEquals("known", setup.machineAdapterId())
    }

    @Test
    fun keepTheCurrentProfileStoresTheIdentityInIt() = identityTest(
        tab = AdapterProfile(id = "fresh", alias = "Fresh"),
        stored = listOf(AdapterProfile(id = "known", alias = "Known", identity = "24:6f:28:a1:b2:c5", identitySource = IdentitySource.BtMac)),
    ) { setup ->
        runCurrent()
        setup.presenter.onUse(null)
        setup.presenter.onConfirm()
        runCurrent()
        assertEquals("fresh", setup.machineAdapterId())
        assertEquals("24:6f:28:a1:b2:c5", setup.fixture.store.adapters.loadAdapterProfile("fresh")!!.identity)
    }

    @Test
    fun aLegacyAdapterGetsThePromptWithTheDeclaredInputs() = identityTest(
        tab = AdapterProfile(id = "fresh", alias = "Fresh"),
        info = AdapterInfo(),
    ) { setup ->
        runCurrent()
        assertNull(setup.presenter.prompt.value, "The rules wait for the id group.")
        advanceTimeBy(AdapterIdentityRules.LEGACY_WAIT_MS + 1L)
        runCurrent()
        val prompt = assertNotNull(setup.presenter.prompt.value)
        assertEquals(PromptReason.Legacy, prompt.reason)
        assertTrue(prompt.legacy)
        assertTrue(prompt.updateCurrent)
        assertEquals("Fresh", prompt.name)
        setup.presenter.onToggleDeclared(DeclaredInput.X)
        setup.presenter.onToggleDeclared(DeclaredInput.Z)
        setup.presenter.onToggleDeclared(DeclaredInput.Probe)
        setup.presenter.onToggleDeclared(DeclaredInput.Z)
        setup.presenter.onConfirm()
        runCurrent()
        val stored = setup.fixture.store.adapters.loadAdapterProfile("fresh")!!
        assertTrue(stored.legacy)
        assertEquals(setOf(DeclaredInput.X, DeclaredInput.Probe), stored.declaredInputs)
    }

    @Test
    fun disconnectPausesTheMachine() = identityTest(tab = null) { setup ->
        runCurrent()
        assertNotNull(setup.presenter.prompt.value)
        setup.presenter.onDisconnect()
        runCurrent()
        assertNull(setup.presenter.prompt.value)
        assertEquals(listOf("disconnect mill"), setup.fixture.connections.calls)
    }

    @Test
    fun aNameThatExistsShowsTheRefusalOfTheStore() = identityTest(
        tab = null,
        stored = listOf(AdapterProfile(id = "other", alias = "Shop", identity = "11:22:33:44:55:66", identitySource = IdentitySource.BtMac)),
    ) { setup ->
        runCurrent()
        setup.presenter.onName("Shop")
        setup.presenter.onConfirm()
        runCurrent()
        val prompt = assertNotNull(setup.presenter.prompt.value)
        assertNotNull(prompt.nameError)
        assertFalse(prompt.busy)
        assertNull(setup.machineAdapterId())
    }

    @Test
    fun theManualPromptOfTheDetailsScreen() = identityTest(tab = null, info = AdapterInfo(identity = link), state = SessionState.NoData) { setup ->
        runCurrent()
        assertNull(setup.presenter.prompt.value, "Not Connected: no automatic prompt.")
        setup.presenter.openManual()
        runCurrent()
        assertIs<NewAdapterPromptUi>(setup.presenter.prompt.value)
        assertEquals(PromptReason.NoProfile, setup.presenter.prompt.value!!.reason)
    }

    @Test
    fun theDeclaredInputsComeFromTheModeAnswers() = identityTest(
        tab = AdapterProfile(id = "tab", alias = "Tab", identity = "24:6f:28:a1:b2:c5", identitySource = IdentitySource.BtMac),
        info = AdapterInfo(
            identity = link,
            identityCheck = IdentityCheck.Confirmed,
            values = listOf("x000f0002", "y00030002", "z00030002", "w00010001", "u00010001", "v00050004")
                .fold(AdapterValues().withInfo(0x04, "tda-z", 0L)) { all, text -> all.withInfo(0x01, text, 0L) },
        ),
    ) { setup ->
        runCurrent()
        val stored = setup.fixture.store.adapters.loadAdapterProfile("tab")!!
        assertEquals(setOf(DeclaredInput.X, DeclaredInput.Y, DeclaredInput.Z, DeclaredInput.Probe, DeclaredInput.Tach), stored.declaredInputs, "x supports Digital Probe, v supports Tachometer.")
        assertEquals("tda-z", stored.model)
        assertNotNull(stored.lastSeenOn, "A confirmed identity sets the last seen time.")
        assertNull(setup.presenter.prompt.value)
        assertEquals(1, setup.fixture.store.adapters.adapterProfiles().first().size)
        assertNotNull(setup.fixture.store.adapters.saveAdapterProfile(stored).valueOrNull())
    }
}
