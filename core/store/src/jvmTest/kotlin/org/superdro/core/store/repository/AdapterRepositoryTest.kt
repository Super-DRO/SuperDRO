package org.superdro.core.store.repository

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.BacklashMode
import org.superdro.core.model.Calibration
import org.superdro.core.model.CalibrationStatus
import org.superdro.core.model.Channels
import org.superdro.core.model.DeclaredInput
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.EncoderType
import org.superdro.core.model.IdentitySource
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.MapPoint
import org.superdro.core.model.ProbeContactType
import org.superdro.core.store.StoreFixture
import org.superdro.core.store.StoreRefusal
import org.superdro.core.store.StoreResult
import org.superdro.core.store.files.Fixtures
import org.superdro.core.store.files.adapter.AdapterProfileImporter
import org.superdro.core.store.files.adapter.AdapterTarget
import org.superdro.core.store.refusalOrNull
import org.superdro.core.store.valueOrNull

class AdapterRepositoryTest {

    private val scope = TestScope()
    private val store = StoreFixture(scope)

    @AfterTest
    fun close() = store.close()

    private fun adapter(alias: String = "Mill adapter") = AdapterProfile(
        alias = alias,
        model = "DRO-ESP32",
        identity = "24:6F:28:A1:B2:C3",
        identitySource = IdentitySource.BtMac,
        lastLink = AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3"),
        declaredInputs = setOf(DeclaredInput.X, DeclaredInput.Probe),
        probeContactType = ProbeContactType.NormallyOpen,
        probeDebounceMs = 50L,
        createdOn = Instant.parse("2026-10-01T10:00:00Z"),
    )

    @Test
    fun anAdapterProfileKeepsAllValuesAndTheMaps() = scope.runTest {
        val x = EncoderProfile.defaultFor(Channels.X).copy(
            label = "Table",
            type = EncoderType.LeadscrewEncoder,
            resolution = 2000.0,
            countsPerScrewRev = 400.0,
            reversed = true,
            filter = true,
            backlashMode = BacklashMode.Map,
            backlashCounts = 3L,
            errorMap = listOf(MapPoint(0, 0), MapPoint(1000, -2), MapPoint(2000, 5)),
            backlashMap = listOf(MapPoint(0, 4)),
            calibration = Calibration(status = CalibrationStatus.Calibrated, calibratedOn = Instant.parse("2026-10-02T00:00:00Z")),
        )
        val w = EncoderProfile.defaultFor(Channels.W, EncoderKind.Angular).copy(errorMapCyclic = false)
        val profile = adapter().copy(encoders = mapOf('x' to x, 'w' to w))
        store.adapters.saveAdapterProfile(profile)

        // The store writes the identity in lower case.
        val stored = profile.copy(identity = "24:6f:28:a1:b2:c3")
        assertEquals(stored, store.adapters.loadAdapterProfile(profile.id))
        assertEquals(stored, store.adapters.adapterProfile(profile.id).first())
        // The list has the encoders but not the maps.
        val summary = store.adapters.adapterProfiles().first().single()
        assertEquals(emptyList(), summary.encoders.getValue('x').errorMap)
        assertEquals(2000.0, summary.encoders.getValue('x').resolution)
        assertEquals(listOf(stored), store.adapters.findByIdentity("24:6F:28:A1:B2:C3", IdentitySource.BtMac))

        // An input that is not in the new profile goes with its maps.
        store.adapters.saveAdapterProfile(profile.copy(encoders = mapOf('w' to w)))
        assertEquals(setOf('w'), store.adapters.loadAdapterProfile(profile.id)!!.encoders.keys)
        assertEquals(0L, store.count("encoder_map_point"))
    }

