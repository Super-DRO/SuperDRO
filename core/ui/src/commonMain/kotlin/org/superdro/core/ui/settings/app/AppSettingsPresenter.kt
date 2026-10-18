package org.superdro.core.ui.settings.app

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import org.superdro.core.connection.BatteryOptimization
import org.superdro.core.connection.ConnectionRequest
import org.superdro.core.model.AppSettings
import org.superdro.core.model.DxfImportLimits
import org.superdro.core.model.GraphicalViewPlacement
import org.superdro.core.model.MuteScope
import org.superdro.core.model.ThemeMode
import org.superdro.core.model.TransportKind
import org.superdro.core.resources.Res
import org.superdro.core.resources.settings_app_dxf_length
import org.superdro.core.resources.settings_app_dxf_reset_done
import org.superdro.core.resources.settings_app_dxf_whole_number
import org.superdro.core.resources.settings_app_link_last
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.settings.components.LengthSettingFormat
import org.superdro.core.ui.tabs.RequestSender
import org.superdro.core.ui.tabs.adddevice.LinkTypes
import org.superdro.core.ui.text.UiText

/** The groups of the app settings screen. */
enum class AppSettingsGroup { Appearance, Sounds, Connection, Machines, DxfImport, Developer }

/**
 * The limits of the DXF import as fields of the app settings. Each limit
 * is a whole number more than 0. [CurveTolerance] shows in mm with the format `####0.0####` and
 * the store keeps nm.
 */
enum class DxfLimit {
    SourceBytes,
    Entities,
    BezierSegments,
    BlockLevels,
    ParseTimeMs,
    CurveTolerance,
    PolylineWarningPoints,
    SplineControlPoints;

    /** Gives the stored value of this limit in [limits]: bytes, counts, ms, or nm. */
    fun valueIn(limits: DxfImportLimits): Long = when (this) {
        SourceBytes -> limits.maxSourceBytes
        Entities -> limits.maxEntities.toLong()
        BezierSegments -> limits.maxBezierSegments.toLong()
        BlockLevels -> limits.maxBlockLevels.toLong()
        ParseTimeMs -> limits.maxParseTimeMs
        CurveTolerance -> limits.curveToleranceNm
        PolylineWarningPoints -> limits.polylineWarningPoints.toLong()
        SplineControlPoints -> limits.maxSplineControlPoints.toLong()
    }

    /** Gives [limits] with [value] for this limit. */
    fun withValue(limits: DxfImportLimits, value: Long): DxfImportLimits = when (this) {
        SourceBytes -> limits.copy(maxSourceBytes = value)
        Entities -> limits.copy(maxEntities = value.toInt())
        BezierSegments -> limits.copy(maxBezierSegments = value.toInt())
        BlockLevels -> limits.copy(maxBlockLevels = value.toInt())
        ParseTimeMs -> limits.copy(maxParseTimeMs = value)
        CurveTolerance -> limits.copy(curveToleranceNm = value)
        PolylineWarningPoints -> limits.copy(polylineWarningPoints = value.toInt())
        SplineControlPoints -> limits.copy(maxSplineControlPoints = value.toInt())
    }

    /** True when the stored type of this limit is an `Int`. */
    val isInt: Boolean get() = this != SourceBytes && this != ParseTimeMs && this != CurveTolerance
}

/** The rules of the DXF limit fields: the text of a stored value, and the check of a typed text. */
object DxfLimitFields {

    /** The text of [limit] for the value in [limits]. The curve tolerance shows in mm. */
    fun text(limit: DxfLimit, limits: DxfImportLimits): String = when (limit) {
        DxfLimit.CurveTolerance -> LengthSettingFormat.format(limits.curveToleranceNm / NM_PER_UM, metric = true)
        else -> limit.valueIn(limits).toString()
    }

