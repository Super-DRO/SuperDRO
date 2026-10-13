package org.superdro.composite.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Unit tests of the version mapping of `Version.kt`. */
class VersionTest {

    @Test
    fun `an exact tag on a clean tree is the release version`() {
        assertEquals("26.41.1", parseGitDescribe("v26.41.1"))
    }

    @Test
    fun `commits after the tag make a development build`() {
        assertEquals("26.41.1-dev+7-gabc123", parseGitDescribe("v26.41.1-7-gabc123"))
    }

    @Test
    fun `a dirty tree on an exact tag is a development build`() {
        assertEquals("26.41.1-dev+dirty", parseGitDescribe("v26.41.1-dirty"))
    }

    @Test
    fun `no tag gives the sha`() {
        assertEquals("0.0.0-dev+gcf4c9cf", parseGitDescribe("cf4c9cf"))
        assertEquals("0.0.0-dev+gcf4c9cf-dirty", parseGitDescribe("cf4c9cf-dirty"))
    }

    @Test
    fun `no git output gives a valid development version`() {
        assertEquals("0.0.0-dev", parseGitDescribe(null))
        assertEquals("0.0.0-dev", parseGitDescribe(""))
        assertEquals("0.0.0-dev", parseGitDescribe("   "))
    }

    @Test
    fun `the version code follows the formula`() {
        assertEquals(2641001, projectVersionCode("26.41.1"))
        assertEquals(2629001, projectVersionCode("26.29.1"))
    }

    @Test
    fun `the version code ignores the development marker`() {
        assertEquals(2641001, projectVersionCode("26.41.1-dev+7-gabc123"))
    }

    @Test
    fun `the version code increases in a week and across weeks and years`() {
        assertTrue(projectVersionCode("26.41.2") > projectVersionCode("26.41.1"))
        assertTrue(projectVersionCode("26.41.1") > projectVersionCode("26.40.9"))
        assertTrue(projectVersionCode("27.1.1") > projectVersionCode("26.52.9"))
    }

    @Test
    fun `the version code is at least one`() {
        assertEquals(1, projectVersionCode("0.0.0-dev"))
        assertEquals(1, projectVersionCode("0.0.0-dev+gabc123"))
        assertEquals(1, projectVersionCode(""))
        assertEquals(1, projectVersionCode("garbage"))
    }

    @Test
    fun `the packaging version has no development marker`() {
        assertEquals("26.41.1", packagingVersion("26.41.1-dev+7-gabc123"))
        assertEquals("26.41.1", packagingVersion("26.41.1"))
    }
}
