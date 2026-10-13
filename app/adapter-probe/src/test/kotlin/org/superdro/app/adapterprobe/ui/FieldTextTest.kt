package org.superdro.app.adapterprobe.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** A fast input keeps all its characters, and a real change of the presenter value still shows. */
class FieldTextTest {

    @Test
    fun lateEchoesOfAFastInputDoNotChangeTheText() {
        val field = FieldText("")
        "192.168.1.40".indices.forEach { end -> field.edit("192.168.1.40".substring(0, end + 1)) }
        // The presenter state comes back with the old values, one after the other.
        listOf("1", "19", "192", "192.", "192.1").forEach { echo -> field.external(echo) }
        assertEquals("192.168.1.40", field.text)
        "192.168.1.40".indices.drop(5).forEach { end -> field.external("192.168.1.40".substring(0, end + 1)) }
        assertEquals("192.168.1.40", field.text)
    }

    @Test
    fun aValueThatTheFieldDidNotSendReplacesTheText() {
        val field = FieldText("")
        field.external("10.0.2.2")
        assertEquals("10.0.2.2", field.text)
        field.edit("10.0.2.20")
        field.external("10.0.2.20")
        field.external("192.168.1.40")
        assertEquals("192.168.1.40", field.text)
    }

    @Test
    fun anEditBackToAnOldValueStaysAfterItsEchoes() {
        val field = FieldText("80")
        field.edit("8")
        field.edit("")
        field.edit("9")
        field.external("8")
        field.external("")
        assertEquals("9", field.text)
        field.external("9")
        assertEquals("9", field.text)
    }
}
