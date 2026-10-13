package org.superdro.core.ui.graphics.probe

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.ProbeConfig
import org.superdro.core.model.ProbeProfile
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.graphics.GraphicsHarness
import org.superdro.core.ui.text.UiText

/** A metric machine of [type] with the probe input on (`probe_enable`). */
fun probeMachine(type: MachineType = MachineType.VerticalMill, probe: ProbeConfig = ProbeConfig(enabled = true)): MachineProfile {
    val base = MachineProfile.create(type)
    return base.copy(metricDefault = true, probe = probe)
}

/** Stores [probe] for the machine of [harness] and gives the stored profile. */
suspend fun GraphicsHarness.storeProbe(probe: ProbeProfile): ProbeProfile =
    probes.saveProbeProfile(probe.copy(profileId = profile.id)).valueOrNull() ?: error("The probe profile was not stored.")

/**
 * One probe closure on the port [port] at [timeMs]: `P1;`, a tick after the latch deadline, and
 * `P0;`. The latch uses the last sample at or before the time on each axis with no later sample.
 */
fun GraphicsHarness.touch(scope: TestScope, timeMs: Long, port: Char = 'p') {
    session.probe(active = true, timeMs = timeMs, port = port)
    session.tick(timeMs + 600L)
    session.probe(active = false, timeMs = timeMs + 700L, port = port)
    scope.advanceUntilIdle()
}

/** Gives the English text of [text] for the checks: the fallback of a key with its arguments. */
fun UiText?.english(): String? = when (this) {
    null -> null
    is UiText.Key -> args.foldIndexed(fallback) { index, text, argument ->
        text.replace("%${index + 1}\$s", argument.toString()).replace("%${index + 1}\$d", argument.toString())
    }
    is UiText.Plain -> text
    is UiText.Resource -> "resource:${resource.key}"
}
