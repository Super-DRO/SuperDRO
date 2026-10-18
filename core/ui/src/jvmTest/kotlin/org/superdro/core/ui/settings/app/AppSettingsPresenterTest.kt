package org.superdro.core.ui.settings.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.BatteryOptimization
import org.superdro.core.connection.ConnectionRequest
import org.superdro.core.model.AppSettings
import org.superdro.core.model.DxfImportLimits
import org.superdro.core.model.GraphicalViewPlacement
import org.superdro.core.model.MuteScope
import org.superdro.core.model.ThemeMode
import org.superdro.core.model.TransportKind
import org.superdro.core.ui.tabs.RecordingRequests
import org.superdro.core.ui.tabs.english
import org.superdro.core.ui.tabs.testPresenterContext
import org.superdro.core.ui.testing.UiStoreFixture

/** A [BatteryOptimization] whose state the test sets. It counts the calls of [refresh]. */
class FakeBatteryOptimization(exempt: Boolean = true) : BatteryOptimization {
    override val exempt = MutableStateFlow(exempt)
    var refreshes = 0
    override fun refresh() {
        refreshes++
    }
}

/** The app settings screen (the App rows). */
class AppSettingsPresenterTest {

    private class Harness(scope: TestScope, exempt: Boolean = true) {
        val store = UiStoreFixture(scope)
        val battery = FakeBatteryOptimization(exempt)
        val requests = RecordingRequests()
        val presenter = AppSettingsPresenter(store.appSettings, battery, requests, scope.testPresenterContext())
        val settings: AppSettings get() = store.appSettings.settings.value

        fun close() {
            presenter.close()
            store.close()
        }
    }

    @Test
    fun eachAppRowOfTheTableHasItsDefault() = runTest {
        val harness = Harness(this)
        runCurrent()
        val settings = harness.presenter.uiState.value.settings
        assertEquals(ThemeMode.System, settings.themeMode)
        assertFalse(settings.largeButtons)
        assertFalse(settings.muteAllSounds)
        assertEquals(MuteScope.App, settings.muteScope)
        assertTrue(settings.fullScreen)
        assertTrue(settings.wakeLockWhileLinked)
        assertTrue(settings.showHints)
        assertTrue(settings.showSetupReminder)
        assertTrue(settings.checkForUpdates)
        assertFalse(settings.sendGetSigOnce)
        assertEquals(GraphicalViewPlacement.BelowReadout, settings.graphicalViewInPortrait)
        assertEquals(emptySet(), settings.hiddenLinkTypes)
        assertTrue(listOf(TransportKind.BluetoothSpp, TransportKind.UsbSerial, TransportKind.Tcp).all(harness.presenter.uiState.value::linkTypeShown))
        assertEquals(AppSettingsGroup.Appearance, harness.presenter.uiState.value.group)
        harness.close()
    }

    @Test
    fun eachSwitchWritesTheStoreAtOnce() = runTest {
        val harness = Harness(this)
        runCurrent()
        val presenter = harness.presenter
        presenter.setThemeMode(ThemeMode.Dark)
        presenter.setLargeButtons(true)
        presenter.setFullScreen(false)
        presenter.setShowHints(false)
        presenter.setShowSetupReminder(false)
        presenter.setCheckForUpdates(false)
        presenter.setGraphicalViewInPortrait(GraphicalViewPlacement.FullScreen)
        presenter.setMuteAllSounds(true)
        presenter.setWakeLock(false)
        presenter.setSendGetSigOnce(true)
        runCurrent()
        val settings = harness.settings
        assertEquals(ThemeMode.Dark, settings.themeMode)
        assertTrue(settings.largeButtons)
        assertFalse(settings.fullScreen)
        assertFalse(settings.showHints)
        assertFalse(settings.showSetupReminder)
        assertFalse(settings.checkForUpdates)
        assertEquals(GraphicalViewPlacement.FullScreen, settings.graphicalViewInPortrait)
        assertTrue(settings.muteAllSounds)
        assertFalse(settings.wakeLockWhileLinked)
        assertTrue(settings.sendGetSigOnce)
        assertEquals(settings, presenter.uiState.value.settings)
        harness.store.appSettings.awaitWrites()
        val file = harness.store.storeIO.files.getValue("app-settings.json").decodeToString()
        assertTrue("\"largeButtons\":true" in file, file)
        assertTrue("\"checkForUpdates\":false" in file, file)
        harness.close()
    }

