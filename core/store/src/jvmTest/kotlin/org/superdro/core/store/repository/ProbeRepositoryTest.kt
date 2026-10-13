package org.superdro.core.store.repository

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeRoles
import org.superdro.core.model.ProbeType
import org.superdro.core.store.StoreFixture
import org.superdro.core.store.StoreRefusal
import org.superdro.core.store.refusalOrNull
import org.superdro.core.store.valueOrNull

class ProbeRepositoryTest {

    private val scope = TestScope()
    private val store = StoreFixture(scope)

    @AfterTest
    fun close() = store.close()

    @Test
    fun theSchemaHasTheSixStandardTipsForAllMachineProfiles() = scope.runTest {
        val tips = store.probes.standardTips().first()
        assertEquals(
            listOf(
                Triple("Edge finder 0.100\"", 2540.0, false),
                Triple("Edge finder 0.200\"", 5080.0, false),
                Triple("Edge finder 0.500\"", 12700.0, false),
                Triple("Edge finder 4 mm", 4000.0, true),
                Triple("Edge finder 6 mm", 6000.0, true),
                Triple("Edge finder 10 mm", 10000.0, true),
            ),
            tips.map { Triple(it.name, it.stylusDiameterUm, it.metric) },
        )
        tips.forEach { tip ->
            assertEquals(ProbeType.EdgeFinder2D, tip.type)
            assertNull(tip.profileId)
            assertNull(tip.port)
            assertEquals(true, tip.standardTip)
        }
        assertEquals(ProbeRepository.STANDARD_TIP_IDS.toSet(), tips.map { it.id }.toSet())

        // Each machine profile sees them: its own profiles first, then the shared ones by name.
        val mill = store.machines.createProfile(MachineProfile.create(name = "Mill")).valueOrNull()!!
        store.probes.saveProbeProfile(ProbeProfile(profileId = mill.id, name = "Renishaw"))
        val library = store.probes.library(mill.id).first().map { it.name }
        assertEquals("Renishaw", library.first())
        assertEquals(7, library.size)
    }

    @Test
    fun aStandardTipCannotChangeButCanBeDeletedForAllProfiles() = scope.runTest {
        val tip = store.probes.standardTips().first().first()
        val change = store.probes.saveProbeProfile(tip.copy(stylusDiameterUm = 1.0))
        assertEquals(StoreRefusal.StandardTipReadOnly, change.refusalOrNull())
        assertEquals("A standard tip can't be changed.", change.refusalOrNull()!!.message)

        val mill = store.machines.createProfile(MachineProfile.create(name = "Mill")).valueOrNull()!!
        store.machines.updateProfile(mill.id) { it.copy(probeRoles = ProbeRoles(edgeFinderProfileId = tip.id)) }
        store.probes.deleteProbeProfile(tip.id)
        assertEquals(5, store.probes.standardTips().first().size)
        assertEquals(5, store.probes.library(mill.id).first().size)
        // The machine profile loses the selection of the deleted profile.
        assertNull(store.machines.loadProfile(mill.id)!!.probeRoles.edgeFinderProfileId)
    }

    @Test
    fun aProbeProfileKeepsAllValues() = scope.runTest {
        val probe = ProbeProfile(
            type = ProbeType.TouchProbe,
            name = "Touch probe 2 mm",
            port = 'q',
            stylusDiameterUm = 2000.0,
            effectiveDiameterUm = 1987.5,
            xOffsetUm = 10.0,
            yOffsetUm = -5.0,
            zOffsetUm = 30000.0,
            metric = true,
            sampleCount = 3,
            spreadLimitUm = null,
            calibrationResidualUm = 1.5,
            calibratedOn = Instant.parse("2026-10-11T12:00:00Z"),
            calibrationSession = """{"touches":[]}""",
            sessionDigest = "a".repeat(64),
        )
        store.probes.saveProbeProfile(probe.copy(standardTip = true))
        // A new profile is never a standard tip.
        assertEquals(probe, store.probes.loadProbeProfile(probe.id))
        store.probes.markUsed(probe.id, Instant.parse("2026-10-12T09:00:00Z"))
        assertEquals(Instant.parse("2026-10-12T09:00:00Z"), store.probes.loadProbeProfile(probe.id)!!.lastUsedOn)
    }

    @Test
    fun theReplaceAllOfAnImportIsOneTransaction() = scope.runTest {
        val mill = store.machines.createProfile(MachineProfile.create(name = "Mill")).valueOrNull()!!
        val old = ProbeProfile(id = "old", profileId = mill.id, name = "Old probe")
        store.probes.saveProbeProfile(old)
        store.machines.updateProfile(mill.id) { profile -> profile.copy(probeRoles = ProbeRoles(edgeFinderProfileId = "old")) }

        // A profile of a machine that does not exist breaks the write: nothing changes.
        val broken = ProbeProfile(id = "new", profileId = "no-such-machine", name = "New probe")
        assertFails { store.probes.replaceProbeProfiles(listOf("old"), listOf(broken)) }
        assertEquals(listOf("Old probe"), store.probes.library(mill.id).first().filter { !it.standardTip }.map { it.name })
        assertEquals("old", store.machines.loadProfile(mill.id)!!.probeRoles.edgeFinderProfileId)

        // The good write deletes the old profile with its role, and adds the new one.
        val written = store.probes.replaceProbeProfiles(listOf("old"), listOf(broken.copy(profileId = mill.id)))
        assertEquals(1, written)
        assertEquals(listOf("New probe"), store.probes.library(mill.id).first().filter { !it.standardTip }.map { it.name })
        assertNull(store.machines.loadProfile(mill.id)!!.probeRoles.edgeFinderProfileId)
    }
}
