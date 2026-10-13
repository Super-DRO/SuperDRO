package org.superdro.core.ui.settings.logs

import kotlin.test.Test
import kotlin.test.assertTrue
import org.superdro.core.connection.DeviceId
import org.superdro.core.ui.settings.renderSettings
import org.superdro.core.ui.text.UiText

/**
 * Draws the log screen to PNG files (`core/ui/build/screenshots/settings-logs-*.png`) in light and
 * dark mode, in landscape and in portrait: the lines with the filter, and the record of the byte
 * stream with one recorded link.
 */
class LogsScreenshotTest {

    private object NoEvents : LogsEvents {
        override fun onSelectTab(tab: LogsTab) = Unit
        override fun onSelectFilter(filter: LogFilter) = Unit
        override fun onExport() = Unit
        override fun onClear() = Unit
        override fun onRecordChange(deviceId: DeviceId, on: Boolean) = Unit
        override fun onExportRecord(id: Long) = Unit
        override fun onMessageShown(id: Long) = Unit
    }

    private val mill = DeviceId("mill")
    private val lathe = DeviceId("lathe")

    private val lines = listOf(
        LogLineUiState(9, "08:12:04.511", LogLevel.Info, "DeviceSession", "Mill", "link 2 open", null),
        LogLineUiState(8, "08:12:02.502", LogLevel.Info, "DeviceSession", "Mill", "two-way communication on", null),
        LogLineUiState(7, "08:12:01.250", LogLevel.Warning, "DeviceSession", "Lathe", "no data for 30000 ms", null),
        LogLineUiState(6, "08:11:58.004", LogLevel.Error, "App", null, "An app coroutine failed", "java.lang.IllegalStateException: The file IO of tabs.json failed."),
        LogLineUiState(5, "08:11:57.900", LogLevel.Debug, "DeviceSession", "Mill", "Connecting(attempt=1)", null),
        LogLineUiState(4, "08:11:57.100", LogLevel.Info, "ConnectionManager", null, "New session mill (BluetoothSpp)", null),
    )

    private val filters = listOf(
        LogFilterItem(LogFilter.All, UiText.Plain("All")),
        LogFilterItem(LogFilter.App, UiText.Plain("App")),
        LogFilterItem(LogFilter.Machine(lathe), UiText.Plain("Lathe")),
        LogFilterItem(LogFilter.Machine(mill), UiText.Plain("Mill")),
    )

    @Test
    fun theLogLines() {
        val state = LogsUiState(filters = filters, lines = lines)
        val files = renderSettings("logs-lines") { LogsScreen(state, NoEvents, onBack = {}) }
        assertTrue(files.all { file -> file.length() > 0 })
    }

    @Test
    fun theRecordOfTheByteStream() {
        val state = LogsUiState(
            tab = LogsTab.Record,
            filters = filters,
            sessions = listOf(
                RecordSessionUiState(mill, "Mill", recording = true, available = true),
                RecordSessionUiState(lathe, "Lathe", recording = false, available = false),
            ),
            records = listOf(
                RecordedLinkUiState(2, "Mill", 2, "08:12:04.511", 418, 9_312L, open = true, truncated = false),
                RecordedLinkUiState(1, "Mill", 1, "08:02:11.020", 52_190, 4_194_304L, open = false, truncated = true),
            ),
        )
        val files = renderSettings("logs-record") { LogsScreen(state, NoEvents, onBack = {}) }
        assertTrue(files.all { file -> file.length() > 0 })
    }
}