    @Test
    fun aTdapProfileWithAnUpperCaseMacMatchesTheLowerCaseMacOfTheLiveLink() = scope.runTest {
        // The file has "identity": "24:0A:C4:12:34:58". The live Bluetooth link gives lower case.
        val imported = checkNotNull(AdapterProfileImporter(ids = { "imported" }).read(Fixtures.bytes("adapter-3.32.00.tdap"), AdapterTarget.NewAdapter).adapter)
        store.adapters.saveAdapterProfile(imported)

        val found = store.adapters.findByIdentity("24:0a:c4:12:34:58", IdentitySource.BtMac)
        assertEquals(listOf("imported"), found.map(AdapterProfile::id))
        assertEquals("24:0a:c4:12:34:58", found.single().identity)
        assertEquals(5080.0, found.single().encoders.getValue('x').resolution)
        // The upper case form and a form with spaces find the same profile.
        assertEquals(listOf("imported"), store.adapters.findByIdentity(" 24:0A:C4:12:34:58 ", IdentitySource.BtMac).map(AdapterProfile::id))
        assertEquals(emptyList(), store.adapters.findByIdentity("24:0a:c4:12:34:58", IdentitySource.AdapterReported))
    }

    @Test
    fun aStoredUpperCaseIdentityMatchesWithNoChangeOfLetterCase() = scope.runTest {
        // A row that a version before the migration 2.sqm wrote.
        store.adapters.saveAdapterProfile(adapter())
        store.driver.execute(null, "UPDATE adapter_profile SET identity = 'AA:BB:CC:DD:EE:FF'", 0)
        assertEquals(1, store.adapters.findByIdentity("aa:bb:cc:dd:ee:ff", IdentitySource.BtMac).size)
    }

    @Test
    fun theAliasIsUniqueNotBlankAndHasAMaximumOf40Characters() = scope.runTest {
        store.adapters.saveAdapterProfile(adapter("Mill adapter"))
        assertEquals(StoreRefusal.AdapterAliasExists, store.adapters.saveAdapterProfile(adapter("MILL ADAPTER")).refusalOrNull())
        assertEquals(StoreRefusal.AdapterAliasBlank, store.adapters.saveAdapterProfile(adapter(" ")).refusalOrNull())
        assertEquals(StoreRefusal.AdapterAliasTooLong, store.adapters.saveAdapterProfile(adapter("A".repeat(41))).refusalOrNull())
        assertEquals("A".repeat(40), store.adapters.saveAdapterProfile(adapter("A".repeat(40))).valueOrNull()!!.alias)
    }

    @Test
    fun aMapOf20000PointsIsOneTransaction() = scope.runTest {
        val profile = adapter().copy(encoders = emptyMap())
        store.adapters.saveAdapterProfile(profile)
        val points = List(EncoderProfile.MAX_MAP_POINTS) { index -> MapPoint(index * 10L, (index % 7) - 3L) }
        store.adapters.replaceMap(profile.id, 'Y', MapKind.Error, points)
        val stored = store.adapters.loadAdapterProfile(profile.id)!!
        assertEquals(points, stored.encoders.getValue('y').errorMap)
        // The input had no encoder profile: it got the default first.
        assertEquals(EncoderProfile.DEFAULT_LINEAR_RESOLUTION, stored.encoders.getValue('y').resolution)
        store.adapters.replaceMap(profile.id, 'y', MapKind.Error, listOf(MapPoint(1, 1)))
        assertEquals(1L, store.count("encoder_map_point"))
    }

    @Test
    fun aForgetRemovesTheEncodersTheMapsTheSessionsAndTheProfileLink() = scope.runTest {
        val profile = adapter().copy(encoders = mapOf('x' to EncoderProfile.defaultFor('x').copy(errorMap = listOf(MapPoint(0, 1)))))
        store.adapters.saveAdapterProfile(profile)
        val machine = store.machines.createProfile(MachineProfile.create(MachineType.VerticalMill).copy(adapterProfileId = profile.id)).valueOrNull()!!
        assertEquals(profile.id, machine.adapterProfileId)
        store.adapters.saveWizardSession(profile.id, 'x', TestSession.serializer(), TestSession(listOf(1.0)))

        store.adapters.deleteAdapterProfile(profile.id)

        assertEquals(0L, store.count("adapter_profile"))
        assertEquals(0L, store.count("encoder_profile"))
        assertEquals(0L, store.count("encoder_map_point"))
        assertNull(store.machines.loadProfile(machine.id)!!.adapterProfileId)
        assertFalse(store.storeIO.exists(AdapterRepository.wizardSessionName(profile.id, 'x')))
    }

