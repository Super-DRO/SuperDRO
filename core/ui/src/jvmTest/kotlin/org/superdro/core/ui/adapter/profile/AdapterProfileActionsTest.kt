package org.superdro.core.ui.adapter.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.BacklashMode
import org.superdro.core.model.Calibration
import org.superdro.core.model.CalibrationStatus
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.IdentitySource
import org.superdro.core.model.MapPoint
import org.superdro.core.model.ProbeContactType
import org.superdro.core.store.files.ExportFile
import org.superdro.core.ui.adapter.AdapterFixture
import org.superdro.core.ui.tabs.english
import org.superdro.core.ui.text.UiText
import org.superdro.core.util.PickedFile

/** The copy, export, import, and forget actions of an adapter profile. */
class AdapterProfileActionsTest {

    private val profile = AdapterProfile(
        id = "shop",
        alias = "Shop adapter",
        model = "tda-example",
        identity = "24:6f:28:a1:b2:c5",
        identitySource = IdentitySource.BtMac,
        probeContactType = ProbeContactType.NormallyClosed,
        probeDebounceMs = 50L,
        encoders = AdapterProfile().encoders + ('x' to EncoderProfile.defaultFor('x').copy(
            resolution = 5080.0,
            backlashMode = BacklashMode.Constant,
            backlashCounts = 3L,
            errorMap = listOf(MapPoint(0L, 0L), MapPoint(10_000L, 4L), MapPoint(20_000L, 9L)),
            calibration = Calibration(status = CalibrationStatus.Calibrated),
        )),
    )

    private class Setup(val fixture: AdapterFixture, val actions: AdapterProfileActions, val messages: MutableList<UiText>, val forgotten: () -> Boolean)

    private fun actionsTest(body: suspend TestScope.(Setup) -> Unit) = runTest {
        val fixture = AdapterFixture(this)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        fixture.store.adapters.saveAdapterProfile(profile)
        runCurrent()
        val messages = mutableListOf<UiText>()
        var forgotten = false
        val stored: AdapterProfile? = fixture.store.adapters.loadAdapterProfile("shop")
        val actions = AdapterProfileActions(scope, fixture.deps, { stored }, { text -> messages += text }, onForgotten = { forgotten = true })
        try {
            body(Setup(fixture, actions, messages) { forgotten })
        } finally {
            scope.cancel()
            fixture.close()
        }
    }

    @Test
    fun copyMakesAProfileWithTheCopyNameAndNoIdentity() = actionsTest { setup ->
        setup.actions.copy()
        runCurrent()
        setup.actions.copy()
        runCurrent()
        val all = setup.fixture.store.adapters.adapterProfiles().first().sortedBy { it.alias }
        assertEquals(listOf("Shop adapter", "Shop adapter (copy 2)", "Shop adapter (copy)"), all.map { it.alias })
        val copy = all.first { it.alias == "Shop adapter (copy)" }
        assertNull(copy.identity, "An identity belongs to one physical adapter.")
        assertNull(copy.identitySource)
        val full = setup.fixture.store.adapters.loadAdapterProfile(copy.id)!!
        assertEquals(5080.0, full.encoders.getValue('x').resolution)
        assertEquals(3, full.encoders.getValue('x').errorMap.size, "The copy has the maps.")
        assertEquals("Saved the copy “Shop adapter (copy)”.", setup.messages.first().english())
    }

    @Test
    fun theUniqueNameKeepsTheLimitOfFortyCharacters() {
        val long = "A".repeat(40)
        assertEquals("A".repeat(33) + " (copy)", AdapterProfileActions.uniqueName(long, AdapterProfileActions.COPY_SUFFIX, emptySet()))
        assertEquals("Shop (copy 3)", AdapterProfileActions.uniqueName("Shop", AdapterProfileActions.COPY_SUFFIX, setOf("shop (copy)", "shop (copy 2)")))
        assertEquals("Shop", AdapterProfileActions.uniqueName("Shop", null, emptySet()))
        assertEquals("Shop (2)", AdapterProfileActions.uniqueName("Shop", null, setOf("shop")))
    }

    @Test
    fun exportWritesTheSuperDroFileAndTheTdapForm() = actionsTest { setup ->
        setup.actions.askExport()
        assertEquals(ProfileDialog.ExportChoice, setup.actions.state.value.dialog)
        setup.actions.export(AdapterExportKind.Tdap)
        runCurrent()
        setup.actions.export(AdapterExportKind.SuperDro)
        runCurrent()
        val (tdapName, tdapMime, tdapText) = setup.fixture.files.created[0]
        assertEquals("superdro-adapter_profile-Shop_adapter-20261012_080000.tdap", tdapName)
        assertEquals(ExportFile.MIME_TDAP, tdapMime)
        assertTrue(tdapText.contains("\"touchdro\": \"adapter-profile\""))
        assertTrue(tdapText.contains("\"contact-type\": \"NORMALLY_CLOSED\""))
        assertFalse(tdapText.contains("\"device\""), "An export file has no device identity.")
        val (jsonName, jsonMime, jsonText) = setup.fixture.files.created[1]
        assertEquals("superdro-adapter_profile-Shop_adapter-20261012_080000.json", jsonName)
        assertEquals(ExportFile.MIME_JSON, jsonMime)
        assertTrue(jsonText.contains("\"superdro\": \"adapter-profile\""))
        assertEquals(listOf("Saved as “superdro-adapter_profile-Shop_adapter-20261012_080000.tdap”.", "Saved as “superdro-adapter_profile-Shop_adapter-20261012_080000.json”."), setup.messages.map { it.english() })
    }

