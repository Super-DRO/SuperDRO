package org.superdro.platform.android.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.SessionState
import org.superdro.core.model.TransportKind

/** The service loads the channel name and the action text after its start. */
class NotificationTextsTest {

    private class Texts(private val fail: Boolean = false) : ServiceTexts {
        var reads = 0

        override suspend fun channelName(): String {
            reads++
            if (fail) error("No assets")
            return "Connections"
        }

        override suspend fun disconnectAll(): String {
            reads++
            return "Disconnect all"
        }

        override suspend fun title(onlineCount: Int) = ""
        override suspend fun offlineRemembered(count: Int) = ""
        override suspend fun deviceLine(name: String, state: SessionState, transport: TransportKind) = ""
        override suspend fun mcpServer(address: String?) = ""
        override suspend fun bootOpenApp() = ""
    }

    @Test
    fun theLoadGivesTheChannelNameAndTheActionText() = runTest {
        val texts = Texts()
        val loaded = NotificationTexts.load(texts, StandardTestDispatcher(testScheduler))
        assertEquals(NotificationTexts("Connections", "Disconnect all"), loaded)
        assertEquals(2, texts.reads)
    }

    @Test
    fun aFailedReadGivesNull() = runTest {
        assertNull(NotificationTexts.load(Texts(fail = true), StandardTestDispatcher(testScheduler)))
    }
}
