package org.superdro.core.protocol

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtocolFrameTest {

    @Test
    fun onlyPrintableAsciiIsAFrameByte() {
        assertTrue(ProtocolFrame.isFrameByte(0x20))
        assertTrue(ProtocolFrame.isFrameByte(0x7E))
        assertFalse(ProtocolFrame.isFrameByte(0x0D))
        assertFalse(ProtocolFrame.isFrameByte(0x0A))
        assertFalse(ProtocolFrame.isFrameByte(0x00))
        assertFalse(ProtocolFrame.isFrameByte(0x80.toByte()))
    }

    @Test
    fun theStartCharacters() {
        "AaBbCcDdEeFfPpQqRrSsTtUuVvWwXxYyZz.@\$!?".forEach { character ->
            assertTrue(ProtocolFrame.isStart(character), "$character")
        }
        "GgHhIiJjKkLlMmNnOo0123456789;#-".forEach { character ->
            assertFalse(ProtocolFrame.isStart(character), "$character")
        }
    }
}
