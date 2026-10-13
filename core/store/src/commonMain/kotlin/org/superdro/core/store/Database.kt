package org.superdro.core.store

import app.cash.sqldelight.db.SqlDriver
import org.superdro.core.model.AxisId
import org.superdro.core.model.BacklashMode
import org.superdro.core.model.CameraModeStatus
import org.superdro.core.model.DrawingFormat
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderType
import org.superdro.core.model.IdentitySource
import org.superdro.core.model.MachineType
import org.superdro.core.model.ProbeContactType
import org.superdro.core.model.ProbeType
import org.superdro.core.model.ShapeType
import org.superdro.core.model.SubDatumGroupKind
import org.superdro.core.model.ToolKind
import org.superdro.core.store.db.Adapter_profile
import org.superdro.core.store.db.Archived_item
import org.superdro.core.store.db.Axis_state
import org.superdro.core.store.db.Camera_mode_result
import org.superdro.core.store.db.Camera_selection
import org.superdro.core.store.db.Drawing
import org.superdro.core.store.db.Drawing_layer
import org.superdro.core.store.db.Drawing_shape
import org.superdro.core.store.db.Encoder_map_point
import org.superdro.core.store.db.Encoder_profile
import org.superdro.core.store.db.EnumColumnAdapter
import org.superdro.core.store.db.Guide
import org.superdro.core.store.db.InstantColumnAdapter
import org.superdro.core.store.db.IntColumnAdapter
import org.superdro.core.store.db.Machine_datum
import org.superdro.core.store.db.Machine_profile
import org.superdro.core.store.db.Measurement_log
import org.superdro.core.store.db.Probe_profile
import org.superdro.core.store.db.Sub_datum
import org.superdro.core.store.db.Sub_datum_tag
import org.superdro.core.store.db.SuperDroDatabase
import org.superdro.core.store.db.Thread_spec
import org.superdro.core.store.db.Tool
import org.superdro.core.store.db.Workspace
import org.superdro.core.store.db.Workspace_undo
import org.superdro.core.store.db.Workspace_undo_item

/**
 * Makes the database on [driver], with the column adapters of all tables. Each platform gives
 * its driver: `AndroidSqlDriverFactory` in `platform:android:store`, and `JdbcSqliteDriver`
 * in the JVM tests. Use one driver object for the process, because the flow notification works
 * only through the listeners of that object.
 *
 * The driver must turn on the foreign keys, else a delete does not remove the owned rows.
 * `AndroidSqlDriverFactory` does this, and a JVM driver gives
 * the property `foreign_keys` = `true`.
 */
