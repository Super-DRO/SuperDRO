package org.superdro.core.ui.adapter

import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import org.superdro.core.appversion.AppVersion
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceSession
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.MachineProfile
import org.superdro.core.store.files.ExportContext
import org.superdro.core.store.repository.AdapterRepository
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.store.repository.ProbeRepository
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.session.Connections
import org.superdro.core.ui.sound.SoundController
import org.superdro.core.util.FilePicker

/**
 * The dependencies of the presenters of the adapter area. `core:di` binds one object
 * (`adapterUiModule`). A presenter gets this object and the
 * arguments of its route.
 *
 * @property sounds The sound controller of the app: the reference mark tones and the probe
 *   sounds.
 * @property filePicker The document picker of the platform: the `.tdap`, `.tdprb`, and CSV files.
 * @property wallClock The wall clock of the dates (calibration date, export time, last seen).
 * @property timeZone The time zone of the local times (the firmware date, the export files).
 * @property appVersion The version text of the export files.
 */
class AdapterUiDeps(
    val connections: Connections,
    val machines: MachineRepository,
    val adapters: AdapterRepository,
    val probes: ProbeRepository,
    val filePicker: FilePicker,
    val sounds: SoundController,
    val context: PresenterContext,
    val wallClock: Clock = Clock.System,
    val timeZone: TimeZone = TimeZone.currentSystemDefault(),
    val appVersion: String = AppVersion.NAME,
) {

    /** The session of [device], or null while the device has no session. */
    fun session(device: DeviceId): Flow<DeviceSession?> =
        connections.sessions.map { sessions -> sessions[device] }.distinctUntilChanged()

    /** The state of the session of [device], or null while the device has no session. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun sessionState(device: DeviceId): Flow<SessionState?> =
        session(device).flatMapLatest { session -> session?.state ?: flowOf(null) }

    /** The machine profile of the tab of [device]: the device ID is the ID of the machine profile. */
    fun machine(device: DeviceId): Flow<MachineProfile?> = machines.profile(device.value)

    /**
     * The adapter profile that the machine profile of [device] selects, with all its maps,
     * or null when the profile selects no adapter profile.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun tabAdapter(device: DeviceId): Flow<AdapterProfile?> = machine(device)
        .map { profile -> profile?.adapterProfileId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else adapters.adapterProfile(id) }

    /** The header values of an export file at the current time. */
    fun exportContext(): ExportContext = ExportContext(appVersion, wallClock.now(), timeZone)
}
