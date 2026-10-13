package org.superdro.core.ui.adapter.list

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.DeclaredInput
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.IdentitySource
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.ProbeContactType
import org.superdro.core.ui.adapter.AdapterFixture
import org.superdro.core.ui.adapter.ScriptedSession
import org.superdro.core.ui.tabs.english

/** The adapters list and the offline adapter details. */
class AdaptersPresenterTest {

    private suspend fun TestScope.stored(fixture: AdapterFixture) {
        fixture.store.adapters.saveAdapterProfile(
            AdapterProfile(
                id = "shop",
                alias = "Shop adapter",
                identity = "24:6f:28:a1:b2:c5",
                identitySource = IdentitySource.BtMac,
                lastSeenOn = Instant.parse("2026-10-11T14:30:00Z"),
            ),
        )
        fixture.store.adapters.saveAdapterProfile(AdapterProfile(id = "old", alias = "Old adapter", legacy = true))
        fixture.store.adapters.saveAdapterProfile(AdapterProfile(id = "spare", alias = "Spare"))
        fixture.store.machines.createProfile(MachineProfile.create(MachineType.VerticalMill, "Bridgeport").copy(id = "mill", adapterProfileId = "shop"))
        fixture.store.machines.createProfile(MachineProfile.create(MachineType.Lathe, "Lathe").copy(id = "lathe", adapterProfileId = "old"))
        runCurrent()
    }

    @Test
    fun eachRowShowsTheStateTheUsersTheIdentityAndTheLastSeenTime() = runTest {
        val fixture = AdapterFixture(this)
        stored(fixture)
        fixture.connect(ScriptedSession(DeviceId("mill")))
        val presenter = AdaptersPresenter(fixture.deps, selectedDevice = DeviceId("lathe"))
        try {
            runCurrent()
            val rows = presenter.uiState.value.rows.associateBy { row -> row.id }
            assertEquals(listOf("Old adapter", "Shop adapter", "Spare"), presenter.uiState.value.rows.map { row -> row.alias })
            assertEquals(
                listOf("● Connected", "Used by: Bridgeport", "Identified (…:b2:c5)", "Last seen: 2026-10-11 14:30"),
                rows.getValue("shop").parts.map { part -> part.english() },
            )
            assertEquals(listOf("★ This machine profile", "Used by: Lathe", "Legacy"), rows.getValue("old").parts.map { part -> part.english() })
            assertEquals(listOf("Not identified"), rows.getValue("spare").parts.map { part -> part.english() })
            assertEquals(DeviceId("mill"), rows.getValue("shop").connectedDevice)
            assertEquals(AdapterRowTarget.Offline("shop"), presenter.targetOf(rows.getValue("shop")), "The connected tab is not the selected tab.")
            assertEquals(AdapterRowTarget.Offline("old"), presenter.targetOf(rows.getValue("old")))
        } finally {
            presenter.close()
            fixture.close()
        }
    }

    @Test
    fun theConnectedRowOfTheSelectedTabOpensTheAdapterDetails() = runTest {
        val fixture = AdapterFixture(this)
        stored(fixture)
        val session = fixture.connect(ScriptedSession(DeviceId("mill")))
        val presenter = AdaptersPresenter(fixture.deps, selectedDevice = DeviceId("mill"))
        try {
            runCurrent()
            val shop = presenter.uiState.value.rows.first { row -> row.id == "shop" }
            assertEquals(AdapterRowTarget.Online(DeviceId("mill")), presenter.targetOf(shop))
            session.state.value = SessionState.Reconnecting(1, 0L, org.superdro.core.connection.LinkFailure.Lost)
            runCurrent()
            val offline = presenter.uiState.value.rows.first { row -> row.id == "shop" }
            assertNull(offline.connectedDevice)
            assertEquals("★ This machine profile", offline.parts.first().english())
        } finally {
            presenter.close()
            fixture.close()
        }
    }

    @Test
    fun createNewAdapterProfileWritesALegacyProfileWithTheDeclaredInputs() = runTest {
        val fixture = AdapterFixture(this)
        val presenter = AdaptersPresenter(fixture.deps, selectedDevice = null)
        try {
            runCurrent()
            assertTrue(presenter.uiState.value.rows.isEmpty())
            presenter.onCreate()
            runCurrent()
            assertEquals("Legacy adapter", presenter.uiState.value.create?.name)
            presenter.onCreateName("Caliper box")
            presenter.onCreateToggle(DeclaredInput.X)
            presenter.onCreateToggle(DeclaredInput.Tach)
            presenter.onCreateConfirm()
            runCurrent()
            assertNull(presenter.uiState.value.create)
            val created = fixture.store.adapters.adapterProfiles().first().single()
            assertEquals("Caliper box", created.alias)
            assertTrue(created.legacy)
            assertEquals(setOf(DeclaredInput.X, DeclaredInput.Tach), created.declaredInputs)
            // A blank name shows the refusal of the store.
            presenter.onCreate()
            runCurrent()
            presenter.onCreateName(" ")
            presenter.onCreateConfirm()
            runCurrent()
            assertEquals("Adapter name can't be blank.", presenter.uiState.value.create?.error?.english())
        } finally {
            presenter.close()
            fixture.close()
        }
    }

