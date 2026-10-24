package org.superdro.core.ui.navigation

import org.superdro.core.functions.errormap.MapKind
import org.superdro.core.model.AxisId

/**
 * The key of an entry in the back stack of one device tab. Each
 * tab has its own back stack. Its root is [ReadoutRoute]. The device of the tab is not in the
 * route: the entry gets it from its [TabEntryScope].
 *
 * The type is open on purpose. An area can add a route in this file and register its entry in
 * its own entry file (`settingsEntries()`, `adapterEntries()`, `functionEntries()`,
 * `graphicsEntries()`).
 */
interface TabRoute

/**
 * The key of an entry in the global back stack: the screens of the gear, the overflow menu, and
 * the add-device sheet. A global entry shows above the content of the
 * selected tab.
 */
interface GlobalRoute

// This file has one route for each screen, sheet, and dialog of SuperDRO, and for each other entry
// of the global overflow menu. A menu (for example the overflow menu of the sub-datum panel) is not
// a route: it opens at its anchor.
//
// The comment of each route gives the kind of its entry and the area that implements it:
// frame (the app frame and the readout, `core:ui` package `tabs` and `readout`), settings,
// adapter, functions, or graphics. A placeholder entry shows "This screen is under construction"
// until the area registers the real entry.

// ---------------------------------------------------------------------------------------------
// Frame and readout (implemented)

/** Screen, frame: the readout screen, the root of each tab. */
data object ReadoutRoute : TabRoute

/** Sheet, frame: the keypad of [axis] and the RPM keypad. */
data class KeypadRoute(val axis: AxisId) : TabRoute

/** Sheet, frame: the axis details of [axis]. */
data class AxisDetailsRoute(val axis: AxisId) : TabRoute

/** Sheet, frame: the divisions keypad of the angular axis. */
data object DivisionsRoute : TabRoute

/** Sheet, frame: the preset sheet of the tachometer with large chips. */
data object TachometerPresetRoute : TabRoute

/** Sheet, frame: the add-device sheet with the device list and the machine setup. */
data object AddDeviceRoute : GlobalRoute

/** Screen, frame: the remembered machines pane. */
data object RememberedMachinesRoute : GlobalRoute

/** Dialog, frame: rename the machine [profileId] (tab menu "Rename"). */
data class RenameMachineRoute(val profileId: String) : GlobalRoute

/** Dialog, frame: edit the remembered machine [profileId]: the name and the link (pane "Edit"). */
data class EditMachineRoute(val profileId: String) : GlobalRoute

/** Dialog, frame: the reason for the battery optimization exemption before the system request. */
data object BatteryOptimizationRoute : GlobalRoute

// ---------------------------------------------------------------------------------------------
// Settings area

/** Screen, settings: the machine settings of the tab. */
data object MachineSettingsRoute : TabRoute

/**
 * Screen, settings: the machine settings of the stored profile [profileId] with no tab. The machine
 * profiles screen opens it for a profile that has no tab.
 */
data class OfflineMachineSettingsRoute(val profileId: String) : GlobalRoute

/** Screen, settings: the calibration wizard. Null [axis]: the first axis that needs it. */
data class CalibrationWizardRoute(val axis: AxisId? = null) : TabRoute

/** Screen, settings: the import preview of a tool file, a sub-datum file, or a profile file. */
data object ImportPreviewRoute : TabRoute

/** Screen, settings: the report screen of an import or an error map. */
data object ReportRoute : TabRoute

/** Screen, settings: the profile file export and import of the tab profile. */
data object ProfileFileRoute : TabRoute

/** Screen, settings: the app settings (global settings, the gear). */
data object AppSettingsRoute : GlobalRoute

/** Screen, settings: the machine profiles. */
data object MachineProfilesRoute : GlobalRoute

/** Dialog, settings: the delete question of the profile [profileId]. */
data class DeleteProfileRoute(val profileId: String) : GlobalRoute

/** Screen, settings: the about screen with the version and the licenses. */
data object AboutRoute : GlobalRoute

/**
 * Screen, settings: the about screen at the help text of the topic [topic] (the name of a
 * `HelpTopic`). The help button of a screen opens it.
 */
data class HelpRoute(val topic: String) : GlobalRoute

/** Screen, settings: the log screen. */
data object LogsRoute : GlobalRoute

/** Screen, settings: the data output. */
data object DataOutputRoute : GlobalRoute

/**
 * Screen, settings: the data output screen with the diagnostics open in its status section.
 * A tap on the status chip opens it in the warning state or with "Always on".
 */
