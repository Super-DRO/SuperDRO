package org.superdro.core.ui.adapter

import org.superdro.core.connection.AdapterRestartState
import org.superdro.core.connection.DeviceId
import org.superdro.core.model.DeclaredInput
import org.superdro.core.model.ProbeContactType
import org.superdro.core.protocol.InputChannel
import org.superdro.core.protocol.InputMode
import org.superdro.core.protocol.ReferenceState
import org.superdro.core.resources.Res
import org.superdro.core.resources.adapter_cleared
import org.superdro.core.resources.adapter_failure_clear
import org.superdro.core.resources.adapter_commands_off
import org.superdro.core.resources.adapter_input_probe
import org.superdro.core.resources.adapter_input_x
import org.superdro.core.resources.adapter_probe_nc
import org.superdro.core.resources.adapter_list_connected
import org.superdro.core.resources.adapter_list_identified
import org.superdro.core.resources.adapter_list_last_seen
import org.superdro.core.resources.adapter_list_legacy
import org.superdro.core.resources.adapter_list_not_identified
import org.superdro.core.resources.adapter_list_used_by
import org.superdro.core.resources.adapter_probe_summary
import org.superdro.core.resources.adapter_resolution_inch
import org.superdro.core.resources.adapter_resolution_rev
import org.superdro.core.resources.adapter_status_calibrated
import org.superdro.core.resources.adapter_status_not_calibrated
import org.superdro.core.resources.adapter_tach_summary
import org.superdro.core.resources.adapter_type_rotary
import org.superdro.core.resources.adapter_type_scale
import org.superdro.core.store.files.MessageKey
import org.superdro.core.ui.adapter.common.ImportReportUi
import org.superdro.core.ui.adapter.details.AdapterDetailsUiState
import org.superdro.core.ui.adapter.details.ChannelRowUi
import org.superdro.core.ui.adapter.details.GearTarget
import org.superdro.core.ui.adapter.identity.NewAdapterPromptUi
import org.superdro.core.ui.adapter.identity.PromptReason
import org.superdro.core.ui.adapter.inputs.ProbeInputUiState
import org.superdro.core.ui.adapter.inputs.TachPulsesUiState
import org.superdro.core.ui.adapter.list.AdapterRowUi
import org.superdro.core.ui.adapter.list.AdaptersUiState
import org.superdro.core.ui.adapter.list.OfflineAdapterUiState
import org.superdro.core.ui.adapter.list.OfflineInputRowUi
import org.superdro.core.ui.adapter.list.OfflineRowTarget
import org.superdro.core.ui.adapter.profile.AdapterExportKind
import org.superdro.core.ui.adapter.profile.ProfileActionEvents
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText

/** The sample states of the adapter screens for the screenshots and the UI tests. */
object AdapterSamples {

    private fun row(
        channel: InputChannel,
        current: InputMode,
        modes: List<InputMode>,
        raw: String,
        rate: String?,
        selection: InputMode = current,
        reference: ReferenceState? = null,
        busy: Boolean = false,
        message: UiText? = null,
        notice: UiText? = null,
        showReference: Boolean = false,
        showClear: Boolean = false,
    ) = ChannelRowUi(
        channel = channel,
        name = AdapterTexts.channelName(channel),
        declared = false,
        modes = modes,
        current = current,
        selection = selection,
        rawValue = raw,
        refreshRate = rate,
        showSave = selection != current,
        showReference = showReference,
        showClear = showClear,
        busy = busy,
        reference = reference,
        message = message,
        notice = notice,
        gear = org.superdro.core.ui.adapter.details.AdapterDetailsRules.gear(channel, current),
    )

    private val quadrature = listOf(InputMode.Disabled, InputMode.QuadratureAB, InputMode.Tachometer, InputMode.DigitalProbe)

    /** A connected `tda-` adapter with a mode change, a reference search, a busy channel, and a failure. */
    val details = AdapterDetailsUiState(
        name = "TouchDRO Adapter",
        model = "tda-example",
        bluetoothId = "24:6f:28:a1:b2:c5",
        firmwareVersion = "1.2.3",
        firmwareBuild = "456",
        firmwareDate = "2025-03-23 00:00",
        adapterAlias = "Shop adapter",
        hasProfile = true,
        commandsAllowed = true,
        restartNeeded = true,
        showRestart = true,
        rows = listOf(
            row(InputChannel.X, InputMode.QuadratureAB, quadrature, "12345", "50.00 Hz", reference = ReferenceState.InProgress, showReference = true, showClear = true),
            row(InputChannel.Y, InputMode.QuadratureAB, quadrature, "-2310", "50.00 Hz", selection = InputMode.Tachometer),
            row(InputChannel.Z, InputMode.QuadratureAB, quadrature, "0", "49.98 Hz", busy = true, showReference = true, showClear = true),
            row(InputChannel.W, InputMode.Tachometer, quadrature, "4800", "10.00 Hz", message = UiText.of(Res.string.adapter_failure_clear)),
            row(InputChannel.U, InputMode.MitutoyoSpc, listOf(InputMode.Disabled, InputMode.QuadratureAB, InputMode.MitutoyoSpc), "777", "0 Hz", notice = UiText.of(Res.string.adapter_cleared), showClear = false),
            row(InputChannel.V, InputMode.DigitalProbe, listOf(InputMode.Disabled, InputMode.DigitalProbe), "p: 0, q: 1", null),
        ),
    )

