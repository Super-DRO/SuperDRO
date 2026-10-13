package org.superdro.core.ui.adapter.encoder.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.functions.errormap.WizardSlot
import org.superdro.core.resources.Res
import org.superdro.core.resources.encoder_loading
import org.superdro.core.resources.encoder_no_adapter
import org.superdro.core.resources.encoder_wizard_clear_pass
import org.superdro.core.resources.encoder_wizard_discard
import org.superdro.core.resources.encoder_wizard_discard_confirm
import org.superdro.core.resources.encoder_wizard_discard_text
import org.superdro.core.resources.encoder_wizard_discard_title
import org.superdro.core.resources.encoder_wizard_empty_pass
import org.superdro.core.resources.encoder_wizard_exit
import org.superdro.core.resources.encoder_wizard_help
import org.superdro.core.resources.encoder_wizard_live
import org.superdro.core.resources.encoder_wizard_live_reference
import org.superdro.core.resources.encoder_wizard_live_target
import org.superdro.core.resources.encoder_wizard_no_reference
import org.superdro.core.resources.encoder_wizard_not_connected
import org.superdro.core.resources.encoder_wizard_pass
import org.superdro.core.resources.encoder_wizard_pass_recording
import org.superdro.core.resources.encoder_wizard_pass_row
import org.superdro.core.resources.encoder_wizard_passes
import org.superdro.core.resources.encoder_wizard_recording
import org.superdro.core.resources.encoder_wizard_recover
import org.superdro.core.resources.encoder_wizard_recover_drop
import org.superdro.core.resources.encoder_wizard_recover_text
import org.superdro.core.resources.encoder_wizard_recover_title
import org.superdro.core.resources.encoder_wizard_reference
import org.superdro.core.resources.encoder_wizard_review
import org.superdro.core.resources.encoder_wizard_start_pass
import org.superdro.core.resources.encoder_wizard_start_verification
import org.superdro.core.resources.encoder_wizard_stop
import org.superdro.core.resources.encoder_wizard_title
import org.superdro.core.resources.encoder_wizard_truncated
import org.superdro.core.resources.encoder_wizard_verification
import org.superdro.core.resources.ic_close
import org.superdro.core.ui.adapter.AdapterTexts
import org.superdro.core.ui.adapter.common.SectionTitle
import org.superdro.core.ui.adapter.common.StatusKind
import org.superdro.core.ui.adapter.common.StatusLine
import org.superdro.core.ui.adapter.encoder.map.ConfirmDialog
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets

/** The test tags of the wizard screen. */
object WizardTags {
    const val START_PASS: String = "wizard-start-pass"
    const val START_VERIFICATION: String = "wizard-start-verification"
    const val STOP: String = "wizard-stop"
    const val REVIEW: String = "wizard-review"
    const val DISCARD: String = "wizard-discard"
    const val EXIT: String = "wizard-exit"

    /** The tag of the chip of the reference input [letter]. */
    fun reference(letter: Char): String = "wizard-reference-$letter"

    /** The tag of the pass row [index] (0 to 4). */
    fun pass(index: Int): String = "wizard-pass-$index"
}

/** The events of the wizard screen. */
interface ErrorMapWizardEvents {
    fun onReference(reference: Char)
    fun onStartPass()
    fun onStartVerification()
    fun onStop()
    fun onClearPass(index: Int)
    fun onClearVerification()
    fun onReview()
    fun onDiscard()
    fun onConfirmDiscard()
    fun onCancelDiscard()
    fun onExit()
    fun onRecover()
    fun onDropRecovery()
}

/** The [ErrorMapWizardEvents] of a presenter. */
class PresenterWizardEvents(private val presenter: ErrorMapWizardPresenter) : ErrorMapWizardEvents {
    override fun onReference(reference: Char) = presenter.onReference(reference)
    override fun onStartPass() = presenter.onStartPass()
    override fun onStartVerification() = presenter.onStartVerification()
    override fun onStop() = presenter.onStop()
    override fun onClearPass(index: Int) = presenter.onClearPass(index)
    override fun onClearVerification() = presenter.onClearVerification()
    override fun onReview() = presenter.onReview()
    override fun onDiscard() = presenter.onDiscard()
    override fun onConfirmDiscard() = presenter.onConfirmDiscard()
    override fun onCancelDiscard() = presenter.onCancelDiscard()
    override fun onExit() = presenter.onExit()
    override fun onRecover() = presenter.onRecover()
    override fun onDropRecovery() = presenter.onDropRecovery()
}

