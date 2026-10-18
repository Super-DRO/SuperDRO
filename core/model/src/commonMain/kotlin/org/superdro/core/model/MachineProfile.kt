package org.superdro.core.model

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/** The machine type (`machine_type` 0, 1, 2). The readout uses only "lathe or not lathe". */
@Serializable
enum class MachineType {
    VerticalMill,
    Lathe,
    HorizontalMill;

    val isLathe: Boolean get() = this == Lathe
}

/** The machine mode. The measurement mode is not available on a lathe. */
@Serializable
enum class MachineMode { Cutting, Measurement }

/**
 * The angle convention of the hole patterns. A new mill gets
 * [Standard]. Each lathe gets [Legacy]. An imported `.tdset` file keeps its value.
 */
@Serializable
enum class PatternAngleConvention { Standard, Legacy }

/** The type of the auxiliary readout on a lathe. */
@Serializable
enum class AuxiliaryType { Tailstock, CompoundSlide, Other }

/**
 * The auxiliary readout of a lathe (`y_type`, `y_vectoring`, `y_angle_reference`).
 *
 * @property angleReferenceDegrees The angle reference: 0, 90, or 180.
 */
@Serializable
data class AuxiliaryConfig(
    val type: AuxiliaryType = AuxiliaryType.Tailstock,
    val vectoring: Boolean = false,
    val angleReferenceDegrees: Int = 0,
)

/**
 * The probe profile of each function. Null means no selection.
 */
@Serializable
data class ProbeRoles(
    val edgeFinderProfileId: String? = null,
    val zReferenceProfileId: String? = null,
    val measurementInstrumentProfileId: String? = null,
)

/**
 * The content of the side panel. [MachineDatums] is the machine datum tab. It is the last value,
 * so the stored ordinals of the older values do not change.
 */
@Serializable
enum class SidePanelMode { Off, SubDatums, Tools, MachineDatums }

/**
 * The stored configuration of one machine. The user names each
 * machine. [MachineProfile.create] gives a new profile with the defaults.
 *
 * The constructor defaults are the values of a vertical mill. Use [create] for a lathe.
 */
