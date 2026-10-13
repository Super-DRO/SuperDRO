package org.superdro.core.fakeadapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.superdro.core.model.AdapterAddress

/**
 * The fake adapter writes its answers in the order of the documented sequences, also on a
 * dispatcher with many threads.
 */
class FakeAdapterOrderTest {

    @Test
    fun theAckComesBeforeTheModeAnswerOnAManyThreadDispatcher() = runBlocking {
        val runs = 300
        withContext(Dispatchers.Default) {
            repeat(runs) { run ->
                val adapter = FakeAdapter(FakeScenarios.Commands)
                val transport = adapter.transport(AdapterAddress.Fake(FakeScenarios.Commands.name))
                transport.open()
                val statuses = Channel<String>(Channel.UNLIMITED)
                val reader = launch(Dispatchers.Default) {
                    val text = StringBuilder()
                    transport.incoming.collect { bytes ->
                        text.append(bytes.decodeToString())
                        while (true) {
                            val end = text.indexOf(";")
                            if (end < 0) break
                            val message = text.substring(0, end + 1)
                            text.deleteRange(0, end + 1)
                            if (message.startsWith("$")) statuses.trySend(message)
                        }
                    }
                }
                val mode = if (run % 2 == 0) 4 else 2
                transport.write("#set_mode:x,$mode;".encodeToByteArray())
                val first = withTimeout(5_000) { statuses.receive() }
                val second = withTimeout(5_000) { statuses.receive() }
                assertEquals(listOf("\$0aa3;", "\$060;"), listOf(first, second), "run $run")
                transport.close()
                reader.join()
            }
        }
    }
}
