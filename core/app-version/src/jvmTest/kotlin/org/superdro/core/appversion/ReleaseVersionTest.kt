package org.superdro.core.appversion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseVersionTest {

    @Test
    fun parseTagAcceptsCleanTagsAndTrimsWhitespace() {
        assertEquals("26.42.1", ReleaseVersion.parseTag("v26.42.1"))
        assertEquals("26.42.1", ReleaseVersion.parseTag("26.42.1"))
        assertEquals("26.42.2", ReleaseVersion.parseTag("  v26.42.2 \n"))
        assertEquals("0.1.1", ReleaseVersion.parseTag("v0.1.1"))
        assertEquals("99.53.999", ReleaseVersion.parseTag("v99.53.999"))
    }

    @Test
    fun parseTagRejectsDevPrereleasesAndBadRanges() {
        assertNull(ReleaseVersion.parseTag(""))
        assertNull(ReleaseVersion.parseTag("v26.42.1-dev"))
        assertNull(ReleaseVersion.parseTag("26.42.1-dev"))
        assertNull(ReleaseVersion.parseTag("v26.42.1-rc1"))
        assertNull(ReleaseVersion.parseTag("v26.42.1+3.g1234567"))
        assertNull(ReleaseVersion.parseTag("V26.42.1"))
        assertNull(ReleaseVersion.parseTag("v26.0.1"))
        assertNull(ReleaseVersion.parseTag("v26.54.1"))
        assertNull(ReleaseVersion.parseTag("v26.42.0"))
        assertNull(ReleaseVersion.parseTag("v26.42.1000"))
        assertNull(ReleaseVersion.parseTag("v100.1.1"))
        assertNull(ReleaseVersion.parseTag("v26.42"))
        assertNull(ReleaseVersion.parseTag("v26.42.1.2"))
    }

    @Test
    fun codeOfComputesExpectedCodeAndValidatesBounds() {
        assertEquals(2642001, ReleaseVersion.codeOf("26.42.1"))
        assertEquals(2652009, ReleaseVersion.codeOf("26.52.9"))
        assertEquals(2701001, ReleaseVersion.codeOf("27.1.1"))
        assertEquals(1001, ReleaseVersion.codeOf("0.1.1"))
        assertEquals(9953999, ReleaseVersion.codeOf("99.53.999"))

        assertNull(ReleaseVersion.codeOf("v26.42.1"))
        assertNull(ReleaseVersion.codeOf("26.42.1-dev"))
        assertNull(ReleaseVersion.codeOf("-1.42.1"))
        assertNull(ReleaseVersion.codeOf("100.42.1"))
        assertNull(ReleaseVersion.codeOf("26.0.1"))
        assertNull(ReleaseVersion.codeOf("26.54.1"))
        assertNull(ReleaseVersion.codeOf("26.42.0"))
        assertNull(ReleaseVersion.codeOf("26.42.1000"))
        assertNull(ReleaseVersion.codeOf("26.42"))
        assertNull(ReleaseVersion.codeOf("26.42.1.1"))
        assertNull(ReleaseVersion.codeOf("26..1"))
        assertNull(ReleaseVersion.codeOf("26.+42.1"))
    }

    @Test
    fun isNewerComparesAcrossCountsWeeksAndYearRollover() {
        val code26421 = ReleaseVersion.codeOf("26.42.1")!!
        assertFalse(ReleaseVersion.isNewer("26.42.1", currentCode = code26421))
        assertTrue(ReleaseVersion.isNewer("26.42.2", currentCode = code26421))
        assertFalse(ReleaseVersion.isNewer("26.41.9", currentCode = code26421))

        val endOf2026 = ReleaseVersion.codeOf("26.52.9")!!
        assertTrue(ReleaseVersion.isNewer("27.1.1", currentCode = endOf2026))
        assertFalse(ReleaseVersion.isNewer("26.52.8", currentCode = endOf2026))

        assertFalse(ReleaseVersion.isNewer("26.42.2-dev", currentCode = code26421))
        assertFalse(ReleaseVersion.isNewer("v26.42.2", currentCode = code26421))
        assertFalse(ReleaseVersion.isNewer("bad", currentCode = 0))
    }
}
