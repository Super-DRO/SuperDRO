package org.superdro.core.appversion

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppVersionTest {

    @Test
    fun theStampHasANameAndAPositiveCode() {
        assertTrue(AppVersion.NAME.isNotBlank())
        assertFalse('+' in AppVersion.NAME)
        assertTrue(AppVersion.CODE >= 1)
    }
}
