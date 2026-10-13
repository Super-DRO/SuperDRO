package org.superdro.core.connection.tcp

import kotlin.test.Test
import kotlin.test.assertEquals

class TcpLinkDefaultsTest {

    @Test
    fun theTcpLinkUsesPort8080AndA5000MsConnectLimit() {
        assertEquals(8080, TcpLinkDefaults.PORT)
        assertEquals(5000L, TcpLinkDefaults.CONNECT_TIMEOUT_MS)
        assertEquals(1024, TcpLinkDefaults.READ_BUFFER_BYTES)
    }
}
