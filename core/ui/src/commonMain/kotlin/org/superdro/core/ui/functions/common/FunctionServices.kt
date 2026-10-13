package org.superdro.core.ui.functions.common

import androidx.compose.runtime.Composable
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import org.superdro.core.appversion.AppVersion
import org.superdro.core.readout.expression.ExpressionEvaluator
import org.superdro.core.readout.expression.ExpressionResult
import org.superdro.core.readout.expression.valueOrNull
import org.superdro.core.store.files.ExportContext
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.store.json.TabStore
import org.superdro.core.store.repository.AdapterRepository
import org.superdro.core.store.repository.DrawingRepository
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.store.repository.ProbeRepository
import org.superdro.core.store.repository.ToolRepository
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.rememberInstance
import org.superdro.core.ui.session.Connections
import org.superdro.core.ui.sound.SoundController
import org.superdro.core.util.FilePicker

/**
 * The services of the machine functions area:
 * the repositories and the files of `core:store`, the file picker, the sounds, and the
 * presenter context. `core:di` binds one object (`FunctionsUiModule`). The entries of the area get
 * it with [rememberFunctionServices]. A test makes its own object on a database in memory.
 *
 * @property wallClock The wall clock of the stored times and of the export file names.
 * @property timeZone The time zone of the export file names and the `exported` values.
 * @property appVersion The value of the export header `app`.
 */
class FunctionServices(
    val machines: MachineRepository,
    val tools: ToolRepository,
    val drawings: DrawingRepository,
    val probes: ProbeRepository,
    val adapters: AdapterRepository,
    val tabStore: TabStore,
    val appSettings: AppSettingsStore,
    val connections: Connections,
    val filePicker: FilePicker,
    val sounds: SoundController,
    val context: PresenterContext,
    val wallClock: Clock = Clock.System,
    val timeZone: TimeZone = TimeZone.currentSystemDefault(),
    val appVersion: String = AppVersion.NAME,
) {
    /** The header values of a new export file. The file has no device identity. */
    fun exportContext(): ExportContext = ExportContext(appVersion, wallClock.now(), timeZone)

    /** Evaluates the text of a number field with the expression evaluator. [current] is the base of `++x` and `--x`. */
    fun evaluate(text: String, current: Double = 0.0): ExpressionResult = ExpressionEvaluator.evaluate(text, current)

    /**
     * The number parser of the CSV importers of `core:store`:
     * the expression evaluator, so that a cell such as `3+1/4` or `1,5` imports.
     */
    val parseNumber: (String) -> Double? = { text -> ExpressionEvaluator.evaluate(text).valueOrNull() }
}

/** Gives the [FunctionServices] of the DI graph of the composition. */
@Composable
fun rememberFunctionServices(): FunctionServices = rememberInstance()