    @Test
    fun theOfflineDetailsShowTheProfileAndTheInputRows() = runTest {
        val fixture = AdapterFixture(this)
        val encoders = AdapterProfile().encoders +
            ('w' to EncoderProfile.defaultFor('w', EncoderKind.Angular)) +
            ('t' to EncoderProfile.defaultFor('t').copy(resolution = 4.0))
        fixture.store.adapters.saveAdapterProfile(
            AdapterProfile(id = "shop", alias = "Shop adapter", model = "tda-example", identity = "24:6f:28:a1:b2:c5", encoders = encoders, probeContactType = ProbeContactType.NormallyClosed, probeDebounceMs = 20L),
        )
        val presenter = OfflineAdapterPresenter("shop", fixture.deps)
        try {
            runCurrent()
            val state = presenter.uiState.value
            assertEquals("Shop adapter", state.alias)
            assertEquals("tda-example", state.model)
            assertEquals("24:6f:28:a1:b2:c5", state.bluetoothId)
            assertEquals(listOf("x", "y", "z", "w", "u", "v", "t", "probe"), state.rows.map { row -> row.key })
            assertEquals(listOf("Linear scale", "25400 counts per inch", "Not calibrated"), state.rows[0].summary.map { it.english() })
            assertEquals(listOf("Rotary encoder", "1000 counts per revolution", "Not calibrated"), state.rows[3].summary.map { it.english() })
            assertEquals(listOf("4 pulses per revolution"), state.rows[6].summary.map { it.english() })
            assertEquals(OfflineRowTarget.Tach, state.rows[6].target)
            assertEquals(listOf("Normally closed, debounce 20 ms"), state.rows[7].summary.map { it.english() })
            assertEquals(OfflineRowTarget.Encoder('u'), state.rows[4].target)
        } finally {
            presenter.close()
            fixture.close()
        }
    }

    @Test
    fun theOfflineNameEditKeepsTheRulesOfTheStore() = runTest {
        val fixture = AdapterFixture(this)
        fixture.store.adapters.saveAdapterProfile(AdapterProfile(id = "shop", alias = "Shop adapter"))
        fixture.store.adapters.saveAdapterProfile(AdapterProfile(id = "other", alias = "Other"))
        val presenter = OfflineAdapterPresenter("shop", fixture.deps)
        try {
            runCurrent()
            presenter.onEditAlias()
            runCurrent()
            assertEquals("Shop adapter", presenter.uiState.value.aliasEdit)
            presenter.onAliasText("other")
            presenter.onSaveAlias()
            runCurrent()
            assertEquals("An adapter with this name already exists.", presenter.uiState.value.aliasError?.english())
            presenter.onAliasText("X".repeat(41))
            presenter.onSaveAlias()
            runCurrent()
            assertEquals("An adapter name can have a maximum of 40 characters.", presenter.uiState.value.aliasError?.english())
            presenter.onAliasText("Mill adapter")
            presenter.onSaveAlias()
            runCurrent()
            assertNull(presenter.uiState.value.aliasEdit)
            assertEquals("Mill adapter", presenter.uiState.value.alias)
            presenter.onEditAlias()
            presenter.onCancelAlias()
            runCurrent()
            assertNull(presenter.uiState.value.aliasEdit)
        } finally {
            presenter.close()
            fixture.close()
        }
    }

    @Test
    fun theOfflineDetailsOfALegacyAdapterEditTheDeclaredInputsAndForgetCloses() = runTest {
        val fixture = AdapterFixture(this)
        fixture.store.adapters.saveAdapterProfile(AdapterProfile(id = "old", alias = "Old", legacy = true, declaredInputs = setOf(DeclaredInput.X)))
        val presenter = OfflineAdapterPresenter("old", fixture.deps)
        try {
            runCurrent()
            assertTrue(presenter.uiState.value.legacy)
            presenter.onToggleDeclared(DeclaredInput.Probe)
            runCurrent()
            presenter.onToggleDeclared(DeclaredInput.X)
            runCurrent()
            assertEquals(setOf(DeclaredInput.Probe), fixture.store.adapters.loadAdapterProfile("old")!!.declaredInputs)
            presenter.actions.askForget()
            presenter.actions.confirmForget()
            runCurrent()
            assertTrue(presenter.uiState.value.gone)
            assertNull(fixture.store.adapters.loadAdapterProfile("old"))
            assertNotNull(presenter.uiState.value)
        } finally {
            presenter.close()
            fixture.close()
        }
    }
}