    @Test
    fun theMuteScopeEachMachineTurnsOffTheAppSwitch() = runTest {
        val harness = Harness(this)
        runCurrent()
        assertTrue(harness.presenter.uiState.value.appMuteApplies)
        harness.presenter.setMuteScope(MuteScope.EachMachine)
        runCurrent()
        assertEquals(MuteScope.EachMachine, harness.settings.muteScope)
        assertFalse(harness.presenter.uiState.value.appMuteApplies)
        harness.close()
    }

    @Test
    fun theLastLinkTypeCannotBeHidden() = runTest {
        val harness = Harness(this)
        runCurrent()
        harness.presenter.setLinkTypeShown(TransportKind.BluetoothSpp, false)
        harness.presenter.setLinkTypeShown(TransportKind.Tcp, false)
        runCurrent()
        assertEquals(setOf(TransportKind.BluetoothSpp, TransportKind.Tcp), harness.settings.hiddenLinkTypes)
        assertNull(harness.presenter.uiState.value.message)
        harness.presenter.setLinkTypeShown(TransportKind.UsbSerial, false)
        runCurrent()
        assertEquals(setOf(TransportKind.BluetoothSpp, TransportKind.Tcp), harness.settings.hiddenLinkTypes)
        val message = harness.presenter.uiState.value.message!!
        assertEquals("At least one link type must stay on.", message.text.english())
        harness.presenter.onMessageShown(message.id)
        harness.presenter.setLinkTypeShown(TransportKind.Tcp, true)
        runCurrent()
        assertNull(harness.presenter.uiState.value.message)
        assertEquals(setOf(TransportKind.BluetoothSpp), harness.settings.hiddenLinkTypes)
        assertTrue(harness.presenter.uiState.value.linkTypeShown(TransportKind.Tcp))
        harness.close()
    }

    @Test
    fun theBatteryStateComesFromThePlatformAndTheButtonOpensTheSystemSetting() = runTest {
        val harness = Harness(this, exempt = false)
        runCurrent()
        assertEquals(1, harness.battery.refreshes)
        assertFalse(harness.presenter.uiState.value.batteryExempt)
        harness.presenter.openBatteryOptimizationSettings()
        runCurrent()
        assertEquals(listOf<ConnectionRequest>(ConnectionRequest.OpenBatteryOptimizationSettings), harness.requests.sent)
        harness.battery.exempt.value = true
        harness.presenter.onResume()
        runCurrent()
        assertEquals(2, harness.battery.refreshes)
        assertTrue(harness.presenter.uiState.value.batteryExempt)
        harness.close()
    }

    @Test
    fun theDxfFieldsShowTheDefaults() = runTest {
        val harness = Harness(this)
        runCurrent()
        val limits = DxfImportLimits()
        assertEquals(2_000_000L, limits.maxSourceBytes)
        assertEquals(10_000, limits.maxEntities)
        assertEquals(10_000, limits.maxBezierSegments)
        assertEquals(8, limits.maxBlockLevels)
        assertEquals(5000L, limits.maxParseTimeMs)
        assertEquals(1000L, limits.curveToleranceNm)
        assertEquals(2000, limits.polylineWarningPoints)
        assertEquals(1000, limits.maxSplineControlPoints)
        assertEquals(
            mapOf(
                DxfLimit.SourceBytes to "2000000",
                DxfLimit.Entities to "10000",
                DxfLimit.BezierSegments to "10000",
                DxfLimit.BlockLevels to "8",
                DxfLimit.ParseTimeMs to "5000",
                DxfLimit.CurveTolerance to "0.001",
                DxfLimit.PolylineWarningPoints to "2000",
                DxfLimit.SplineControlPoints to "1000",
            ),
            harness.presenter.uiState.value.dxfTexts,
        )
        harness.close()
    }

