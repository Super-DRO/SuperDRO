package org.superdro.validation.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toArgb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.math.pow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.superdro.core.connection.AdapterInfo
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceRef
import org.superdro.core.connection.DeviceSession
import org.superdro.core.connection.RestartResult
import org.superdro.core.connection.SessionEvent
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.AxisId
import org.superdro.core.protocol.AdapterCommand
import org.superdro.core.protocol.AdapterMessage
import org.superdro.core.protocol.CommandResult
import org.superdro.core.readout.input.AxisValue
import org.superdro.core.readout.input.AxisValueStatus
import org.superdro.core.readout.input.InputHistory
import org.superdro.core.readout.input.InputSnapshot
import org.superdro.core.readout.input.InputStage
import org.superdro.core.readout.input.ProbeEvent
import org.superdro.core.store.StoreIOProviderJvm
import org.superdro.core.store.StoreWriter
import org.superdro.core.store.createSuperDroDatabase
import org.superdro.core.store.db.SuperDroDatabase
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.store.json.TabStore
import org.superdro.core.store.repository.AdapterRepository
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.store.repository.ProbeRepository
import org.superdro.core.store.repository.ToolRepository
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.readout.ReadoutPresenter
import org.superdro.core.ui.session.Connections
import org.superdro.core.ui.sound.NoUiSounds
import org.superdro.core.ui.sound.SoundController
import org.superdro.core.ui.tabs.DeviceTabsPresenter
import org.superdro.core.ui.text.resolve
import org.superdro.core.util.Clock

/**
 * The WCAG 2 contrast formula. The relative luminance of an sRGB color is
 * `0.2126 R + 0.7152 G + 0.0722 B` of the linear channels. The ratio is `(L1 + 0.05) / (L2 + 0.05)`
 * with the lighter color as `L1`.
 */
object Wcag {

    /** Gives the linear value of one sRGB channel of 0 to 1. */
    fun linear(channel: Double): Double = if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)

    /** Gives the relative luminance of the color `#RRGGBB`. */
    fun luminance(hex: String): Double {
        val value = hex.removePrefix("#").toInt(16)
        val r = linear(((value shr 16) and 0xFF) / 255.0)
        val g = linear(((value shr 8) and 0xFF) / 255.0)
        val b = linear((value and 0xFF) / 255.0)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** Gives the contrast ratio of two colors `#RRGGBB`. */
    fun ratio(first: String, second: String): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
}

/** Gives the text `#RRGGBB` of an opaque Compose color. */
fun Color.hex(): String = "#%06X".format(toArgb() and 0xFFFFFF)

/**
 * A device session with no transport: the test sets the state and the inputs. The session sends no
 * command.
 */
class FakeSession(id: String, name: String, address: AdapterAddress = AdapterAddress.Fake("still")) : DeviceSession {
    override val id: DeviceId = DeviceId(id)
    override val device: MutableStateFlow<DeviceRef> = MutableStateFlow(DeviceRef(this.id, name, address))
    override val state: MutableStateFlow<SessionState> = MutableStateFlow(SessionState.Disconnected)
    override val inputs: MutableStateFlow<InputSnapshot> = MutableStateFlow(InputSnapshot.EMPTY)
    override val probeEvents: MutableSharedFlow<ProbeEvent> = MutableSharedFlow()
    override val adapterInfo: MutableStateFlow<AdapterInfo> = MutableStateFlow(AdapterInfo())
    override val history: InputHistory = InputStage().history
    override val messages: MutableSharedFlow<AdapterMessage> = MutableSharedFlow()
    override val events: MutableSharedFlow<SessionEvent> = MutableSharedFlow()

    override suspend fun send(command: AdapterCommand): CommandResult = CommandResult.Cancelled

    override suspend fun restartAdapter(): RestartResult = RestartResult.NoSession

    override fun openAdapterDetails() = Unit

    override fun closeAdapterDetails() = Unit

