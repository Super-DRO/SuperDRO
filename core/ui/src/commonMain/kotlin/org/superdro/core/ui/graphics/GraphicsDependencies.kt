package org.superdro.core.ui.graphics

import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import org.superdro.core.store.StoreIOProvider
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.store.repository.AdapterRepository
import org.superdro.core.store.repository.DrawingRepository
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.store.repository.MeasurementLogRepository
import org.superdro.core.store.repository.ProbeRepository
import org.superdro.core.store.repository.ToolRepository
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.session.Connections
import org.superdro.core.ui.sound.SoundController
import org.superdro.core.util.FilePicker
import org.superdro.core.drawing.DxfLimits
import org.superdro.core.drawing.dxf.DxfImportSettings

/**
 * The objects that the presenters of the graphics area use. `core:di` binds one value
 * (`GraphicsUiModule.kt`). An entry of the area
 * gets it with `rememberInstance<GraphicsDependencies>()`. A test makes its own value.
 *
 * @property storeIO The app files: the reference images (`images/<uuid>.jpg`).
 * @property filePicker The document picker, the camera app, and the save picker.
 * @property wallClock The wall clock of the log entries and of the export file names.
 * @property utcOffsetSeconds Gives the offset of the local time zone from UTC, for the log times.
 */
class GraphicsDependencies(
    val connections: Connections,
    val machines: MachineRepository,
    val adapters: AdapterRepository,
    val tools: ToolRepository,
    val probes: ProbeRepository,
    val drawings: DrawingRepository,
    val measurementLog: MeasurementLogRepository,
    val storeIO: StoreIOProvider,
    val filePicker: FilePicker,
    val sounds: SoundController,
    val appSettings: AppSettingsStore,
    val context: PresenterContext,
    val wallClock: Clock = Clock.System,
    val utcOffsetSeconds: () -> Int = ::systemUtcOffsetSeconds,
)

/** Gives the offset of the time zone of the system from UTC now, in seconds. */
fun systemUtcOffsetSeconds(): Int = TimeZone.currentSystemDefault().offsetAt(Clock.System.now()).totalSeconds

/**
 * The settings of a DXF import: the limits of the app settings (`AppSettings.dxfImportLimits`)
 * at the time of the import.
 */
fun GraphicsDependencies.dxfImportSettings(): DxfImportSettings =
    DxfImportSettings(limits = DxfLimits.from(appSettings.settings.value.dxfImportLimits))