    @Test
    fun aValidDxfTextGoesIntoTheStoreAndATextThatIsNotValidShowsItsMessage() = runTest {
        val harness = Harness(this)
        runCurrent()
        val presenter = harness.presenter
        presenter.setDxfText(DxfLimit.Entities, "500")
        runCurrent()
        assertEquals(500, harness.settings.dxfImportLimits.maxEntities)
        assertNull(presenter.uiState.value.dxfErrors[DxfLimit.Entities])
        listOf("0", "-5", "abc", "1.5", "", "3000000000").forEach { text ->
            presenter.setDxfText(DxfLimit.Entities, text)
            runCurrent()
            assertEquals(500, harness.settings.dxfImportLimits.maxEntities, text)
            assertEquals(text, presenter.uiState.value.dxfTexts[DxfLimit.Entities])
            assertEquals("Type a whole number more than 0.", presenter.uiState.value.dxfErrors.getValue(DxfLimit.Entities).english(), text)
        }
        presenter.setDxfText(DxfLimit.SourceBytes, "3000000000")
        presenter.setDxfText(DxfLimit.ParseTimeMs, "12000")
        presenter.setDxfText(DxfLimit.CurveTolerance, "0.002")
        runCurrent()
        assertEquals(3_000_000_000L, harness.settings.dxfImportLimits.maxSourceBytes)
        assertEquals(12_000L, harness.settings.dxfImportLimits.maxParseTimeMs)
        assertEquals(2000L, harness.settings.dxfImportLimits.curveToleranceNm)
        presenter.setDxfText(DxfLimit.CurveTolerance, "0,0005")
        runCurrent()
        assertEquals(500L, harness.settings.dxfImportLimits.curveToleranceNm)
        presenter.setDxfText(DxfLimit.CurveTolerance, "0")
        runCurrent()
        assertEquals(500L, harness.settings.dxfImportLimits.curveToleranceNm)
        assertEquals("Type a length more than 0.", presenter.uiState.value.dxfErrors.getValue(DxfLimit.CurveTolerance).english())
        harness.close()
    }

    @Test
    fun theResetGivesTheDefaultsAgain() = runTest {
        val harness = Harness(this)
        runCurrent()
        harness.presenter.setDxfText(DxfLimit.BlockLevels, "3")
        harness.presenter.setDxfText(DxfLimit.SplineControlPoints, "x")
        runCurrent()
        harness.presenter.resetDxfLimits()
        runCurrent()
        assertEquals(DxfImportLimits(), harness.settings.dxfImportLimits)
        assertEquals("8", harness.presenter.uiState.value.dxfTexts[DxfLimit.BlockLevels])
        assertEquals("1000", harness.presenter.uiState.value.dxfTexts[DxfLimit.SplineControlPoints])
        assertTrue(harness.presenter.uiState.value.dxfErrors.isEmpty())
        assertEquals("The DXF import limits are the defaults again.", harness.presenter.uiState.value.message!!.text.english())
        harness.close()
    }

    @Test
    fun theCurveToleranceUsesTheLengthFormat() {
        assertEquals("0.001", DxfLimitFields.text(DxfLimit.CurveTolerance, DxfImportLimits(curveToleranceNm = 1000L)))
        assertEquals("0.0125", DxfLimitFields.text(DxfLimit.CurveTolerance, DxfImportLimits(curveToleranceNm = 12_500L)))
        assertEquals(1000L, DxfLimitFields.parse(DxfLimit.CurveTolerance, "0.001"))
        assertEquals(10L, DxfLimitFields.parse(DxfLimit.CurveTolerance, "0.00001"))
        assertNull(DxfLimitFields.parse(DxfLimit.CurveTolerance, "0.000001"))
        assertNull(DxfLimitFields.parse(DxfLimit.CurveTolerance, "-0.002"))
    }
}