    override suspend fun <T> readHistory(block: (InputHistory) -> T): T = block(history)

    /** Sets the sum `R` (µm or arc seconds) of each axis of [values] with the status `Ok` at [timeMs]. */
    fun setAxes(values: Map<AxisId, Double>, timeMs: Long = 0L) {
        inputs.value = InputSnapshot(
            axes = values.mapValues { (axis, value) -> AxisValue(axis, value, timeMs, AxisValueStatus.Ok) },
        )
    }
}

/** The [Connections] of the fake sessions. A disconnect sets the state `Disconnected`. */
class FakeConnections : Connections {
    private val mutable = MutableStateFlow<Map<DeviceId, DeviceSession>>(emptyMap())

    override val sessions: StateFlow<Map<DeviceId, DeviceSession>> = mutable

    /** Adds [session] after the other sessions. */
    fun add(session: FakeSession) = mutable.update { map -> map + (session.id to session) }

    /** Removes the session of [id]. */
    fun drop(id: String) = mutable.update { map -> map - DeviceId(id) }

    override fun connect(device: DeviceRef) = Unit

    override fun disconnect(id: DeviceId) {
        (mutable.value[id] as? FakeSession)?.state?.value = SessionState.Disconnected
    }

    override fun disconnectAll() = mutable.value.keys.forEach(::disconnect)

    override fun remove(id: DeviceId) = mutable.update { map -> map - id }
}

/**
 * The presenters of `core:ui` on the real repositories (an in-memory SQLite database and a store
 * folder in a temporary directory) and on the virtual time of [scope]. All writes and all
 * presenter coroutines use [dispatcher], so `advanceTimeBy` controls the timers (CLAUDE.md, "Tests").
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PresenterRig(val scope: TestScope, val dispatcher: CoroutineDispatcher) : AutoCloseable {
    private val scheduler: TestCoroutineScheduler = scope.testScheduler

    /** The clock of the virtual time. */
    val clock: Clock = Clock { scheduler.currentTime }

    val folder: File = Files.createTempDirectory("superdro-ui-validation").toFile()
    val io = StoreIOProviderJvm(folder)
    private val driver = JdbcSqliteDriver(
        JdbcSqliteDriver.IN_MEMORY,
        Properties().apply { setProperty("foreign_keys", "true") },
    ).also { driver -> SuperDroDatabase.Schema.create(driver) }
    val writer = StoreWriter(createSuperDroDatabase(driver), dispatcher, dispatcher)
    val machines = MachineRepository(writer, io)
    val adapters = AdapterRepository(writer, io, fileDispatcher = dispatcher)
    val tools = ToolRepository(writer)
    val probes = ProbeRepository(writer)
    val appSettings = AppSettingsStore(io, dispatcher = dispatcher)
    val tabStore = TabStore(io, dispatcher = dispatcher)
    val connections = FakeConnections()
    val context = PresenterContext(dispatcher = dispatcher, clock = clock)
    val sounds = SoundController(NoUiSounds, appSettings.settings, MutableStateFlow(emptySet()), clock)

    private val closeables = ArrayList<AutoCloseable>()

    /** Gives a new readout presenter of the tab [id]. [close] closes it. */
    fun readout(id: String): ReadoutPresenter =
        ReadoutPresenter(DeviceId(id), connections, machines, adapters, tools, appSettings, sounds, context, probes).also(closeables::add)

    /** Gives a new tabs presenter. [close] closes it. */
    fun tabs(): DeviceTabsPresenter = DeviceTabsPresenter(connections, tabStore, machines, context).also(closeables::add)

    override fun close() {
        closeables.forEach(AutoCloseable::close)
        driver.close()
        folder.deleteRecursively()
    }
}

/** The folder of the PNG files of the UI validation (`validation/build/ui-screenshots`). */
val screenshotDir: File = File(System.getProperty("superdro.uiScreenshotDir") ?: "build/ui-screenshots").apply { mkdirs() }