    /**
     * Gives the stored value of the text [text] for [limit], or null when the text is not valid: a
     * whole number more than 0, or for the curve tolerance a length in mm that is 1 nm or more.
     */
    fun parse(limit: DxfLimit, text: String): Long? {
        if (limit == DxfLimit.CurveTolerance) {
            val um = LengthSettingFormat.parse(text, metric = true) ?: return null
            val nm = kotlin.math.round(um * NM_PER_UM)
            return if (nm >= 1.0 && nm <= Long.MAX_VALUE.toDouble()) nm.toLong() else null
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !trimmed.all(Char::isDigit)) return null
        val value = trimmed.toLongOrNull() ?: return null
        if (value <= 0L) return null
        if (limit.isInt && value > Int.MAX_VALUE) return null
        return value
    }

    /** The message of a text that is not valid for [limit]. */
    fun error(limit: DxfLimit): UiText =
        UiText.of(if (limit == DxfLimit.CurveTolerance) Res.string.settings_app_dxf_length else Res.string.settings_app_dxf_whole_number)

    private const val NM_PER_UM: Double = 1000.0
}

/**
 * The state of the app settings screen.
 *
 * @property settings The current app settings.
 * @property batteryExempt The battery optimization state of the platform.
 * @property dxfTexts The text of each DXF limit field: the typed text, else the stored value.
 * @property dxfErrors The message of each DXF field whose typed text is not valid.
 * @property message A message of a refused change, for example the last link type.
 */
@Immutable
data class AppSettingsUiState(
    val group: AppSettingsGroup = AppSettingsGroup.Appearance,
    val settings: AppSettings = AppSettings(),
    val batteryExempt: Boolean = true,
    val dxfTexts: Map<DxfLimit, String> = DxfLimit.entries.associateWith { limit -> DxfLimitFields.text(limit, DxfImportLimits()) },
    val dxfErrors: Map<DxfLimit, UiText> = emptyMap(),
    val message: AppSettingsMessage? = null,
) {
    /** True when the switch of [kind] is on: the add-device sheet shows the link type. */
    fun linkTypeShown(kind: TransportKind): Boolean = kind !in settings.hiddenLinkTypes

    /** True when the app switch "Mute all sounds" applies. */
    val appMuteApplies: Boolean get() = settings.muteScope == MuteScope.App
}

/** One message of the screen for the snackbar. [id] keeps two equal texts apart. */
@Immutable
data class AppSettingsMessage(val id: Long, val text: UiText)

/**
 * The presenter of the app settings screen (the "App" rows). Each
 * change goes into [appSettings] at once: the theme, the touch targets, the full screen, the sounds,
 * and the links follow the store flow.
 *
 * - The link type switches: one link type must stay on.
 * - The battery optimization state comes from the platform binding [battery]. The screen
 *   reads it again when it opens and when the app comes back from the system setting.
 * - The DXF limits: a typed text that is not valid shows its message
 *   and does not go into the store.
 */
