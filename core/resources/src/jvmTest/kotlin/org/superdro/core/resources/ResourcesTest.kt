package org.superdro.core.resources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.getString

/** The generated `Res` class has the app strings, the readout fonts, and the license texts. */
@OptIn(ExperimentalResourceApi::class)
class ResourcesTest {

    @Test
    fun theBaseStringsAreEnglish() = runTest {
        assertEquals("SuperDRO", getString(TestEnvironment, Res.string.app_name))
        assertEquals("No machine is online", getString(TestEnvironment, Res.string.app_no_machine_online))
        assertEquals("Add machine", getString(TestEnvironment, Res.string.app_add_machine))
    }

    @Test
    fun eachReadoutFontIsAResource() = runTest {
        val fonts = listOf(
            "font/dseg7_modern_bold_italic.ttf",
            "font/jetbrains_mono_regular.ttf",
            "font/jetbrains_mono_bold.ttf",
            "font/roboto_mono_regular.ttf",
            "font/roboto_mono_bold.ttf",
            "font/droid_sans_mono_regular.ttf",
            "font/cousine_regular.ttf",
            "font/cousine_bold.ttf",
        )
        fonts.forEach { path -> assertTrue(Res.readBytes(path).size > 10_000, path) }
        assertTrue(Res.font.dseg7_modern_bold_italic.toString().isNotEmpty())
    }

    @Test
    fun eachLicenseTextIsAResource() = runTest {
        val ofl = listOf("dseg_ofl", "jetbrains_mono_ofl", "roboto_mono_ofl", "cousine_ofl")
        ofl.forEach { name ->
            val text = Res.readBytes("files/licenses/$name.txt").decodeToString()
            assertTrue("SIL Open Font License" in text || "SIL OPEN FONT LICENSE" in text, name)
        }
        assertTrue("Apache License" in Res.readBytes("files/licenses/apache_2_0.txt").decodeToString())
        assertTrue("Apache License" in Res.readBytes("files/licenses/droid_sans_mono_notice.txt").decodeToString())
        assertTrue("DSEG" in Res.readBytes("files/licenses/third_party_notices.txt").decodeToString())
    }
}
