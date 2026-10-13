package org.superdro.validation.store

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.superdro.core.functions.errormap.ErrorMapWizardSession
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.AppSettings
import org.superdro.core.model.AxisAlignment
import org.superdro.core.model.PartRotation
import org.superdro.core.model.SubDatum
import org.superdro.core.model.SubDatumGroupKind
import org.superdro.core.model.ToolGeometry
import org.superdro.core.store.files.ImportFileDetector
import org.superdro.core.store.files.ImportFileKind
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.DataOutputConfig
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.ProbeType
import org.superdro.core.model.SuperDroJson
import org.superdro.core.model.TabState
import org.superdro.core.model.ThemeMode
import org.superdro.core.model.Tool
import org.superdro.core.store.BUNDLED_SQLITE_VERSION
import org.superdro.core.store.checkSqliteVersion
import org.superdro.core.store.db.SuperDroDatabase
import org.superdro.core.store.isSupportedSqliteVersion
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.store.json.TabStore
import org.superdro.core.store.repository.AdapterRepository
import org.superdro.core.store.sqliteVersion
import org.superdro.core.store.valueOrNull

/**
 * The storage rules and the list of the data that SuperDRO does not store. The database is
 * `JdbcSqliteDriver.IN_MEMORY`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StorageRulesTest {

    private val harnesses = mutableListOf<StoreHarness>()

    private fun TestScope.store(): StoreHarness = StoreHarness(StandardTestDispatcher(testScheduler)).also(harnesses::add)

    @AfterTest
    fun closeAll() {
        harnesses.forEach(StoreHarness::close)
    }

    @Test
    fun the_sqlite_of_the_jvm_is_3_53_4_and_at_least_3_38_0() = runTest {
        val store = store()
        assertEquals("3.53.4", store.database.sqliteVersion())
        assertEquals("3.53.4", BUNDLED_SQLITE_VERSION)
        assertEquals("3.53.4", checkSqliteVersion(store.database))
        assertTrue(isSupportedSqliteVersion("3.38.0"))
        assertTrue(isSupportedSqliteVersion("3.53.4"))
        assertFalse(isSupportedSqliteVersion("3.37.2"))
        // API 24 has SQLite 3.9: the number compare of each part.
        assertFalse(isSupportedSqliteVersion("3.9.2"))
    }

    @Test
    fun schema_version_1_and_one_migration_file_for_each_schema_change() {
        val sqldelight = StoreFixtures.root.resolve("core/store/src/commonMain/sqldelight")
        assertTrue(sqldelight.resolve("databases/1.db").isFile, "1.db holds the schema version 1")
        // Each schema change after version 1 adds one migration n.sqm (n = 1, 2, ...).
        val migrations = sqldelight.walkTopDown().filter { file -> file.extension == "sqm" }.map { file -> file.nameWithoutExtension.toInt() }.sorted().toList()
        assertEquals((1..migrations.size).toList(), migrations)
        assertEquals(1L + migrations.size, SuperDroDatabase.Schema.version)
        val build = StoreFixtures.root.resolve("core/store/build.gradle.kts").readText()
        assertTrue("verifySqlDelightMigration" in build, "The migration check runs in check")
    }

    @Test
    fun the_tables_of_the_storage_table_exist() = runTest {
        val store = store()
        val tables = store.texts("SELECT name FROM sqlite_master WHERE type = 'table'").filterNotNull().toSet()
        listOf(
            "machine_profile", "workspace", "axis_state", "sub_datum", "sub_datum_tag", "guide", "machine_datum",
            "thread_spec", "tool", "adapter_profile", "encoder_profile", "encoder_map_point", "probe_profile",
            "drawing", "drawing_layer", "drawing_shape", "measurement_log",
        ).forEach { table -> assertTrue(table in tables, "table $table") }
    }

    @Test
    fun the_json_files_have_their_names_and_the_wizard_session_its_folder() {
        assertEquals("app-settings.json", AppSettingsStore.FILE_NAME)
        assertEquals("tabs.json", TabStore.FILE_NAME)
        // The wizard session file is `<files>/error_map_wizard/adapter_<id>_<INPUT>.emw`. The session type of core:functions gives the same path.
        assertEquals("error_map_wizard/adapter_abc_X.emw", AdapterRepository.wizardSessionName("abc", 'x'))
        assertEquals(ErrorMapWizardSession.relativePath("abc", 'x'), AdapterRepository.wizardSessionName("abc", 'x'))
    }

    @Test
    fun each_json_file_has_schemaVersion_and_an_old_file_loads() {
        listOf(
            SuperDroJson.encodeToString(AppSettings.serializer(), AppSettings()),
            SuperDroJson.encodeToString(TabState.serializer(), TabState()),
            SuperDroJson.encodeToString(MachineProfile.serializer(), MachineProfile.create()),
        ).forEach { text -> assertEquals("1", SuperDroJson.parseToJsonElement(text).jsonObject["schemaVersion"].toString()) }
        // A file with only some fields, and a field of a newer version, loads with the defaults.
        val old = SuperDroJson.decodeFromString(AppSettings.serializer(), """{"schemaVersion":1,"themeMode":"Dark","later":7}""")
        assertEquals(AppSettings(themeMode = ThemeMode.Dark), old)
        val profile = SuperDroJson.decodeFromString(MachineProfile.serializer(), """{"name":"Old"}""")
        assertEquals("Old", profile.name)
        assertEquals(MachineProfile.create().x, profile.x)
    }

    @Test
    fun lengths_are_real_micrometers_and_drawing_coordinates_integer_nanometers() = runTest {
        val store = store()
        val tables = store.texts("SELECT name FROM sqlite_master WHERE type = 'table'").filterNotNull()
        val columns = tables.flatMap { table -> store.columns(table).map { (name, type) -> Triple(table, name, type) } }
        val micrometers = columns.filter { (_, name, _) -> name.endsWith("_um") }
        val nanometers = columns.filter { (_, name, _) -> name.endsWith("_nm") }
        assertTrue(micrometers.any { it.first == "sub_datum" } && micrometers.any { it.first == "tool" })
        micrometers.forEach { (table, name, type) -> assertEquals("REAL", type, "$table.$name") }
        columns.filter { (_, name, _) -> name.endsWith("_arcsec") }.forEach { (table, name, type) -> assertEquals("REAL", type, "$table.$name") }
        assertTrue(nanometers.any { it.first == "drawing_shape" })
        nanometers.forEach { (table, name, type) -> assertEquals("INTEGER", type, "$table.$name") }
    }

    @Test
    fun ids_are_uuid_text_and_dates_are_iso_8601_utc_text() = runTest {
        val store = store()
        val created = store.machines.createProfile(MachineProfile.create(name = "Bridgeport").copy(createdOn = Instant.parse("2026-10-11T14:30:05.123Z")))
        val profile = assertNotNull(created.valueOrNull())
        assertTrue(StoreFixtures.UUID_FORM.matches(profile.id))
        val dates = store.texts("SELECT created_on FROM machine_profile")
        assertEquals(1, dates.size)
        assertTrue(Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$""").matches(dates.single()!!), "date text ${dates.single()}")
        assertEquals(Instant.parse("2026-10-11T14:30:05.123Z"), Instant.parse(dates.single()!!))
    }

    @Test
    fun a_write_that_fails_rolls_back_the_full_transaction() = runTest {
        val store = store()
        val profile = MachineProfile.create(name = "Rollback")
        assertFailsWith<IllegalStateException> {
            store.writer.write { db ->
                db.machineProfileQueries.insert(
                    id = profile.id,
                    name = profile.name,
                    machine_type = profile.machineType,
                    metric_default = false,
                    use_shared_tools = false,
                    adapter_profile_id = null,
                    active_workspace_id = null,
                    config_json = "{}",
                    created_on = profile.createdOn,
                    updated_on = profile.updatedOn,
                )
                error("stop")
            }
        }
        assertEquals(emptyList(), store.texts("SELECT id FROM machine_profile"))
    }

    @Test
    fun only_core_store_calls_the_generated_queries() {
        val queryCall = Regex("""\.\w+Queries\.\w+\(""")
        val offenders = listOf("core", "platform", "app")
            .map(StoreFixtures.root::resolve)
            .flatMap { directory -> directory.walkTopDown().onEnter { it.name != "build" }.filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { file -> "/src/" in file.invariantSeparatorsPath }
            .filterNot { file -> file.invariantSeparatorsPath.contains("/core/store/") }
            .filterNot { file -> Regex("""/src/\w*[Tt]est\w*/""").containsMatchIn(file.invariantSeparatorsPath) }
            .filter { file -> queryCall.containsMatchIn(file.readText()) }
        assertEquals(emptyList(), offenders.map { it.relativeTo(StoreFixtures.root).path })
    }

    @Test
    fun a_json_store_writes_after_a_change_and_a_new_store_reads_it() = runTest {
        val store = store()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = AppSettingsStore(store.io, dispatcher = dispatcher)
        first.awaitLoaded()
        first.update { settings -> settings.copy(themeMode = ThemeMode.Dark, largeButtons = true) }
        first.awaitWrites()
        assertTrue(store.folder.resolve("app-settings.json").isFile)
        val second = AppSettingsStore(store.io, dispatcher = dispatcher)
        second.awaitLoaded()
        assertEquals(ThemeMode.Dark, second.settings.value.themeMode)
        assertTrue(second.settings.value.largeButtons)

        val tabs = TabStore(store.io, dispatcher = dispatcher)
        tabs.awaitLoaded()
        tabs.remember("p1")
        tabs.remember("p2")
        tabs.setPaused("p2", true)
        tabs.select("p1")
        tabs.awaitWrites()
        val tabsAgain = TabStore(store.io, dispatcher = dispatcher)
        tabsAgain.awaitLoaded()
        assertEquals(listOf("p1", "p2"), tabsAgain.state.value.remembered.map { it.profileId })
        assertEquals(listOf(false, true), tabsAgain.state.value.remembered.map { it.paused })
        assertEquals("p1", tabsAgain.state.value.selectedProfileId)
        assertEquals(listOf("p1"), tabsAgain.wantedProfileIds.first())
    }

    @Test
    fun an_old_settings_file_without_the_new_fields_loads() = runTest {
        val store = store()
        store.folder.mkdirs()
        store.folder.resolve("app-settings.json").writeText("""{"schemaVersion":1,"themeMode":"Dark"}""")
        val settings = AppSettingsStore(store.io, dispatcher = StandardTestDispatcher(testScheduler))
        settings.awaitLoaded()
        assertEquals(AppSettings(themeMode = ThemeMode.Dark), settings.settings.value)
    }

    @Test
    fun the_data_output_configuration_is_part_of_the_machine_profile() = runTest {
        val store = store()
        val profile = assertNotNull(store.machines.createProfile(MachineProfile.create(name = "Out")).valueOrNull())
        val changed = DataOutputConfig(enabled = true, port = 5900, precision = 2)
        store.machines.updateProfile(profile.id) { it.copy(dataOutput = changed) }
        assertEquals(changed, store.machines.loadProfile(profile.id)!!.dataOutput)
    }

    @Test
    fun a_text_setting_with_the_value_true_stays_a_text() = runTest {
        val store = store()
        val profile = assertNotNull(store.machines.createProfile(MachineProfile.create(name = "Typed")).valueOrNull())
        store.machines.updateProfile(profile.id) { it.copy(x = it.x.copy(label = "true"), tachometer = it.tachometer.copy(label = "false")) }
        val loaded = store.machines.loadProfile(profile.id)!!
        assertEquals("true", loaded.x.label)
        assertEquals("false", loaded.tachometer.label)
    }

    @Test
    fun the_workspace_state_is_typed_and_keeps_a_value_with_colons() = runTest {
        val store = store()
        val profile = assertNotNull(store.machines.createProfile(MachineProfile.create(name = "Colons")).valueOrNull())
        val workspaceId = store.machines.workspaces(profile.id).first().single().id
        store.machines.updateWorkspace(workspaceId) { workspace ->
            workspace.copy(
                activeToolId = "a:b:c",
                activeSubDatumId = "10:20:30",
                axisStates = workspace.axisStates + (AxisId.X to AxisState(absoluteOffset = -12.5, toolOffset = 3.0, toolOffsetActive = true)),
            )
        }
        val loaded = store.machines.loadWorkspace(workspaceId)!!
        assertEquals("a:b:c", loaded.activeToolId)
        assertEquals("10:20:30", loaded.activeSubDatumId)
        assertEquals(AxisState(absoluteOffset = -12.5, toolOffset = 3.0, toolOffsetActive = true), loaded.axisStates[AxisId.X])
        val stored = store.texts("SELECT state_json FROM workspace").joinToString()
        assertFalse("w_state" in stored, "no key with the w_state prefix: $stored")
    }

    @Test
    fun the_unused_tables_and_columns_are_not_in_the_model() = runTest {
        val store = store()
        val tables = store.texts("SELECT name FROM sqlite_master WHERE type = 'table'").filterNotNull().map(String::lowercase)
        assertTrue(tables.none { "layout" in it || "feature" in it }, "tables $tables")
        val toolColumns = store.columns("tool").map { it.first.lowercase() }
        assertTrue(toolColumns.none { "favorite" in it || "y_offset" in it }, "tool columns $toolColumns")
        val subDatumColumns = store.columns("sub_datum").map { it.first.lowercase() }
        assertTrue(subDatumColumns.none { it == "hide" || it == "deleted" }, "sub-datum columns $subDatumColumns")
        // The A coordinate is used now.
        assertTrue(subDatumColumns.any { it.startsWith("a_") || it == "a" }, "sub-datum columns $subDatumColumns")
        val toolKeys = SuperDroJson.encodeToJsonElement(Tool.serializer(), Tool()).jsonObject.keys.map(String::lowercase)
        assertTrue(toolKeys.none { "favorite" in it || "yoffset" in it }, "tool keys $toolKeys")
    }

    @Test
    fun the_stored_models_have_no_license_cloud_or_device_keys() {
        val forbidden = Regex("(?i)license|plus|installation|deviceid|entitlement|cloud|never_send")
        listOf(
            SuperDroJson.encodeToJsonElement(AppSettings.serializer(), AppSettings()),
            SuperDroJson.encodeToJsonElement(MachineProfile.serializer(), MachineProfile.create()),
            SuperDroJson.encodeToJsonElement(DataOutputConfig.serializer(), DataOutputConfig()),
        ).forEach { element ->
            val keys = keysOf(element)
            assertTrue(keys.none(forbidden::containsMatchIn), "keys ${keys.filter(forbidden::containsMatchIn)}")
        }
        // The MQTT credentials and the PSK are not in the configuration.
        val outputKeys = keysOf(SuperDroJson.encodeToJsonElement(DataOutputConfig.serializer(), DataOutputConfig()))
        assertTrue(outputKeys.none { key -> Regex("(?i)password|username|secret|pskkey|psk_key").containsMatchIn(key) }, "keys $outputKeys")
    }

    @Test
    fun the_probe_table_has_the_six_standard_tips() = runTest {
        val store = store()
        val tips = store.probes.standardTips().first()
        val expected = listOf(
            Triple("Edge finder 0.100\"", 2540.0, false),
            Triple("Edge finder 0.200\"", 5080.0, false),
            Triple("Edge finder 0.500\"", 12700.0, false),
            Triple("Edge finder 4 mm", 4000.0, true),
            Triple("Edge finder 6 mm", 6000.0, true),
            Triple("Edge finder 10 mm", 10000.0, true),
        )
        assertEquals(expected.toSet(), tips.map { Triple(it.name, it.stylusDiameterUm, it.metric) }.toSet())
        tips.forEach { tip ->
            assertEquals(ProbeType.EdgeFinder2D, tip.type)
            assertNull(tip.profileId)
            assertNull(tip.port)
            assertTrue(tip.standardTip)
        }
    }

    @Test
    fun each_adapter_address_of_a_profile_reads_back() = runTest {
        val store = store()
        listOf(
            AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3"),
            AdapterAddress.UsbSerial(vendorId = 0x0403, productId = 0x6001, serial = "A1B2", baudRate = 9600, portIndex = 1),
            AdapterAddress.UsbSerial(vendorId = 0x1A86, productId = 0x7523),
            AdapterAddress.Tcp("dro.local"),
            AdapterAddress.Tcp("fe80::1", 9000),
            AdapterAddress.Ble("peripheral-1"),
            AdapterAddress.Fake("demo"),
        ).forEachIndexed { index, address ->
            // The rate of a USB link is the rate of the profile setting (`MachineProfile.usbBaudRate`).
            val rate = (address as? AdapterAddress.UsbSerial)?.baudRate ?: AdapterAddress.UsbSerial.DEFAULT_BAUD_RATE
            val created = MachineProfile.create(name = "Link $index").copy(adapter = address, usbBaudRate = rate)
            val profile = assertNotNull(store.machines.createProfile(created).valueOrNull())
            assertEquals(address, store.machines.loadProfile(profile.id)!!.adapter)
        }
        // A USB link with a different rate gets the rate of the setting (the setting does not change by itself).
        val usb = AdapterAddress.UsbSerial(vendorId = 0x0403, productId = 0x6001, serial = "C3", baudRate = 57_600)
        val mismatch = assertNotNull(store.machines.createProfile(MachineProfile.create(name = "Mismatch").copy(adapter = usb, usbBaudRate = 9_600)).valueOrNull())
        assertEquals(usb.copy(baudRate = 9_600), store.machines.loadProfile(mismatch.id)!!.adapter)
        assertEquals(9_600, store.machines.loadProfile(mismatch.id)!!.usbBaudRate)
    }

    @Test
    fun a_profile_round_trip_keeps_all_settings() = runTest {
        val store = store()
        val profile = MachineProfile.create(MachineType.Lathe, "South Bend").let { lathe ->
            lathe.copy(
                metricDefault = true,
                usbBaudRate = 9600,
                twoWayCommunication = false,
                display = lathe.display.copy(showSliders = false),
                x = lathe.x.copy(label = "Xd"),
                tachometer = lathe.tachometer.copy(enabled = true, presets = listOf(100.0, 200.0)),
                probe = lathe.probe.copy(enabled = true, channel = 'q'),
                use3dSubDatums = false,
            )
        }
        val stored = assertNotNull(store.machines.createProfile(profile).valueOrNull())
        val loaded = store.machines.loadProfile(stored.id)!!
        assertEquals(profile.copy(workspaces = emptyList(), activeWorkspaceId = loaded.activeWorkspaceId, tools = emptyList(), createdOn = loaded.createdOn, updatedOn = loaded.updatedOn), loaded.copy(workspaces = emptyList()))
    }

    @Test
    fun warning_the_sq_files_use_no_sql_math_function_and_no_percentile() {
        val math = Regex("""(?i)\b(acos|acosh|asin|asinh|atan|atan2|atanh|ceil|ceiling|cos|cosh|degrees|exp|ln|log|log10|log2|mod|pi|pow|power|radians|sin|sinh|sqrt|tan|tanh|trunc|percentile|percentile_cont|percentile_disc|median)\s*\(""")
        val files = StoreFixtures.root.resolve("core/store/src/commonMain/sqldelight").walkTopDown().filter { it.extension == "sq" }.toList()
        assertTrue(files.size >= 10)
        files.forEach { file ->
            val code = file.readLines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
            assertEquals(null, math.find(code)?.value, file.name)
        }
    }

    @Test
    fun the_new_fields_of_the_entities_are_stored() = runTest {
        val store = store()
        val profile = assertNotNull(store.machines.createProfile(MachineProfile.create(name = "Entity fields")).valueOrNull())
        val workspaceId = store.machines.workspaces(profile.id).first().single().id
        val point = SubDatum(name = "T1", x = 1.0, z = -2.0, tags = listOf("a", "b"), groupId = 7, groupKind = SubDatumGroupKind.Thread, groupRef = "thread-1")
        store.machines.insertSubDatums(workspaceId, listOf(point))
        val loaded = store.machines.subDatums(workspaceId).first().single()
        assertEquals(SubDatumGroupKind.Thread, loaded.groupKind)
        assertEquals("thread-1", loaded.groupRef)
        assertEquals(7L, loaded.groupId)
        assertEquals(listOf("a", "b"), loaded.tags)
        store.machines.updateWorkspace(workspaceId) { workspace ->
            workspace.copy(
                partRotation = PartRotation(active = true, angleArcSec = 3600.0, pivotXUm = 10.0),
                axisAlignment = AxisAlignment(active = true, angleArcSec = -60.0),
                armedThreadId = "thread-1",
            )
        }
        val workspace = store.machines.loadWorkspace(workspaceId)!!
        assertEquals(PartRotation(active = true, angleArcSec = 3600.0, pivotXUm = 10.0), workspace.partRotation)
        assertEquals(AxisAlignment(active = true, angleArcSec = -60.0), workspace.axisAlignment)
        assertEquals("thread-1", workspace.armedThreadId)
        val tool = assertNotNull(store.tools.addTool(profile.id, Tool(name = "With geometry", geometry = ToolGeometry(drawnIn = 25.4))).valueOrNull())
        assertEquals(25.4, store.tools.library(profile.id).first().single { it.id == tool.id }.geometry?.drawnIn)
    }

    @Test
    fun a_profile_delete_closes_its_tab() = runTest {
        val store = store()
        val tabs = TabStore(store.io, dispatcher = StandardTestDispatcher(testScheduler))
        tabs.awaitLoaded()
        tabs.remember("p1")
        tabs.remember("p2")
        tabs.select("p2")
        tabs.forget("p2")
        assertEquals(listOf("p1"), tabs.state.value.remembered.map { it.profileId })
        assertNull(tabs.state.value.selectedProfileId)
    }

    @Test
    fun a_touchdro_database_file_is_not_an_import_file() {
        val header = "SQLite format 3".encodeToByteArray() + byteArrayOf(0) + ByteArray(200) { 0x01 }
        assertEquals(ImportFileKind.Unknown, ImportFileDetector.detect(header).kind)
    }

    private fun keysOf(element: kotlinx.serialization.json.JsonElement): List<String> = when (element) {
        is kotlinx.serialization.json.JsonObject -> element.keys.toList() + element.values.flatMap(::keysOf)
        is kotlinx.serialization.json.JsonArray -> element.flatMap(::keysOf)
        else -> emptyList()
    }
}