class AppSettingsPresenter(
    private val appSettings: AppSettingsStore,
    private val battery: BatteryOptimization,
    private val requests: RequestSender,
    context: PresenterContext,
) : ScopedPresenter(context) {

    private data class Local(
        val group: AppSettingsGroup = AppSettingsGroup.Appearance,
        val typed: Map<DxfLimit, String> = emptyMap(),
        val errors: Map<DxfLimit, UiText> = emptyMap(),
        val message: AppSettingsMessage? = null,
    )

    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    /** The state of the screen. */
    val uiState: StateFlow<AppSettingsUiState> = combine(appSettings.settings, battery.exempt, local) { settings, exempt, current ->
        AppSettingsUiState(
            group = current.group,
            settings = settings,
            batteryExempt = exempt,
            dxfTexts = DxfLimit.entries.associateWith { limit -> current.typed[limit] ?: DxfLimitFields.text(limit, settings.dxfImportLimits) },
            dxfErrors = current.errors,
            message = current.message,
        )
    }.stateIn(scope, SharingStarted.Eagerly, AppSettingsUiState(settings = appSettings.settings.value, batteryExempt = battery.exempt.value))

    init {
        battery.refresh()
    }

    /** Selects the group [group]. */
    fun selectGroup(group: AppSettingsGroup) = local.update { old -> old.copy(group = group) }

    /** The screen shows again, for example after the system setting: reads the battery state again. */
    fun onResume() = battery.refresh()

    fun setThemeMode(mode: ThemeMode) = change { it.copy(themeMode = mode) }
    fun setLargeButtons(on: Boolean) = change { it.copy(largeButtons = on) }
    fun setFullScreen(on: Boolean) = change { it.copy(fullScreen = on) }
    fun setShowHints(on: Boolean) = change { it.copy(showHints = on) }
    fun setShowSetupReminder(on: Boolean) = change { it.copy(showSetupReminder = on) }
    fun setCheckForUpdates(on: Boolean) = change { it.copy(checkForUpdates = on) }
    fun setGraphicalViewInPortrait(placement: GraphicalViewPlacement) = change { it.copy(graphicalViewInPortrait = placement) }
    fun setPaneSizeScope(scope: org.superdro.core.model.PaneSizeScope) = change { it.copy(paneSizeScope = scope) }
    fun setMuteAllSounds(on: Boolean) = change { it.copy(muteAllSounds = on) }
    fun setMuteScope(scope: MuteScope) = change { it.copy(muteScope = scope) }
    fun setWakeLock(on: Boolean) = change { it.copy(wakeLockWhileLinked = on) }
    fun setSendGetSigOnce(on: Boolean) = change { it.copy(sendGetSigOnce = on) }

    /**
     * Shows or hides the link type [kind] in the add-device sheet. The last link type that
     * shows cannot go off: the screen shows "At least one link type must stay on."
     */
    fun setLinkTypeShown(kind: TransportKind, shown: Boolean) {
        val hidden = appSettings.settings.value.hiddenLinkTypes
        if (!shown) {
            val stillShown = LinkTypes.SHEET_TYPES.filter { type -> type !in hidden && type != kind }
            if (stillShown.isEmpty()) {
                showMessage(UiText.of(Res.string.settings_app_link_last))
                return
            }
        }
        change { settings -> settings.copy(hiddenLinkTypes = if (shown) settings.hiddenLinkTypes - kind else settings.hiddenLinkTypes + kind) }
    }

    /** Opens the battery optimization settings of the system. */
    fun openBatteryOptimizationSettings() {
        requests.send(ConnectionRequest.OpenBatteryOptimizationSettings)
    }

    /**
     * The user typed [text] in the field of [limit]. A valid text goes into the store at once. A
     * text that is not valid shows its message and the store keeps the old value.
     */
    fun setDxfText(limit: DxfLimit, text: String) {
        val value = DxfLimitFields.parse(limit, text)
        local.update { old ->
            old.copy(
                typed = old.typed + (limit to text),
                errors = if (value == null) old.errors + (limit to DxfLimitFields.error(limit)) else old.errors - limit,
            )
        }
        if (value != null) change { settings -> settings.copy(dxfImportLimits = limit.withValue(settings.dxfImportLimits, value)) }
    }

    /** Sets each DXF limit to its default. */
    fun resetDxfLimits() {
        local.update { old -> old.copy(typed = emptyMap(), errors = emptyMap()) }
        change { settings -> settings.copy(dxfImportLimits = DxfImportLimits()) }
        showMessage(UiText.of(Res.string.settings_app_dxf_reset_done))
    }

    /** The snackbar showed the message [id]. */
    fun onMessageShown(id: Long) = local.update { old -> if (old.message?.id == id) old.copy(message = null) else old }

    private fun showMessage(text: UiText) {
        val id = ++messageCounter
        local.update { old -> old.copy(message = AppSettingsMessage(id, text)) }
    }

    private fun change(transform: (AppSettings) -> AppSettings) = appSettings.update(transform)
}