    /** A legacy adapter with declared inputs and no commands. */
    val legacyDetails = details.copy(
        name = "???",
        model = "???",
        bluetoothId = "???",
        firmwareVersion = "???",
        firmwareBuild = "???",
        firmwareDate = "???",
        adapterAlias = "Old adapter",
        commandsAllowed = false,
        commandsNote = UiText.of(Res.string.adapter_commands_off),
        restartNeeded = false,
        showRestart = false,
        restartState = AdapterRestartState.Idle,
        rows = listOf(
            details.rows[0].copy(declared = true, modes = emptyList(), current = InputMode.Unknown, selection = InputMode.Unknown, showReference = false, showClear = false, reference = null, gear = GearTarget.Encoder('x')),
            details.rows[4].copy(name = Res.string.adapter_input_probe, declared = true, modes = emptyList(), current = InputMode.Unknown, selection = InputMode.Unknown, rawValue = "p: 1, q: 0", refreshRate = null, notice = null, gear = GearTarget.Probe),
        ),
    )

    /** The adapters list. */
    val adapters = AdaptersUiState(
        loaded = true,
        rows = listOf(
            AdapterRowUi(
                "shop",
                "Shop adapter",
                listOf(
                    UiText.of(Res.string.adapter_list_connected),
                    UiText.of(Res.string.adapter_list_used_by, "Bridgeport"),
                    UiText.of(Res.string.adapter_list_identified, ":b2:c5"),
                    UiText.of(Res.string.adapter_list_last_seen, "2026-10-11 14:30"),
                ),
                DeviceId("mill"),
            ),
            AdapterRowUi("old", "Old adapter", listOf(UiText.of(Res.string.adapter_list_used_by, "Lathe"), UiText.of(Res.string.adapter_list_legacy)), null),
            AdapterRowUi("spare", "Spare", listOf(UiText.of(Res.string.adapter_list_not_identified)), null),
        ),
    )

    /** The offline adapter details. */
    val offline = OfflineAdapterUiState(
        loaded = true,
        alias = "Shop adapter",
        model = "tda-example",
        bluetoothId = "24:6f:28:a1:b2:c5",
        rows = listOf(
            OfflineInputRowUi("x", UiText.of(Res.string.adapter_input_x), listOf(UiText.of(Res.string.adapter_type_scale), UiText.of(Res.string.adapter_resolution_inch, "5080"), UiText.of(Res.string.adapter_status_calibrated)), OfflineRowTarget.Encoder('x')),
            OfflineInputRowUi("w", AdapterTexts.encoderInputName('w'), listOf(UiText.of(Res.string.adapter_type_rotary), UiText.of(Res.string.adapter_resolution_rev, "7200"), UiText.of(Res.string.adapter_status_not_calibrated)), OfflineRowTarget.Encoder('w')),
            OfflineInputRowUi("t", AdapterTexts.encoderInputName('t'), listOf(UiText.of(Res.string.adapter_tach_summary, "4")), OfflineRowTarget.Tach),
            OfflineInputRowUi("probe", UiText.of(Res.string.adapter_input_probe), listOf(UiText.of(Res.string.adapter_probe_summary, UiText.of(Res.string.adapter_probe_nc), 20L)), OfflineRowTarget.Probe),
        ),
    )

    /** The prompt of a legacy adapter with the declared inputs. */
    val legacyPrompt = NewAdapterPromptUi(
        id = 1L,
        reason = PromptReason.Legacy,
        model = null,
        identity = null,
        currentAlias = null,
        matches = emptyList(),
        useProfileId = null,
        updateCurrent = false,
        name = "USB adapter",
        declared = setOf(DeclaredInput.X, DeclaredInput.Y, DeclaredInput.Probe),
    )

    /** The prompt of an identity that differs, with a stored profile. */
    val differsPrompt = NewAdapterPromptUi(
        id = 2L,
        reason = PromptReason.Differs,
        model = "tda-example",
        identity = "24:6f:28:a1:b2:c5",
        currentAlias = "Shop adapter",
        matches = listOf("spare" to "Spare"),
        useProfileId = null,
        updateCurrent = false,
        name = "TouchDRO Adapter",
    )

    /** The report after a profile import with one item that the import did not map. */
    val importReport = ImportReportUi(
        title = MessageKey.ReportTitle.toUiText(),
        summary = MessageKey.ReportSummary.toUiText(4, 1, 0),
        sections = listOf(MessageKey.ReportNotMapped.toUiText() to listOf(MessageKey.SettingNotMapped.toUiText("w_axis_scale"))),
    )

    val tach = TachPulsesUiState(loaded = true, hasProfile = true, text = "4")
    val probe = ProbeInputUiState(loaded = true, hasProfile = true, contactType = ProbeContactType.NormallyClosed, debounceText = "20")

    /** Profile actions that record each call, for the screens. */
    class RecordingActions : ProfileActionEvents {
        val calls = mutableListOf<String>()
        override fun copy() { calls += "copy" }
        override fun askExport() { calls += "askExport" }
        override fun export(kind: AdapterExportKind) { calls += "export $kind" }
        override fun importIntoProfile() { calls += "importIntoProfile" }
        override fun importAsNew() { calls += "importAsNew" }
        override fun confirmImport() { calls += "confirmImport" }
        override fun askForget() { calls += "askForget" }
        override fun confirmForget() { calls += "confirmForget" }
        override fun dismiss() { calls += "dismiss" }
    }
}
