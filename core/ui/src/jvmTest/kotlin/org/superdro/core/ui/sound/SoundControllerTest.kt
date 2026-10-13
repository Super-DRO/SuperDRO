package org.superdro.core.ui.sound

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AppSettings
import org.superdro.core.model.MuteScope
import org.superdro.core.ui.testing.FakeConnections
import org.superdro.core.ui.testing.FakeDeviceSession
import org.superdro.core.util.TestClock

/** The mute rules and the link tones. */
class SoundControllerTest {

    private class Recorder : UiSounds {
        val played = mutableListOf<UiSound>()
        override fun play(sound: UiSound) {
            played += sound
        }
        override fun proximity(level: Double) = Unit
    }

    private val mill = DeviceId("mill")
    private val lathe = DeviceId("lathe")
    private val sounds = Recorder()
    private val settings = MutableStateFlow(AppSettings())
    private val muted = MutableStateFlow<Set<DeviceId>>(emptySet())
    private val clock = TestClock()
    private val controller = SoundController(sounds, settings, muted, clock)

    private val connected = SessionState.Connected(0L)
    private val reconnecting = SessionState.Reconnecting(2, 1_000L, LinkFailure.Lost)

    @Test
    fun oneAppSwitchMutesAllSoundsByDefault() {
        controller.tap(mill)
        controller.longPress(null)
        settings.value = AppSettings(muteAllSounds = true)
        controller.tap(mill)
        controller.error(lathe)
        assertEquals(listOf(UiSound.Short, UiSound.Long), sounds.played)
    }

    @Test
    fun theScopeEachMachineUsesTheSwitchOfTheMachine() {
        settings.value = AppSettings(muteScope = MuteScope.EachMachine, muteAllSounds = true)
        muted.value = setOf(mill)
        controller.tap(mill)
        controller.tap(lathe)
        controller.tap(null)
        assertEquals(listOf(UiSound.Short), sounds.played)
    }

    @Test
    fun theLinkUpToneNeedsMoreThan2000MsInTheForeground() {
        controller.onForeground()
        controller.onSessionState(mill, SessionState.Connecting(1))
        clock.advanceBy(2_000)
        controller.onSessionState(mill, connected)
        assertEquals(emptyList(), sounds.played, "2000 ms is not more than 2000 ms")
        controller.onSessionState(lathe, SessionState.AwaitingData)
        clock.advanceBy(1)
        controller.onSessionState(lathe, connected)
        assertEquals(listOf(UiSound.LinkUp), sounds.played)
        controller.onBackground()
        controller.onSessionState(lathe, SessionState.NoData)
        controller.onSessionState(lathe, connected)
        assertEquals(listOf(UiSound.LinkUp), sounds.played, "No tone in the background")
    }

    @Test
    fun theLinkLostToneNeedsAConnectedSessionAndAVisibleApp() {
        controller.onForeground()
        controller.onSessionState(mill, SessionState.Connecting(1))
        controller.onSessionState(mill, reconnecting)
        assertEquals(emptyList(), sounds.played, "A session that was not connected")
        controller.onSessionState(lathe, connected)
        controller.onSessionState(lathe, SessionState.NoData)
        controller.onSessionState(lathe, reconnecting)
        assertEquals(listOf(UiSound.LinkLost), sounds.played, "The NO_DATA recovery also plays the tone")
        clock.advanceBy(5_000)
        controller.onSessionState(lathe, SessionState.Connecting(3))
        controller.onSessionState(lathe, connected)
        controller.onBackground()
        controller.onSessionState(lathe, reconnecting)
        assertEquals(listOf(UiSound.LinkLost, UiSound.LinkUp), sounds.played)
    }

    @Test
    fun theMuteAlsoStopsTheLinkTones() {
        settings.value = AppSettings(muteAllSounds = true)
        controller.onForeground()
        clock.advanceBy(3_000)
        controller.onSessionState(mill, SessionState.Connecting(1))
        controller.onSessionState(mill, connected)
        controller.onSessionState(mill, reconnecting)
        assertEquals(emptyList(), sounds.played)
    }

    @Test
    fun theWatcherFollowsTheSessionsOfTheManager() = runTest {
        val connections = FakeConnections()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = kotlinx.coroutines.CoroutineScope(dispatcher)
        controller.onForeground()
        val job = controller.watch(scope, connections.sessions)
        val session = FakeDeviceSession(mill, initialState = SessionState.Connecting(1))
        connections.add(session)
        advanceUntilIdle()
        clock.advanceBy(2_500)
        session.state.value = connected
        advanceUntilIdle()
        session.state.value = reconnecting
        advanceUntilIdle()
        job.cancel()
        assertEquals(listOf(UiSound.LinkUp, UiSound.LinkLost), sounds.played)
    }
}