data object DataOutputDetailsRoute : GlobalRoute

/** Screen, settings: the key mapping settings. */
data object KeyMappingRoute : GlobalRoute

/** Screen, settings: the MCP server (`Settings > MCP server`). */
data object McpSettingsRoute : GlobalRoute

/**
 * Screen, settings: the MCP server screen with the audit log open in its diagnostics section.
 * A tap on the status chip opens it in the problem state or with "Always on".
 */
data object McpDiagnosticsRoute : GlobalRoute

// ---------------------------------------------------------------------------------------------
// Adapter area

/** Screen, adapter: the adapter details of the tab. */
data object AdapterDetailsRoute : TabRoute

/** Screen, adapter: the encoder settings of [input]. A tap on "Error" opens it. */
data class EncoderSettingsRoute(val input: Char) : TabRoute

/** Screen, adapter: the map editor of [input]: the error map, or the backlash map of a leadscrew encoder ([kind]). */
data class MapEditorRoute(val input: Char, val kind: MapKind = MapKind.Error) : TabRoute

/** Screen, adapter: the error map wizard of [input]. */
data class ErrorMapWizardRoute(val input: Char) : TabRoute

/** Screen, adapter: the error map report of [input]. */
data class ErrorMapReportRoute(val input: Char) : TabRoute

/** Screen, adapter: the error map diagnostics of [input]. */
data class ErrorMapDiagnosticsRoute(val input: Char) : TabRoute

/** Sheet, adapter: the probe input settings. */
data object ProbeInputRoute : TabRoute

/** Sheet, adapter: the tachometer pulses. */
data object TachPulsesRoute : TabRoute

/** Dialog, adapter: the "new adapter" prompt. */
data object NewAdapterPromptRoute : TabRoute

/** Screen, adapter: the adapters. */
data object AdaptersRoute : GlobalRoute

/** Screen, adapter: the offline details of the adapter profile [adapterProfileId]. */
data class OfflineAdapterDetailsRoute(val adapterProfileId: String) : GlobalRoute

/** Screen, adapter: the probe library. */
data object ProbeLibraryRoute : GlobalRoute

/** Screen, adapter: the probe profile [probeProfileId], or a new profile. */
data class ProbeProfileRoute(val probeProfileId: String? = null) : GlobalRoute

/** Screen, adapter: the tip calibration of the probe profile [probeProfileId]. */
data class TipCalibrationRoute(val probeProfileId: String) : GlobalRoute

/** Screen, adapter: the calibration wizard of the adapter input [input], from "Calibrate" of the encoder settings. */
data class InputCalibrationRoute(val input: Char) : TabRoute

/** Screen, adapter: the encoder settings of [input] of the stored adapter profile [adapterProfileId], with no link. */
data class OfflineEncoderSettingsRoute(val adapterProfileId: String, val input: Char) : GlobalRoute

/** Screen, adapter: the map editor of [input] of the stored adapter profile [adapterProfileId], with no link. */
data class OfflineMapEditorRoute(val adapterProfileId: String, val input: Char, val kind: MapKind = MapKind.Error) : GlobalRoute

/** Sheet, adapter: the tachometer pulses of the stored adapter profile [adapterProfileId]. */
data class OfflineTachPulsesRoute(val adapterProfileId: String) : GlobalRoute

/** Sheet, adapter: the probe input settings of the stored adapter profile [adapterProfileId]. */
data class OfflineProbeInputRoute(val adapterProfileId: String) : GlobalRoute

// ---------------------------------------------------------------------------------------------
// Functions area

/** Screen, functions: the hole circle with a point preview. */
data object HoleCircleRoute : TabRoute

/** Screen, functions: the arbitrary hole circle; [advanced] for the long press. */
data class ArbitraryHoleCircleRoute(val advanced: Boolean = false) : TabRoute

/** Screen, functions: the hole grid. */
data object HoleGridRoute : TabRoute

/** Screen, functions: the equidistant array. */
data object EquidistantArrayRoute : TabRoute

/** Screen, functions: the arc cut. */
data object ArcCutRoute : TabRoute

/** Sheet, functions: the mill tool radius compensation. */
data object RadiusCompensationRoute : TabRoute

/** Sheet, functions: the mill tool height compensation. */
data object HeightCompensationRoute : TabRoute

/** Sheet, functions: the lathe tool offset. */
data object LatheToolOffsetRoute : TabRoute

/** Sheet, functions: the Z reference. */
data object ZReferenceRoute : TabRoute

/** Sheet, functions: the angle measure. */
data object AngleMeasureRoute : TabRoute

