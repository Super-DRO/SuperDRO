package org.superdro.app.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The command line arguments of the desktop app. */
class DesktopOptionsTest {

    @Test
    fun noArgumentGivesTheNormalApp() {
        assertEquals(DesktopOptions(), DesktopOptions.parse(emptyArray()))
    }

    @Test
    fun demoTurnsOnTheDemoEntry() {
        assertEquals(DesktopOptions(demo = true), DesktopOptions.parse(arrayOf("--demo")))
    }

    @Test
    fun theDataDirectoryAndTheExitTimeHaveTwoForms() {
        assertEquals(
            DesktopOptions(demo = true, dataDirectory = File("/tmp/sd"), exitAfterMs = 45_000L),
            DesktopOptions.parse(arrayOf("--data-dir=/tmp/sd", "--demo", "--exit-after-ms", "45000")),
        )
        assertEquals(DesktopOptions(dataDirectory = File("data")), DesktopOptions.parse(arrayOf("--data-dir", "data")))
    }

    @Test
    fun anArgumentThatTheAppDoesNotKnowGoesToTheLog() {
        val options = DesktopOptions.parse(arrayOf("-psn_0_12345", "--data-dir", "--exit-after-ms=soon", "--help"))
        assertEquals(listOf("-psn_0_12345", "--data-dir", "--exit-after-ms=soon"), options.unknown)
        assertEquals(true, options.help)
        assertEquals(null, options.dataDirectory)
        assertEquals(null, options.exitAfterMs)
    }
}
