package org.superdro.core.store.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AppSettings
import org.superdro.core.model.GraphicalViewPlacement
import org.superdro.core.model.RememberedMachine
import org.superdro.core.model.SuperDroJson
import org.superdro.core.model.TabState
import org.superdro.core.model.ThemeMode
import org.superdro.core.store.FakeStoreIOProvider
import org.superdro.core.store.TestWallClock

/** The SnapshotPB store rules of the JSON files. */
class JsonFileStoreTest {

    private val scope = TestScope()
    private val dispatcher = StandardTestDispatcher(scope.testScheduler)
    private val io = FakeStoreIOProvider()
    private val clock = TestWallClock()

    private fun settingsStore() = AppSettingsStore(io, SuperDroJson, dispatcher, clock)

    private fun tabStore() = TabStore(io, SuperDroJson, dispatcher, clock)

    @Test
    fun aWriteGoesToATemporaryFileAndThenMoves() = scope.runTest {
        val store = settingsStore()
        store.awaitLoaded()
        store.update { it.copy(themeMode = ThemeMode.Dark) }
        advanceUntilIdle()
        assertEquals(listOf("app-settings.json.tmp -> app-settings.json"), io.moves)
        assertFalse(io.exists("app-settings.json.tmp"))
        val text = io.text("app-settings.json")!!
        assertTrue(text.startsWith("""{"schemaVersion":1,"themeMode":"Dark""""), text)
        assertEquals(ThemeMode.Dark, settingsStore().also { it.awaitLoaded() }.state.value.themeMode)
    }

    @Test
    fun aFailedMoveKeepsTheOldFileWhole() = scope.runTest {
        io.putText("app-settings.json", """{"schemaVersion":1,"themeMode":"Dark"}""")
        val store = settingsStore()
        store.awaitLoaded()
        io.failMoves = true
        store.update { it.copy(largeButtons = true) }
        advanceUntilIdle()
        assertEquals("""{"schemaVersion":1,"themeMode":"Dark"}""", io.text("app-settings.json"))
        assertFalse(io.exists("app-settings.json.tmp"))
        // The value in memory has the change.
        assertTrue(store.state.value.largeButtons)
    }

    @Test
    fun aFileThatDoesNotDecodeMovesAside() = scope.runTest {
        io.putText("tabs.json", "{\"remembered\":[")
        val store = tabStore()
        store.awaitLoaded()
        assertEquals(TabState(), store.state.value)
        assertEquals("{\"remembered\":[", io.text("tabs.json.corrupt-1791792000000"))
        assertFalse(io.exists("tabs.json"))
        store.remember("p1")
        advanceUntilIdle()
        assertEquals(listOf(RememberedMachine("p1")), tabStore().also { it.awaitLoaded() }.state.value.remembered)
    }

    @Test
    fun whenTheCorruptFileCannotMoveTheStoreWritesNothing() = scope.runTest {
        io.putText("app-settings.json", "not json")
        io.failMoves = true
        val store = settingsStore()
        store.awaitLoaded()
        store.update { it.copy(fullScreen = false) }
        advanceUntilIdle()
        assertEquals("not json", io.text("app-settings.json"))
    }

    @Test
    fun awaitLoadedGivesTheValueOfTheFile() = scope.runTest {
        io.putText("app-settings.json", """{"schemaVersion":1,"themeMode":"Dark"}""")
        val store = settingsStore()
        assertFalse(store.isLoaded.value)
        // Before the load, the state holds the defaults: a one-time reader must wait.
        assertEquals(ThemeMode.System, store.state.value.themeMode)
        store.awaitLoaded()
        assertTrue(store.isLoaded.value)
        assertEquals(ThemeMode.Dark, store.state.value.themeMode)
    }

    @Test
    fun anUpdateBeforeTheLoadIsNotLost() = scope.runTest {
        io.putText("app-settings.json", """{"schemaVersion":1,"themeMode":"Dark"}""")
        val store = settingsStore()
        store.update { it.copy(largeButtons = true) }
        store.awaitLoaded()
        advanceUntilIdle()
        // The file gives the theme, and the early update applies on top of it.
        assertEquals(ThemeMode.Dark, store.state.value.themeMode)
        assertTrue(store.state.value.largeButtons)
        val reloaded = settingsStore().also { it.awaitLoaded() }.state.value
        assertEquals(ThemeMode.Dark, reloaded.themeMode)
        assertTrue(reloaded.largeButtons)
    }

    @Test
    fun anOldFileWithoutTheNewFieldsLoads() = scope.runTest {
        io.putText("app-settings.json", """{"themeMode":"Dark","fieldOfAFutureVersion":true}""")
        io.putText("tabs.json", """{"remembered":[{"profileId":"p1"}]}""")
        val settings = settingsStore().also { it.awaitLoaded() }.state.value
        assertEquals(AppSettings(themeMode = ThemeMode.Dark), settings)
        assertEquals(GraphicalViewPlacement.BelowReadout, settings.graphicalViewInPortrait)
        val tabs = tabStore().also { it.awaitLoaded() }.state.value
        assertEquals(TabState(remembered = listOf(RememberedMachine("p1", paused = false))), tabs)
        assertEquals(1, tabs.schemaVersion)
    }

    @Test
    fun theTabStoreKeepsTheRememberedMachinesThePauseFlagsAndTheSelection() = scope.runTest {
        val store = tabStore()
        store.remember("mill")
        store.remember("lathe")
        store.remember("mill")
        store.setPaused("lathe", true)
        store.select("lathe")
        advanceUntilIdle()
        assertEquals(listOf("mill"), store.wantedProfileIds.first())

        val reloaded = tabStore().also { it.awaitLoaded() }
        assertEquals(TabState(remembered = listOf(RememberedMachine("mill"), RememberedMachine("lathe", paused = true)), selectedProfileId = "lathe"), reloaded.state.value)
        reloaded.forget("lathe")
        assertNull(reloaded.state.value.selectedProfileId)
        assertEquals(listOf("mill"), reloaded.state.value.remembered.map { it.profileId })
    }

    @Test
    fun aShownHintIsRemembered() = scope.runTest {
        val store = settingsStore()
        store.markHintShown("hint_zero")
        store.markHintShown("hint_zero")
        advanceUntilIdle()
        assertEquals(setOf("hint_zero"), settingsStore().also { it.awaitLoaded() }.state.value.shownHints)
    }

    @Test
    fun theUpdatesOfOneIntervalGoIntoOneWrite() = scope.runTest {
        val store = settingsStore()
        store.awaitLoaded()
        // Ten keystrokes, 20 ms apart: 200 ms, less than the write delay.
        repeat(10) { index ->
            store.update { it.copy(shownHints = it.shownHints + "k$index") }
            advanceTimeBy(20)
            runCurrent()
        }
        assertTrue(io.moves.isEmpty(), "No write before the delay.")
        advanceTimeBy(JsonFileStore.WRITE_DELAY_MS)
        runCurrent()
        assertEquals(1, io.moves.size, "One write for ten updates.")
        assertEquals(10, settingsStore().also { it.awaitLoaded() }.state.value.shownHints.size)
        // A later update starts a new write.
        store.update { it.copy(largeButtons = true) }
        advanceUntilIdle()
        assertEquals(2, io.moves.size)
    }

    @Test
    fun flushWritesImmediately() = scope.runTest {
        val store = settingsStore()
        store.awaitLoaded()
        store.update { it.copy(themeMode = ThemeMode.Dark) }
        runCurrent()
        assertTrue(io.moves.isEmpty())
        store.flush()
        runCurrent()
        assertEquals(1, io.moves.size)
        assertEquals(ThemeMode.Dark, settingsStore().also { it.awaitLoaded() }.state.value.themeMode)

        // awaitWrites also writes immediately, with no virtual time.
        store.update { it.copy(largeButtons = true) }
        val before = currentTime
        store.awaitWrites()
        assertEquals(before, currentTime)
        assertEquals(2, io.moves.size)
        assertTrue(settingsStore().also { it.awaitLoaded() }.state.value.largeButtons)
    }

    @Test
    fun aFailedWriteDoesNotStopTheNextWrites() = scope.runTest {
        val store = settingsStore()
        store.awaitLoaded()
        io.failMoves = true
        store.update { it.copy(largeButtons = true) }
        advanceUntilIdle()
        io.failMoves = false
        store.update { it.copy(themeMode = ThemeMode.Dark) }
        advanceUntilIdle()
        val reloaded = settingsStore().also { it.awaitLoaded() }.state.value
        assertTrue(reloaded.largeButtons)
        assertEquals(ThemeMode.Dark, reloaded.themeMode)
    }
}