/** Sheet, functions: the dimension measure with the caliper. */
data object DimensionMeasureRoute : TabRoute

/** Screen, functions: the edge finder. */
data object EdgeFinderRoute : TabRoute

/** Screen, functions: the thread setup. */
data object ThreadSetupRoute : TabRoute

/** Sheet, functions: the compound slide compensation. */
data object CompoundRoute : TabRoute

/** Sheet, functions: the head tilt compensation. */
data object HeadTiltRoute : TabRoute

/** Sheet, functions: the rotate part. */
data object RotatePartRoute : TabRoute

/** Sheet, functions: the align axes of the measurement mode. */
data object AlignAxesRoute : TabRoute

/** Sheet, functions: the reference instrument of the measurement mode. */
data object InstrumentRoute : TabRoute

/** Sheet, functions: the workspaces. */
data object WorkspacesRoute : TabRoute

/** Dialog, functions: the delete question of the workspace [workspaceId]. */
data class DeleteWorkspaceRoute(val workspaceId: String) : TabRoute

/** Screen, functions: the tool library. */
data object ToolLibraryRoute : TabRoute

/** Sheet, functions: the tool edit of [toolId], or a new tool for a mill or a lathe. */
data class ToolEditRoute(val toolId: String? = null) : TabRoute

/** Dialog, functions: the delete question of the tool [toolId]. */
data class DeleteToolRoute(val toolId: String) : TabRoute

/** Sheet, functions: the shape menu: the long press on "+". */
data object ShapeMenuRoute : TabRoute

/** Sheet, functions: the sub-datum edit of [subDatumId]. Null: the custom point of a double tap on "+". */
data class SubDatumEditRoute(val subDatumId: String? = null) : TabRoute

/** Sheet, functions: the machine datum edit of [datumId], or a new datum. */
data class MachineDatumEditRoute(val datumId: String? = null) : TabRoute

/** The workspace transforms: flip, rotate, scale, and pair. */
enum class WorkspaceTransformKind { Flip, Rotate, Scale, Pair }

/**
 * Sheet, functions: one workspace transform with undo. The pair commands of a long press in the
 * graphical view give the selected point [pointAId] and the pressed point
 * [pointBId].
 */
data class WorkspaceTransformRoute(
    val kind: WorkspaceTransformKind,
    val pointAId: String? = null,
    val pointBId: String? = null,
) : TabRoute

/** Sheet, functions: the bulk tag of the sub-datums. */
data object TagSubDatumsRoute : TabRoute

/**
 * Screen, functions: the step and repeat of the hole grid in the step and repeat mode. An
 * empty [subDatumIds] repeats the workspace.
 */
data class StepAndRepeatRoute(val subDatumIds: List<String>) : TabRoute

/** Dialog, functions: the "Workspace Commands" dialog with the transform buttons. */
data object WorkspaceCommandsRoute : TabRoute

/**
 * Dialog, functions: the single tap of "+". The entry shows nothing: it
 * adds a sub-datum at the current position and leaves the back stack at once.
 */
data object AddCurrentPointRoute : TabRoute

/** The origin commands of one sub-datum. */
enum class SubDatumCommand { AlignToSpindle, AlignOriginToSubDatum }

/** Dialog, functions: the confirmation of an origin command of the sub-datum [subDatumId] with undo. */
data class SubDatumCommandRoute(val subDatumId: String, val command: SubDatumCommand) : TabRoute

// ---------------------------------------------------------------------------------------------
// Graphics area

/** Screen, graphics: the graphical view. */
data object GraphicalViewRoute : TabRoute

/** Dialog, graphics: add a guide (`AddGuideDialog`). */
data object AddGuideRoute : TabRoute

/** Screen, graphics: the reference image setup. */
data object ReferenceImageSetupRoute : TabRoute

/** Screen, graphics: the camera setup. */
data object CameraSetupRoute : TabRoute

/**
 * Screen, camera (`camera/CameraEntries.kt`): the camera screen with the live video, the
 * crosshairs, and the image scale. Only in SuperDRO.
 */
data object CameraRoute : TabRoute

/** Screen, graphics: the measurement log. */
data object MeasurementLogRoute : TabRoute

/** Screen, graphics: the drawing setup ("Set Up Drawing"). */
data object DrawingSetupRoute : TabRoute

/** Screen, graphics: the problem list of a drawing import ("Drawing Not Imported"). */
data object DrawingProblemsRoute : TabRoute

/** Screen, graphics: the cutter geometry of the tool [toolId]. */
data class ToolGeometryRoute(val toolId: String) : TabRoute
