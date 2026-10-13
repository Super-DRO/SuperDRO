package org.superdro.core.ui.adapter.probe

import org.superdro.core.model.AxisId
import org.superdro.core.model.ProbeType

/** The events of the probe screens that record each call as a text, for the UI tests. */
class RecordingProbeEvents : ProbeLibraryEvents, ProbeProfileEvents, TipCalibrationEvents {
    val calls = mutableListOf<String>()

    override fun onBack() {
        calls += "back"
    }
    override fun onOpen(row: ProbeLibraryRow) {
        calls += "open ${row.id}"
    }
    override fun onNew() {
        calls += "new"
    }
    override fun onAskDelete(id: String) {
        calls += "askDelete $id"
    }
    override fun onCancelDelete() {
        calls += "cancelDelete"
    }
    override fun onConfirmDelete() {
        calls += "confirmDelete"
    }
    override fun onDismissNotice() {
        calls += "dismissNotice"
    }
    override fun onExport(form: ProbeExportForm) {
        calls += "export $form"
    }
    override fun onImport() {
        calls += "import"
    }
    override fun onSelectOperation(index: Int) {
        calls += "operation $index"
    }
    override fun onConfirmImport() {
        calls += "confirmImport"
    }
    override fun onCancelImport() {
        calls += "cancelImport"
    }
    override fun onDismissReport() {
        calls += "dismissReport"
    }
    override fun onSelect(function: ProbeFunction, id: String?) {
        calls += "select $function $id"
    }
    override fun onNewForFunction(function: ProbeFunction) {
        calls += "newFor $function"
    }
    override fun onEditProfile(id: String) {
        calls += "edit $id"
    }
    override fun onType(type: ProbeType) {
        calls += "type $type"
    }
    override fun onName(text: String) {
        calls += "name $text"
    }
    override fun onMetric(metric: Boolean) {
        calls += "metric $metric"
    }
    override fun onPort(port: Char?) {
        calls += "port $port"
    }
    override fun onDiameter(text: String) {
        calls += "diameter $text"
    }
    override fun onSampleCount(text: String) {
        calls += "count $text"
    }
    override fun onMaxSpread(text: String) {
        calls += "spread $text"
    }
    override fun onXOffset(text: String) {
        calls += "x $text"
    }
    override fun onYOffset(text: String) {
        calls += "y $text"
    }
    override fun onZOffset(text: String) {
        calls += "z $text"
    }
    override fun onSync(axis: AxisId) {
        calls += "sync $axis"
    }
    override fun onSave(autoName: String) {
        calls += "save $autoName"
    }
    override fun onCalibrate(autoName: String) {
        calls += "calibrate $autoName"
    }
    override fun onShowRecord() {
        calls += "record"
    }
    override fun onDismissRecord() {
        calls += "dismissRecord"
    }
    override fun onDismissRefusal() {
        calls += "dismissRefusal"
    }
    override fun onGauge(text: String) {
        calls += "gauge $text"
    }
    override fun onGaugeMetric(metric: Boolean) {
        calls += "gaugeMetric $metric"
    }
    override fun onContinueGauge() {
        calls += "continueGauge"
    }
    override fun onContinueTouches() {
        calls += "continueTouches"
    }
    override fun onContinueRecenter() {
        calls += "continueRecenter"
    }
    override fun onRetry() {
        calls += "retry"
    }
    override fun onCancel() {
        calls += "cancel"
    }
    override fun onApply() {
        calls += "apply"
    }
    override fun onDiscard() {
        calls += "discard"
    }
    override fun onResume() {
        calls += "resume"
    }
    override fun onStartAgain() {
        calls += "startAgain"
    }
}
