package org.superdro.core.adapterprobe

import kotlin.test.Test
import kotlin.test.assertEquals
import org.superdro.core.protocol.AdapterMessage

/**
 * The parser checks of `adapter_probe.py --self-test` (part 1) on the SuperDRO parser through
 * [MessageFeed]. The probe uses no second parser.
 */
class MessageFeedTest {

    @Test
    fun rejectedTextOfABadReadout() {
        val result = MessageFeed().feed("X12 3;".encodeToByteArray(), 0.0)
        assertEquals("X12 3;", result.rejected)
        assertEquals(listOf("X12"), result.messages.map { it.text })
    }

    @Test
    fun messageTimeIsTheTimeOfTheBlockWithTheFirstCharacter() {
        val feed = MessageFeed()
        assertEquals(emptyList(), feed.feed("X1".encodeToByteArray(), 5.0).messages)
        val messages = feed.feed("2;".encodeToByteArray(), 6.0).messages
        assertEquals(1, messages.size)
        assertEquals(5.0, messages.single().t)
        assertEquals("X12;", messages.single().text)
        assertEquals(AdapterMessage.AxisCount('X', 12, 5000L), messages.single().message)
    }

    @Test
    fun controlBytesAndHighBytesAreRemoved() {
        val feed = MessageFeed()
        val result = feed.feed(byteArrayOf(0x58, 0x31, 0x3B, 0x0D, 0x0A, 0x00, 0xFF.toByte()), 0.0)
        assertEquals(4, result.dropped)
        assertEquals(4L, feed.dropped)
        assertEquals("", result.rejected)
    }

    @Test
    fun theEndingSemicolonOfABadMessageIsRejected() {
        // The tool counts the ';' that ends a message that is not valid as a rejected character.
        assertEquals("\$5;", MessageFeed().feed("\$5;".encodeToByteArray(), 0.0).rejected)
        assertEquals("P10;", MessageFeed().feed("P10;".encodeToByteArray(), 0.0).rejected)
        assertEquals("P1x;", MessageFeed().feed("P1x;".encodeToByteArray(), 0.0).rejected)
        assertEquals("", MessageFeed().feed("X12Y-5;".encodeToByteArray(), 0.0).rejected.removePrefix("X12"))
    }

    @Test
    fun theKeysAndValuesOfTheStatistics() {
        val messages = MessageFeed().feed("X-5;p1;@3E8;\$0aa3;!0A21;?0530aea4a1b2c3;?64QUJD;.3;F5;".encodeToByteArray(), 1.0).messages
        assertEquals(listOf("X", "P", "@", "$", "!", "?", "?", ".", "F"), messages.map { it.key })
        assertEquals(listOf("count", "bool", "time", "status", "error", "info", "sig", "count", "count"), messages.map { it.kind.pyName })
        assertEquals(listOf<Any>(-5L, 1L, 1000L, "\$0aa3;", "!0A21;", "?0530aea4a1b2c3;", "?64QUJD;", 3L, 5L), messages.map { it.statValue })
        assertEquals(true, messages[3].isAck())
        assertEquals(true, messages[4].errorPath())
    }
}
