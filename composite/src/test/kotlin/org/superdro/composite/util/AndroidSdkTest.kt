package org.superdro.composite.util

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidSdkTest {

    @Test
    fun `the SDK levels have the expected values`() {
        // compileSdk 37, minSdk 24, targetSdk 36.
        assertEquals(37, AndroidSdk.COMPILE)
        assertEquals(24, AndroidSdk.MIN)
        assertEquals(36, AndroidSdk.TARGET)
    }
}
