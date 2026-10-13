package org.superdro.core.connection.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import org.superdro.core.connection.LinkFailure

class BleFailuresJvmTest {

    @Test
    fun aSecurityExceptionIsPermissionMissing() {
        assertEquals(LinkFailure.PermissionMissing, bleFailureOf(SecurityException("Need android.permission.BLUETOOTH_CONNECT")))
    }
}
