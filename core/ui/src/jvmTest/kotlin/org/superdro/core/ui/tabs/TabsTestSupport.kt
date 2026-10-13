package org.superdro.core.ui.tabs

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import java.io.File
import org.superdro.core.readout.ReadoutMessage
import org.superdro.core.connection.AdapterDiscovery
import org.superdro.core.connection.CapabilitySource
import org.superdro.core.connection.CapabilityState
import org.superdro.core.connection.ConnectionRequest
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DiscoveredAdapter
import org.superdro.core.connection.TransportFactory
import org.superdro.core.connection.WantedLink
import org.superdro.core.debugadapter.DebugAdapter
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.TransportKind
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.text.UiText
import org.superdro.core.util.Clock

/** The presenter context of a test: the test scheduler and its virtual time. */
fun TestScope.testPresenterContext(): PresenterContext =
    PresenterContext(StandardTestDispatcher(testScheduler), Clock { testScheduler.currentTime })

/**
 * The English strings of `core:resources`, read from the base string files. The JVM tests of
 * `core:ui` run headless, and `getString` of Compose needs the system theme of AWT.
 */
object EnglishStrings {
    private val dir = File("../resources/src/commonMain/composeResources/values")
    private val pattern = Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

    val all: Map<String, String> by lazy {
        dir.listFiles { file -> file.name.startsWith("strings") && file.name.endsWith(".xml") }.orEmpty()
            .flatMap { file -> pattern.findAll(file.readText()).map { match -> match.groupValues[1] to unescape(match.groupValues[2]) }.toList() }
            .toMap()
    }

    private fun unescape(text: String): String = text
        .replace("\\u0027", "'").replace("\\'", "'").replace("\\\"", "\"")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
}

/** Gives the English text of [this] with the base strings of `core:resources`. */
fun UiText.english(): String = when (this) {
    is UiText.Plain -> text
    is UiText.Resource -> format(EnglishStrings.all.getValue(resource.key), args)
    is UiText.Key -> format(EnglishStrings.all[key] ?: fallback, args)
}

private fun format(pattern: String, args: List<Any>): String =
    ReadoutMessage.formatText(pattern, args.map { argument -> if (argument is UiText) argument.english() else argument })

/** A [RequestSender] that keeps each request. */
class RecordingRequests : RequestSender {
    val sent = mutableListOf<ConnectionRequest>()
    override fun send(request: ConnectionRequest) {
        sent += request
    }
}

/** An [AdapterDiscovery] with a list that the test sets. */
class FakeDiscovery(override val kind: TransportKind, initial: List<DiscoveredAdapter> = emptyList()) : AdapterDiscovery {
    val list = MutableStateFlow(initial)
    override val adapters: Flow<List<DiscoveredAdapter>> = list
}

/** A [CapabilitySource] with a state that the test sets. */
class FakeCapabilities(initial: CapabilityState = CapabilityState()) : CapabilitySource {
    override val state: MutableStateFlow<CapabilityState> = MutableStateFlow(initial)
}

/** A [DebugAdapter] of a debug build with two demo machines. */
class FakeDemoAdapter : DebugAdapter {
    override val active: Boolean = false
    override fun applySwitch(on: Boolean?) = Unit
    override val transportFactory: TransportFactory = TransportFactory { _ -> error("No transport in the tests.") }
    override val demoLinks: List<WantedLink> = listOf(
        WantedLink(DeviceId("demo-mill"), "Demo mill", AdapterAddress.Fake("mill")),
        WantedLink(DeviceId("demo-lathe"), "Demo lathe", AdapterAddress.Fake("lathe")),
    )
}

/** The app settings flow of a test. */
fun settingsFlow(settings: org.superdro.core.model.AppSettings = org.superdro.core.model.AppSettings()): StateFlow<org.superdro.core.model.AppSettings> =
    MutableStateFlow(settings)