    @Test
    fun aCancelledExportShowsThatTheFileWasNotSaved() = actionsTest { setup ->
        setup.fixture.files.createResult = false
        setup.actions.export(AdapterExportKind.SuperDro)
        runCurrent()
        assertEquals("The file was not saved.", setup.messages.single().english())
    }

    @Test
    fun importShowsThePreviewThenWritesTheCalibrationAndTheReport() = actionsTest { setup ->
        val tdap = """
            {
              "touchdro": "adapter-profile",
              "format": 1,
              "app": "3.32.00, Build 2026-09-20",
              "exported": "2026-10-11 14:30:00",
              "device": "3f2b9c1e-7d4a-4c55-9a51-2f0d8e6b1a77",
              "adapter": "TouchDRO Adapter",
              "model": "tda-example",
              "inputs": [
                {"input": "Y", "encoder-type": "SCALE", "kind": "CONTINUOUS", "resolution": 2540, "invert": true, "filter": false,
                 "error-map": "position,correction\n0,0\n10000,4\n20000,9", "backlash-mode": "CONSTANT", "backlash-constant": 2,
                 "calibration-status": "CALIBRATED", "calibrated-on": "2026-10-01 09:12:45"},
                {"input": "Z", "encoder-type": "SCALE", "kind": "CONTINUOUS", "resolution": 5080, "backlash-mode": "MAP"},
                {"input": "Q", "resolution": 100}
              ],
              "probe": {"contact-type": "NORMALLY_OPEN", "debounce-ms": 15}
            }
        """.trimIndent()
        setup.fixture.files.nextOpen = PickedFile("shop.tdap", tdap.encodeToByteArray())
        setup.actions.importIntoProfile()
        runCurrent()
        val preview = assertIs<ProfileDialog.Preview>(setup.actions.state.value.dialog).preview
        assertTrue(preview.canImport)
        assertTrue(preview.errors.isEmpty())
        assertTrue(preview.toImport.any { line -> line.label == "Y" })
        assertTrue(preview.warnings.isNotEmpty(), "Q is not an input, and MAP on a scale is refused.")
        // Nothing is written before the confirmation.
        assertEquals(5080.0, setup.fixture.store.adapters.loadAdapterProfile("shop")!!.encoders.getValue('x').resolution)
        assertEquals(25400.0, setup.fixture.store.adapters.loadAdapterProfile("shop")!!.encoders.getValue('y').resolution)
        setup.actions.confirmImport()
        runCurrent()
        assertIs<ProfileDialog.Report>(setup.actions.state.value.dialog)
        val stored = setup.fixture.store.adapters.loadAdapterProfile("shop")!!
        assertEquals("Shop adapter", stored.alias, "The import keeps the name.")
        assertEquals("24:6f:28:a1:b2:c5", stored.identity, "The import keeps the identity.")
        val y = stored.encoders.getValue('y')
        assertEquals(2540.0, y.resolution)
        assertTrue(y.reversed)
        assertEquals(3, y.errorMap.size)
        assertEquals(BacklashMode.None, stored.encoders.getValue('z').backlashMode, "MAP on a scale is refused.")
        assertEquals(ProbeContactType.NormallyOpen, stored.probeContactType)
        assertEquals(15L, stored.probeDebounceMs)
        setup.actions.dismiss()
        assertNull(setup.actions.state.value.dialog)
    }

    @Test
    fun aFileOfAnotherKindCannotImport() = actionsTest { setup ->
        setup.fixture.files.nextOpen = PickedFile("tools.tdset", """{"touchdro": "settings", "format": 1}""".encodeToByteArray())
        setup.actions.importIntoProfile()
        runCurrent()
        val preview = assertIs<ProfileDialog.Preview>(setup.actions.state.value.dialog).preview
        assertFalse(preview.canImport)
        assertTrue(preview.errors.isNotEmpty())
        setup.actions.confirmImport()
        runCurrent()
        assertIs<ProfileDialog.Preview>(setup.actions.state.value.dialog, "A file that is not usable writes nothing.")
    }

    @Test
    fun importAsNewMakesANewProfileWithAUniqueName() = actionsTest { setup ->
        val superdro = setup.fixture.store.adapters.loadAdapterProfile("shop")!!
        val file = org.superdro.core.store.files.adapter.AdapterProfileFile.exportSuperDro(superdro, setup.fixture.deps.exportContext())
        setup.fixture.files.nextOpen = PickedFile(file.fileName, file.bytes())
        setup.actions.importAsNew()
        runCurrent()
        setup.actions.confirmImport()
        runCurrent()
        val all = setup.fixture.store.adapters.adapterProfiles().first().map { it.alias }.sorted()
        assertEquals(listOf("Shop adapter", "Shop adapter (2)"), all)
    }

    @Test
    fun forgetAsksFirstThenDeletesTheProfile() = actionsTest { setup ->
        setup.actions.askForget()
        assertEquals(ProfileDialog.ConfirmForget("Shop adapter"), setup.actions.state.value.dialog)
        setup.actions.dismiss()
        runCurrent()
        assertNotNull(setup.fixture.store.adapters.loadAdapterProfile("shop"), "Cancel deletes nothing.")
        setup.actions.askForget()
        setup.actions.confirmForget()
        runCurrent()
        assertNull(setup.fixture.store.adapters.loadAdapterProfile("shop"))
        assertTrue(setup.forgotten())
        assertEquals("Deleted the adapter profile “Shop adapter”.", setup.messages.single().english())
    }
}