@Serializable
data class MachineProfile(
    val schemaVersion: Int = SCHEMA_VERSION,
    val id: String = newId(),
    val name: String = DEFAULT_MILL_NAME,
    val machineType: MachineType = MachineType.VerticalMill,
    val metricDefault: Boolean = false,
    val adapter: AdapterAddress? = null,
    val adapterProfileId: String? = null,
    /**
     * The USB baud rate setting of the profile (`usb_baud_rate`). A stored
     * or imported value never changes by itself. This field is the one source of
     * truth of the rate: [withUsbBaudRateOnLink] puts it into [AdapterAddress.UsbSerial.baudRate]
     * of [adapter], and the store does that at each read and each write.
     */
    val usbBaudRate: Int = AdapterAddress.UsbSerial.DEFAULT_BAUD_RATE,
    val machineMode: MachineMode = MachineMode.Cutting,
    val patternAngleConvention: PatternAngleConvention = PatternAngleConvention.Standard,
    val auxiliary: AuxiliaryConfig = AuxiliaryConfig(),
    val probeRoles: ProbeRoles = ProbeRoles(),
    val machineDatums: List<MachineDatum> = emptyList(),
    val dataOutput: DataOutputConfig = DataOutputConfig(),
    val cameraId: String? = null,
    val twoWayCommunication: Boolean = true,
    val connectAtStart: Boolean = false,
    val adapterConfigurationCommands: Boolean = false,
    val useSharedToolLibrary: Boolean = false,
    /** The mute switch of this machine. It applies when [AppSettings.muteScope] is [MuteScope.EachMachine]. */
    val muteSounds: Boolean = false,
    val display: DisplaySettings = DisplaySettings(),
    /** The display pane of the graphical view. */
    val graphicalDisplay: GraphicalDisplay = GraphicalDisplay(),
    val functionButtons: FunctionButtonSettings = FunctionButtonSettings(),
    val x: LinearAxisConfig = LinearAxisConfig.forAxis(AxisId.X),
    val y: LinearAxisConfig = LinearAxisConfig.forAxis(AxisId.Y),
    val z: LinearAxisConfig = LinearAxisConfig.forAxis(AxisId.Z),
    val angular: AngularAxisConfig = AngularAxisConfig(),
    val caliper: CaliperConfig = CaliperConfig(),
    val tachometer: TachometerConfig = TachometerConfig(),
    val probe: ProbeConfig = ProbeConfig(),
    val use3dSubDatums: Boolean = true,
    val sidePanel: SidePanelMode = SidePanelMode.Off,
    val stylusDiameterUm: Double = 0.0,
    /** The unit of the stylus diameter, or null for the metric default of the profile. */
    val stylusMetric: Boolean? = null,
    val zSetterHeightUm: Double = 0.0,
    /** The unit of the setter height, or null for the metric default of the profile. */
    val zSetterMetric: Boolean? = null,
    /** The leadscrew pitch of a lathe in µm, or null when it is not set. */
    val leadscrewPitchUm: Double? = null,
    val workspaces: List<Workspace> = listOf(Workspace()),
    /** The ID of the active workspace. An empty text means the first workspace. */
    val activeWorkspaceId: String = workspaces.firstOrNull()?.id.orEmpty(),
    val tools: List<Tool> = emptyList(),
    val createdOn: Instant = Clock.System.now(),
    val updatedOn: Instant = Clock.System.now(),
) {
    /** The active workspace: [activeWorkspaceId], else the first workspace. */
    val activeWorkspace: Workspace?
        get() = workspaces.firstOrNull { workspace -> workspace.id == activeWorkspaceId } ?: workspaces.firstOrNull()

    /**
     * Gives this profile with [usbBaudRate] in the USB link: a [AdapterAddress.UsbSerial] adapter
     * gets the rate of the setting. Each other adapter does not change. Thus the link and the
     * setting cannot have two different rates.
     */
    fun withUsbBaudRateOnLink(): MachineProfile {
        val usb = adapter as? AdapterAddress.UsbSerial ?: return this
        return if (usb.baudRate == usbBaudRate) this else copy(adapter = usb.copy(baudRate = usbBaudRate))
    }

    companion object {
        /** The version of the JSON form. A change that a default value cannot cover increases it. */
        const val SCHEMA_VERSION: Int = 1

        const val DEFAULT_MILL_NAME: String = "Mill"
        const val DEFAULT_LATHE_NAME: String = "Lathe"

        /**
         * A new profile with the defaults. A lathe gets the
         * name "Lathe", the pattern angle convention [PatternAngleConvention.Legacy], and a
         * workspace with the lathe plane and the X diameter mode.
         */
        fun create(machineType: MachineType = MachineType.VerticalMill, name: String? = null): MachineProfile {
            val workspace = Workspace.forMachine(machineType)
            return MachineProfile(
                name = name ?: if (machineType.isLathe) DEFAULT_LATHE_NAME else DEFAULT_MILL_NAME,
                machineType = machineType,
                patternAngleConvention = if (machineType.isLathe) {
                    PatternAngleConvention.Legacy
                } else {
                    PatternAngleConvention.Standard
                },
                workspaces = listOf(workspace),
                activeWorkspaceId = workspace.id,
            )
        }
    }
}

/**
 * A named position in machine coordinates. X, Y, Z are µm and A is arc
 * seconds. The name has 1 to 15 characters.
 */
@Serializable
data class MachineDatum(
    val id: String = newId(),
    val name: String = "",
    val note: String = "",
    val x: Double? = null,
    val y: Double? = null,
    val z: Double? = null,
    val a: Double? = null,
    val createdOn: Instant = Clock.System.now(),
    val updatedOn: Instant = Clock.System.now(),
) {
    companion object {
        const val MAX_NAME_LENGTH: Int = 15
    }
}
