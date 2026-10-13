package org.superdro.core.ui.functions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.test.TestScope
import kotlinx.datetime.TimeZone
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.ThemeMode
import org.superdro.core.store.repository.DrawingRepository
import org.superdro.core.store.repository.ProbeRepository
import org.superdro.core.ui.ScreenshotRenderer
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.functions.common.FunctionServices
import org.superdro.core.ui.functions.common.FunctionTabPresenter
import org.superdro.core.ui.readout.ReadoutHarness
import org.superdro.core.ui.readout.ReadoutPresenter
import org.superdro.core.ui.theme.AppTheme
import org.superdro.core.connection.DeviceId
import org.superdro.core.model.SuperDroJson
import org.superdro.core.util.FilePicker
import org.superdro.core.util.PickedFile

/**
 * A [FilePicker] for the tests: [nextOpen] is the file that the next `openDocument` gives, and
 * the lists hold the files that the UI wrote or shared.
 */
class RecordingFilePicker : FilePicker {
    var nextOpen: PickedFile? = null
    val openRequests = mutableListOf<List<String>>()
    val created = mutableListOf<Triple<String, String, ByteArray>>()
    val shared = mutableListOf<Triple<String, String, ByteArray>>()
    var createResult: Boolean = true

    override suspend fun openDocument(mimeTypes: List<String>): PickedFile? {
        openRequests += mimeTypes
        return nextOpen.also { nextOpen = null }
    }

    override suspend fun createDocument(suggestedName: String, mimeType: String, content: ByteArray): Boolean {
        created += Triple(suggestedName, mimeType, content)
        return createResult
    }

    override suspend fun capturePhoto(): PickedFile? = null

    override suspend fun share(name: String, mimeType: String, content: ByteArray): Boolean {
        shared += Triple(name, mimeType, content)
        return true
    }

    /** Sets the next file of `openDocument` with the text [text]. */
    fun nextText(name: String, text: String) {
        nextOpen = PickedFile(name, text.encodeToByteArray())
    }
}

/**
 * The fixture of the presenter tests of the functions area: the readout harness of one tab on a
 * database in memory, with a fake session, and the [FunctionServices] of the area on the same
 * stores. All work runs on the test scheduler of [scope] (virtual time).
 */
class FunctionsHarness(private val scope: TestScope) {
    val readout = ReadoutHarness(scope)
    val store get() = readout.store
    val filePicker = RecordingFilePicker()
    val drawings = DrawingRepository(readout.store.writer)
    val probes = ProbeRepository(readout.store.writer, SuperDroJson, readout.store.wallClock)
    lateinit var services: FunctionServices
    lateinit var tab: FunctionTabPresenter

    /** The readout presenter of the tab. */
    val presenter: ReadoutPresenter get() = readout.presenter

    /** The stored profile of the tab. */
    val profile: MachineProfile get() = readout.profile

    /** Stores [machine] and starts the readout presenter and the services of its tab. */
    suspend fun start(
        machine: MachineProfile = MachineProfile.create(MachineType.VerticalMill, "Mill"),
        states: Map<AxisId, AxisState> = emptyMap(),
        state: SessionState = SessionState.Connected(0L),
        withSession: Boolean = true,
    ): FunctionServices {
        readout.start(machine, states, state, withSession)
        services = FunctionServices(
            machines = store.machines,
            tools = store.tools,
            drawings = drawings,
            probes = probes,
            adapters = store.adapters,
            tabStore = store.tabs,
            appSettings = store.appSettings,
            connections = readout.connections,
            filePicker = filePicker,
            sounds = readout.soundController,
            context = readout.presenter.context,
            wallClock = store.wallClock,
            timeZone = TimeZone.UTC,
            appVersion = "1.0.0",
        )
        tab = FunctionTabPresenter(DeviceId(readout.profile.id), services)
        return services
    }

    /** Sets the sums `R` of the axes and lets the flows run. */
    fun inputs(vararg values: Pair<AxisId, Double>) = readout.inputs(*values)

    fun close() {
        if (::tab.isInitialized) tab.close()
        readout.close()
    }
}

/**
 * Draws [content] in the theme of the app (light, or dark with [dark]) with the window layout of
 * [widthDp] × [heightDp], and writes `<name>.png` to the screenshot directory.
 */
fun renderFunctionScreenshot(
    name: String,
    dark: Boolean,
    widthDp: Int = 1280,
    heightDp: Int = 800,
    content: @Composable () -> Unit,
): File = ScreenshotRenderer.render(name, widthDp = widthDp, heightDp = heightDp, frames = 30) {
    AppTheme(mode = if (dark) ThemeMode.Dark else ThemeMode.System, systemInDarkTheme = false) {
        CompositionLocalProvider(LocalWindowLayout provides WindowLayout.of(widthDp.dp, heightDp.dp)) {
            content()
        }
    }
}
