package org.superdro.core.ui.settings.machine

import org.superdro.core.model.AuxiliaryType
import org.superdro.core.model.AxisButtonMode
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineType
import org.superdro.core.model.PatternAngleConvention
import org.superdro.core.model.ReadoutFont
import org.superdro.core.model.ReadoutFormat
import org.superdro.core.model.SidePanelMode
import org.superdro.core.model.TachometerMode
import org.superdro.core.model.TransportKind
import org.superdro.core.store.repository.MismatchDisposal

/**
 * The events of the machine settings screen. The composables are stateless and call these
 * functions. [MachineSettingsPresenter] implements them; the default of each function does
 * nothing, for the previews and the screenshot tests.
 */
interface MachineSettingsEvents {
    fun selectGroup(group: MachineSettingsGroup) = Unit
    fun setName(text: String) = Unit
    fun setMachineType(type: MachineType) = Unit
    fun setMetricDefault(on: Boolean) = Unit
    fun setMuteSounds(on: Boolean) = Unit
    fun setUseSharedToolLibrary(on: Boolean) = Unit
    fun setSidePanel(mode: SidePanelMode) = Unit
    fun setUse3dSubDatums(on: Boolean) = Unit
    fun setReadoutFormat(format: ReadoutFormat) = Unit
    fun setReadoutFont(font: ReadoutFont) = Unit
    fun setShowSliders(on: Boolean) = Unit
    fun setShortPressZero(on: Boolean) = Unit
    fun setActivateNewSubDatum(on: Boolean) = Unit
    fun setPatternAngleConvention(convention: PatternAngleConvention) = Unit
    fun setCameraKeepOverlays(on: Boolean) = Unit
    fun setCameraCrosshairColor(key: String) = Unit
    fun setFunctionButton(button: FunctionSwitch, on: Boolean) = Unit
    fun setAxisEnabled(axis: AxisId, on: Boolean) = Unit
    fun setLabel(axis: AxisId, text: String) = Unit
    fun setButtonMode(axis: AxisId, index: Int, mode: AxisButtonMode) = Unit
    fun setButtonShowExpanded(axis: AxisId, index: Int, on: Boolean) = Unit
    fun setButtonShowCollapsed(axis: AxisId, index: Int, on: Boolean) = Unit
    fun setPrimaryChannel(axis: AxisId, channel: Char) = Unit
    fun setInvert(axis: AxisId, on: Boolean) = Unit
    fun setSecondaryEnabled(axis: AxisId, on: Boolean) = Unit
    fun setSecondaryChannel(axis: AxisId, channel: Char) = Unit
    fun setSecondaryInvert(axis: AxisId, on: Boolean) = Unit
    fun setAuxiliaryType(type: AuxiliaryType) = Unit
    fun setAuxiliaryVectoring(on: Boolean) = Unit
    fun setAuxiliaryAngleReference(degrees: Int) = Unit
    fun setTachometerMode(mode: TachometerMode) = Unit
    fun setPresets(text: String) = Unit
    fun setPulses(text: String) = Unit
    fun setProbeEnabled(on: Boolean) = Unit
    fun setEdgeFinderEnabled(on: Boolean) = Unit
    fun setToolSetterEnabled(on: Boolean) = Unit
    fun setLinkType(kind: TransportKind) = Unit
    fun setBluetoothAddress(text: String) = Unit
    fun setWifiHost(text: String) = Unit
    fun setWifiPort(text: String) = Unit
    fun setUsbBaudRate(rate: Int) = Unit
    fun setTwoWayCommunication(on: Boolean) = Unit
    fun setAdapterConfigurationCommands(on: Boolean) = Unit
    fun setTypeChangeDisposal(disposal: MismatchDisposal) = Unit
    fun confirmTypeChange() = Unit
    fun cancelTypeChange() = Unit
}

/** The events that do nothing. */
object NoMachineSettingsEvents : MachineSettingsEvents