/**
 * The error map wizard of one target input: the reference input, the live counts, up to
 * 5 passes and 1 verification pass, "Review Map", "Discard", and "Exit".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ErrorMapWizardScreen(state: ErrorMapWizardUiState, events: ErrorMapWizardEvents, onBack: () -> Unit) {
    val inputName = AdapterTexts.encoderInputName(state.input).resolve()
    ScreenScaffold(title = stringResource(Res.string.encoder_wizard_title, inputName), onBack = onBack) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 840.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            ) {
                when {
                    !state.loaded -> StatusLine(StatusKind.Progress, stringResource(Res.string.encoder_loading))
                    !state.available -> StatusLine(StatusKind.Warning, stringResource(Res.string.encoder_no_adapter))
                    else -> WizardBody(state, events)
                }
            }
        }
    }
    if (state.recovery) {
        ConfirmDialog(
            title = stringResource(Res.string.encoder_wizard_recover_title),
            text = stringResource(Res.string.encoder_wizard_recover_text),
            confirm = stringResource(Res.string.encoder_wizard_recover),
            onConfirm = events::onRecover,
            onCancel = events::onDropRecovery,
            cancel = stringResource(Res.string.encoder_wizard_recover_drop),
        )
    }
    if (state.confirmDiscard) {
        ConfirmDialog(
            title = stringResource(Res.string.encoder_wizard_discard_title),
            text = stringResource(Res.string.encoder_wizard_discard_text),
            confirm = stringResource(Res.string.encoder_wizard_discard_confirm),
            onConfirm = events::onConfirmDiscard,
            onCancel = events::onCancelDiscard,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WizardBody(state: ErrorMapWizardUiState, events: ErrorMapWizardEvents) {
    Text(stringResource(Res.string.encoder_wizard_help), style = MaterialTheme.typography.bodyMedium)
    if (!state.connected) StatusLine(StatusKind.Warning, stringResource(Res.string.encoder_wizard_not_connected))

    SectionTitle(stringResource(Res.string.encoder_wizard_reference))
    if (state.references.isEmpty()) {
        StatusLine(StatusKind.Warning, stringResource(Res.string.encoder_wizard_no_reference))
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.references.forEach { letter ->
                FilterChip(
                    selected = state.reference == letter,
                    onClick = { events.onReference(letter) },
                    enabled = state.recording == null,
                    label = { Text(AdapterTexts.encoderInputName(letter).resolve()) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(WizardTags.reference(letter)),
                )
            }
        }
    }

    SectionTitle(stringResource(Res.string.encoder_wizard_live))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        LiveValue(stringResource(Res.string.encoder_wizard_live_target), state.liveTarget, Modifier.weight(1f))
        LiveValue(stringResource(Res.string.encoder_wizard_live_reference), state.liveReference, Modifier.weight(1f))
    }
    state.recording?.let { slot ->
        val name = when (slot) {
            is WizardSlot.Pass -> stringResource(Res.string.encoder_wizard_pass, slot.index + 1)
            WizardSlot.Verification -> stringResource(Res.string.encoder_wizard_verification)
        }
        StatusLine(StatusKind.Progress, stringResource(Res.string.encoder_wizard_recording, name, state.recordingSamples))
    }

    Spacer(Modifier.size(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val height = Modifier.heightIn(min = LocalTouchTargets.current.button)
        if (state.recording != null) {
            Button(onClick = events::onStop, modifier = height.testTag(WizardTags.STOP), colors = AccentButtons.filled()) { Text(stringResource(Res.string.encoder_wizard_stop)) }
        } else {
            Button(onClick = events::onStartPass, enabled = state.canStartPass, modifier = height.testTag(WizardTags.START_PASS), colors = AccentButtons.filled()) {
                Text(stringResource(Res.string.encoder_wizard_start_pass))
            }
            OutlinedButton(
                onClick = events::onStartVerification,
                enabled = state.canStartVerification,
                colors = AccentButtons.outlined(),
                modifier = height.testTag(WizardTags.START_VERIFICATION),
            ) { Text(stringResource(Res.string.encoder_wizard_start_verification)) }
        }
    }

    SectionTitle(stringResource(Res.string.encoder_wizard_passes))
    val recording = state.recording
    state.passes.forEachIndexed { index, row ->
        val records = recording is WizardSlot.Pass && recording.index == index
        PassRow(
            name = stringResource(Res.string.encoder_wizard_pass, index + 1),
            row = row,
            tag = WizardTags.pass(index),
            canClear = recording == null,
            recordingSamples = if (records) state.recordingSamples else null,
        ) { events.onClearPass(index) }
    }
    PassRow(
        name = stringResource(Res.string.encoder_wizard_verification),
        row = state.verification,
        tag = "wizard-verification",
        canClear = recording == null,
        recordingSamples = if (recording == WizardSlot.Verification) state.recordingSamples else null,
        onClear = events::onClearVerification,
    )

    Spacer(Modifier.size(16.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val height = Modifier.heightIn(min = LocalTouchTargets.current.button)
        Button(onClick = events::onReview, enabled = state.canReview, modifier = height.testTag(WizardTags.REVIEW), colors = AccentButtons.filled()) {
            Text(stringResource(Res.string.encoder_wizard_review))
        }
        OutlinedButton(onClick = events::onDiscard, colors = AccentButtons.outlined(), modifier = height.testTag(WizardTags.DISCARD)) {
            Text(stringResource(Res.string.encoder_wizard_discard))
        }
        OutlinedButton(onClick = events::onExit, colors = AccentButtons.outlined(), modifier = height.testTag(WizardTags.EXIT)) {
            Text(stringResource(Res.string.encoder_wizard_exit))
        }
    }
    Spacer(Modifier.size(24.dp))
}

@Composable
private fun LiveValue(label: String, value: Long?, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value?.toString() ?: "—", style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun PassRow(name: String, row: PassRowUi, tag: String, canClear: Boolean, recordingSamples: Int?, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.widthIn(min = 120.dp))
        val info = row.info
        Text(
            text = if (recordingSamples != null) {
                stringResource(Res.string.encoder_wizard_pass_recording, recordingSamples)
            } else if (info == null) {
                stringResource(Res.string.encoder_wizard_empty_pass)
            } else {
                stringResource(Res.string.encoder_wizard_pass_row, info.sampleCount, info.farthest, info.end)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (row.truncated) StatusLine(StatusKind.Warning, stringResource(Res.string.encoder_wizard_truncated), Modifier.widthIn(max = 200.dp))
        if (info != null) {
            IconButton(onClick = onClear, enabled = canClear) {
                Icon(painterResource(Res.drawable.ic_close), contentDescription = stringResource(Res.string.encoder_wizard_clear_pass, name))
            }
        }
    }
    HorizontalDivider()
}
