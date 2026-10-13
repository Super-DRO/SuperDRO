package org.superdro.core.di

import com.toxicbakery.logging.Arbor
import org.kodein.di.DI
import org.kodein.di.bindEagerSingleton
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import org.superdro.core.connection.StreamRecorder
import org.superdro.core.ui.settings.logs.LogsPresenter
import org.superdro.core.ui.settings.logs.RecordedStreams
import org.superdro.core.ui.settings.logs.SessionLogSink

/**
 * The bindings of the settings area: the logs part (see [settingsUiModule]).
 *
 * - [SessionLogSink] is an eager singleton: the graph puts it into the Arbor forest at once, so the
 *   log screen has the lines of the sessions from the app start.
 * - [RecordedStreams] wraps the [StreamRecorder] of the platform. The connection manager gets it
 *   (`connectionModule`), so each recorded link goes to the file of the platform and to the copy
 *   that the log screen exports.
 * - [LogsPresenter] is a provider: its entry owns it and closes it.
 */
internal val settingsLogsModule = DI.Module("settingsLogsModule") {
    bindEagerSingleton<SessionLogSink> { SessionLogSink().also { sink -> Arbor.sow(sink) } }
    bindSingleton<RecordedStreams> { RecordedStreams(delegate = instance<StreamRecorder>()) }
    bindProvider {
        LogsPresenter(
            sink = instance(),
            streams = instance(),
            connections = instance(),
            machines = instance(),
            filePicker = instance(),
            context = instance(),
        )
    }
}
