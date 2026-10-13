package org.superdro.core.store

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqliteVersionRuleTest {

    @Test
    fun theMinimumIs3_38_0() {
        assertTrue(isSupportedSqliteVersion("3.38.0"))
        assertTrue(isSupportedSqliteVersion("3.53.4"))
        assertTrue(isSupportedSqliteVersion("3.38"))
        assertTrue(isSupportedSqliteVersion("4.0.0"))
        assertFalse(isSupportedSqliteVersion("3.37.2"))
        // API 24 has SQLite 3.9.
        assertFalse(isSupportedSqliteVersion("3.9.2"))
        assertFalse(isSupportedSqliteVersion("not a version"))
    }
}
