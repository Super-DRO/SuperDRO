package org.superdro.core.di

import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.WantedLink
import org.superdro.core.debugadapter.DebugAdapter
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.Calibration
import org.superdro.core.model.CalibrationStatus
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.store.repository.AdapterRepository
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.session.Connections

/**
 * The stored profiles of the two demo machines of a debug build. The demo entry
 * connects the links of [DebugAdapter.demoLinks]. Each session reads the input configuration of its
 * machine profile, and the readout reads the axes of that profile. Thus a demo machine needs a
 * stored profile with the ID of its device.
 *
 * When a session of a demo link starts and its profile is not in the store, this object writes a
 * profile with the link: "Demo mill" (a vertical mill) or "Demo lathe" (a lathe with the
 * tachometer), and an adapter profile with the default encoder profiles (25400 counts per inch, the
 * counts of the fake adapter). The encoder profiles have the status "user set", so the demo shows
 * no setup reminder. A release build has no demo links, so this object does nothing there.
 */
class DemoMachines(
    private val debugAdapter: DebugAdapter,
    private val connections: Connections,
    private val machines: MachineRepository,
    private val adapters: AdapterRepository,
) {

    /** Watches the sessions in [scope] and writes the missing profiles of the demo machines. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        val links = debugAdapter.demoLinks.associateBy { link -> link.deviceId }
        if (links.isEmpty()) return@launch
        connections.sessions
            .map { sessions -> sessions.keys.filter { id -> id in links } }
            .distinctUntilChanged()
            .collect { ids -> ids.forEach { id -> ensureProfile(links.getValue(id)) } }
    }

    private suspend fun ensureProfile(link: WantedLink) {
        if (machines.loadProfile(link.deviceId.value) != null) return
        val type = typeOf(link.deviceId)
        val encoders = AdapterProfile().encoders.mapValues { (_, encoder) ->
            encoder.copy(calibration = Calibration(status = CalibrationStatus.UserSet))
        }
        val adapter = adapters.saveAdapterProfile(
            AdapterProfile(alias = link.name, model = DEMO_MODEL, lastLink = link.address, encoders = encoders),
        ).valueOrNull()
        val base = MachineProfile.create(type, link.name)
        val profile = base.copy(
            id = link.deviceId.value,
            adapter = link.address,
            adapterProfileId = adapter?.id,
            metricDefault = true,
            tachometer = base.tachometer.copy(enabled = type.isLathe),
        )
        val result = machines.createProfile(profile)
        log.i { "Demo profile ${link.name}: ${if (result.valueOrNull() != null) "written" else result}" }
    }

    private fun typeOf(id: DeviceId): MachineType =
        if (id.value.contains("lathe", ignoreCase = true)) MachineType.Lathe else MachineType.VerticalMill

    private companion object {
        const val DEMO_MODEL: String = "tda-fake"
        val log = Arbor.tag("DemoMachines")
    }
}