fun createSuperDroDatabase(driver: SqlDriver): SuperDroDatabase = SuperDroDatabase(
    driver = driver,
    adapter_profileAdapter = Adapter_profile.Adapter(
        identity_sourceAdapter = EnumColumnAdapter(IdentitySource.entries, IdentitySource.BtMac),
        probe_contact_typeAdapter = EnumColumnAdapter(ProbeContactType.entries, ProbeContactType.Auto),
        last_seen_onAdapter = InstantColumnAdapter,
        created_onAdapter = InstantColumnAdapter,
    ),
    archived_itemAdapter = Archived_item.Adapter(
        machine_typeAdapter = machineTypeAdapter,
        archived_onAdapter = InstantColumnAdapter,
    ),
    axis_stateAdapter = Axis_state.Adapter(
        axisAdapter = axisAdapter,
        divisions_per_revAdapter = IntColumnAdapter,
    ),
    camera_mode_resultAdapter = Camera_mode_result.Adapter(
        statusAdapter = EnumColumnAdapter(CameraModeStatus.entries, CameraModeStatus.Bad),
        widthAdapter = IntColumnAdapter,
        heightAdapter = IntColumnAdapter,
        updated_onAdapter = InstantColumnAdapter,
    ),
    camera_selectionAdapter = Camera_selection.Adapter(updated_onAdapter = InstantColumnAdapter),
    drawingAdapter = Drawing.Adapter(
        source_formatAdapter = EnumColumnAdapter(DrawingFormat.entries, DrawingFormat.AsciiDxf),
        created_onAdapter = InstantColumnAdapter,
    ),
    drawing_layerAdapter = Drawing_layer.Adapter(layer_indexAdapter = IntColumnAdapter),
    drawing_shapeAdapter = Drawing_shape.Adapter(
        seqAdapter = IntColumnAdapter,
        layer_indexAdapter = IntColumnAdapter,
        typeAdapter = EnumColumnAdapter(ShapeType.entries, ShapeType.Line),
        styleAdapter = IntColumnAdapter,
    ),
    encoder_map_pointAdapter = Encoder_map_point.Adapter(seqAdapter = IntColumnAdapter),
    encoder_profileAdapter = Encoder_profile.Adapter(
        typeAdapter = EnumColumnAdapter(EncoderType.entries, EncoderType.Scale),
        kindAdapter = EnumColumnAdapter(EncoderKind.entries, EncoderKind.Linear),
        backlash_modeAdapter = EnumColumnAdapter(BacklashMode.entries, BacklashMode.None),
    ),
    guideAdapter = Guide.Adapter(axisAdapter = axisAdapter, created_onAdapter = InstantColumnAdapter),
    machine_datumAdapter = Machine_datum.Adapter(
        created_onAdapter = InstantColumnAdapter,
        updated_onAdapter = InstantColumnAdapter,
    ),
    machine_profileAdapter = Machine_profile.Adapter(
        machine_typeAdapter = machineTypeAdapter,
        created_onAdapter = InstantColumnAdapter,
        updated_onAdapter = InstantColumnAdapter,
    ),
    measurement_logAdapter = Measurement_log.Adapter(
        sequenceAdapter = IntColumnAdapter,
        created_onAdapter = InstantColumnAdapter,
    ),
    probe_profileAdapter = Probe_profile.Adapter(
        typeAdapter = EnumColumnAdapter(ProbeType.entries, ProbeType.TouchProbe),
        sample_countAdapter = IntColumnAdapter,
        calibrated_onAdapter = InstantColumnAdapter,
        last_used_onAdapter = InstantColumnAdapter,
    ),
    sub_datumAdapter = Sub_datum.Adapter(
        group_kindAdapter = EnumColumnAdapter(SubDatumGroupKind.entries, SubDatumGroupKind.Pattern),
        created_onAdapter = InstantColumnAdapter,
        updated_onAdapter = InstantColumnAdapter,
    ),
    sub_datum_tagAdapter = Sub_datum_tag.Adapter(positionAdapter = IntColumnAdapter),
    thread_specAdapter = Thread_spec.Adapter(
        created_onAdapter = InstantColumnAdapter,
        updated_onAdapter = InstantColumnAdapter,
    ),
    toolAdapter = Tool.Adapter(
        kindAdapter = EnumColumnAdapter(ToolKind.entries, ToolKind.Mill),
        tooth_countAdapter = IntColumnAdapter,
        created_onAdapter = InstantColumnAdapter,
        updated_onAdapter = InstantColumnAdapter,
    ),
    workspaceAdapter = Workspace.Adapter(
        last_ordinalAdapter = IntColumnAdapter,
        created_onAdapter = InstantColumnAdapter,
        updated_onAdapter = InstantColumnAdapter,
    ),
    workspace_undoAdapter = Workspace_undo.Adapter(created_onAdapter = InstantColumnAdapter),
    workspace_undo_itemAdapter = Workspace_undo_item.Adapter(seqAdapter = IntColumnAdapter),
)

private val machineTypeAdapter = EnumColumnAdapter(MachineType.entries, MachineType.VerticalMill)

private val axisAdapter = EnumColumnAdapter(AxisId.entries, AxisId.X)

/**
 * The SQLite of the driver must be 3.38.0 or higher.
 */
const val MIN_SQLITE_VERSION: String = "3.38.0"

/**
 * The SQLite version that SuperDRO bundles on Android and uses on the JVM.
 */
const val BUNDLED_SQLITE_VERSION: String = "3.53.4"

/** Gives the SQLite version of the driver, for example `3.53.4`. */
fun SuperDroDatabase.sqliteVersion(): String = sqliteVersionQueries.sqliteVersion().executeAsOne()

/**
 * True when [version] is [MIN_SQLITE_VERSION] or higher. The function compares the numbers of
 * each part, so `3.9.2` is lower than `3.38.0`.
 */
fun isSupportedSqliteVersion(version: String): Boolean {
    val parts = version.trim().split('.').map { part -> part.toIntOrNull() ?: return false }
    val minimum = MIN_SQLITE_VERSION.split('.').map(String::toInt)
    for (index in minimum.indices) {
        val value = parts.getOrElse(index) { 0 }
        if (value != minimum[index]) return value > minimum[index]
    }
    return true
}

/**
 * Stops with an [IllegalStateException] when the SQLite of [database] is lower than
 * [MIN_SQLITE_VERSION]. A debug build calls it after it opens the
 * database. An incorrect driver factory then fails at once, not at the first query that needs a
 * newer SQLite.
 *
 * @return The SQLite version of the driver.
 */
fun checkSqliteVersion(database: SuperDroDatabase): String {
    val version = database.sqliteVersion()
    check(isSupportedSqliteVersion(version)) {
        "SQLite $version is lower than $MIN_SQLITE_VERSION. Use the bundled SQLite $BUNDLED_SQLITE_VERSION."
    }
    return version
}