    @Test
    fun aWizardSessionIsAFileOfTheAppFiles() = scope.runTest {
        val name = AdapterRepository.wizardSessionName("adapter-1", 'x')
        assertEquals("error_map_wizard/adapter_adapter-1_X.emw", name)
        assertNull(store.adapters.loadWizardSession("adapter-1", 'x', TestSession.serializer()))

        val session = TestSession(listOf(0.0, 25.4, 50.8))
        store.adapters.saveWizardSession("adapter-1", 'x', TestSession.serializer(), session)
        assertEquals(session, store.adapters.loadWizardSession("adapter-1", 'X', TestSession.serializer()))
        val text = store.storeIO.text(name)!!
        assertTrue(text.startsWith("""{"schemaVersion":1,"adapterProfileId":"adapter-1","input":"x","session":"""), text)
        assertEquals(listOf("$name.tmp -> $name"), store.storeIO.moves)

        store.storeIO.putText(name, "not json")
        assertNull(store.adapters.loadWizardSession("adapter-1", 'x', TestSession.serializer()))

        store.adapters.deleteWizardSession("adapter-1", 'x')
        assertFalse(store.storeIO.exists(name))
    }

    @Test
    fun aSessionFileOfTheOlderNameMovesToTheNameOfF2314() = scope.runTest {
        val legacy = "wizard/adapter-1-x.json"
        store.storeIO.putText(legacy, """{"schemaVersion":1,"adapterProfileId":"adapter-1","input":"x","session":{"samples":[1.5]}}""")
        assertEquals(TestSession(listOf(1.5)), store.adapters.loadWizardSession("adapter-1", 'X', TestSession.serializer()))
        assertFalse(store.storeIO.exists(legacy))
        assertTrue(store.storeIO.exists("error_map_wizard/adapter_adapter-1_X.emw"))
        store.storeIO.putText(legacy, "{}")
        store.adapters.deleteWizardSession("adapter-1", 'x')
        assertFalse(store.storeIO.exists(legacy), "a delete removes the two names")
        assertFalse(store.storeIO.exists("error_map_wizard/adapter_adapter-1_X.emw"))
    }

    @Test
    fun theCommentsOfAnErrorMapStayAfterAReload() = scope.runTest {
        val comments = listOf("# generated-by: SuperDRO Error Map Wizard", "# passes: 3", "# a note")
        val encoder = EncoderProfile.defaultFor('x').copy(errorMap = listOf(MapPoint(0, 0), MapPoint(1000, 2)), errorMapComments = comments)
        val profile = AdapterProfile(id = "a1", alias = "Mill adapter", encoders = mapOf('x' to encoder))
        assertIs<StoreResult.Ok<AdapterProfile>>(store.adapters.saveAdapterProfile(profile))
        assertEquals(comments, store.adapters.loadAdapterProfile("a1")!!.encoders.getValue('x').errorMapComments)
        // One encoder profile write keeps the new comments too.
        assertIs<StoreResult.Ok<EncoderProfile>>(store.adapters.saveEncoderProfile("a1", encoder.copy(errorMapComments = listOf("# b"))))
        assertEquals(listOf("# b"), store.adapters.loadAdapterProfile("a1")!!.encoders.getValue('x').errorMapComments)
    }

    @Serializable
    data class TestSession(val samples: List<Double> = emptyList())
}