/** Writes [image] to `<screenshotDir>/<name>.png` and gives the file. */
fun writePng(image: ImageBitmap, name: String): File {
    val data = Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
        ?: error("The PNG encoder gave no data for $name")
    return File(screenshotDir, "$name.png").apply { writeBytes(data.bytes) }
}

/** Gives the ARGB pixels of [image]. */
fun pixels(image: ImageBitmap): IntArray = IntArray(image.width * image.height).also { buffer ->
    image.readPixels(buffer)
}

/**
 * Gives the English texts of [texts] (a null text gives null). The resolution needs a composition:
 * the resource environment of the JVM reads the screen, and the tests have no display.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
fun resolveTexts(texts: List<org.superdro.core.ui.text.UiText?>): List<String?> {
    val out = arrayOfNulls<String>(texts.size)
    androidx.compose.ui.test.runComposeUiTest {
        setContent { texts.forEachIndexed { index, text -> out[index] = text?.resolve() } }
        waitForIdle()
    }
    return out.toList()
}

/**
 * Types [text] with the key events of a physical keyboard: for each character a key down, a typed
 * event with the code point, and a key up, as the desktop window gives them. `pressKey` of the test
 * API gives no code point, so a text field gets no character from it.
 */
@OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.test.ExperimentalTestApi::class)
fun androidx.compose.ui.test.SkikoComposeUiTest.typeOnKeyboard(text: String) {
    val source = java.awt.Canvas()
    text.forEach { character ->
        val code = java.awt.event.KeyEvent.getExtendedKeyCodeForChar(character.code)
        val key = androidx.compose.ui.input.key.Key(code)
        val events = listOf(
            androidx.compose.ui.input.key.KeyEvent(
                key, androidx.compose.ui.input.key.KeyEventType.KeyDown, character.code,
                nativeEvent = java.awt.event.KeyEvent(source, java.awt.event.KeyEvent.KEY_PRESSED, 0L, 0, code, character),
            ),
            androidx.compose.ui.input.key.KeyEvent(
                androidx.compose.ui.input.key.Key.Unknown, androidx.compose.ui.input.key.KeyEventType.Unknown, character.code,
                nativeEvent = java.awt.event.KeyEvent(source, java.awt.event.KeyEvent.KEY_TYPED, 0L, 0, java.awt.event.KeyEvent.VK_UNDEFINED, character),
            ),
            androidx.compose.ui.input.key.KeyEvent(
                key, androidx.compose.ui.input.key.KeyEventType.KeyUp, character.code,
                nativeEvent = java.awt.event.KeyEvent(source, java.awt.event.KeyEvent.KEY_RELEASED, 0L, 0, code, character),
            ),
        )
        events.forEach { event -> runOnUiThread { scene.sendKeyEvent(event) } }
    }
    waitForIdle()
}

/** Presses [key] on the keyboard of the window: a key down and a key up, as the desktop window gives them. */
@OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.test.ExperimentalTestApi::class)
fun androidx.compose.ui.test.SkikoComposeUiTest.pressOnKeyboard(key: androidx.compose.ui.input.key.Key, awtCode: Int) {
    val source = java.awt.Canvas()
    listOf(
        androidx.compose.ui.input.key.KeyEventType.KeyDown to java.awt.event.KeyEvent.KEY_PRESSED,
        androidx.compose.ui.input.key.KeyEventType.KeyUp to java.awt.event.KeyEvent.KEY_RELEASED,
    ).forEach { (type, id) ->
        val event = androidx.compose.ui.input.key.KeyEvent(
            key, type, 0,
            nativeEvent = java.awt.event.KeyEvent(source, id, 0L, 0, awtCode, java.awt.event.KeyEvent.CHAR_UNDEFINED),
        )
        runOnUiThread { scene.sendKeyEvent(event) }
    }
    waitForIdle()
}
