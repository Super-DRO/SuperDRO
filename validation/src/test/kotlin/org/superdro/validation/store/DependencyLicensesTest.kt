package org.superdro.validation.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The license texts and the notices that go with the app: the fonts, the native libraries, and each
 * library with an MIT, BSD, or EDL license.
 */
class DependencyLicensesTest {

    @Test
    fun the_license_texts_of_the_fonts_and_the_native_libraries_go_with_the_app() {
        val licenses = StoreFixtures.root.resolve("core/resources/src/commonMain/composeResources/files/licenses")
        mapOf(
            "dseg_ofl.txt" to "SIL OPEN FONT LICENSE",
            "jetbrains_mono_ofl.txt" to "SIL OPEN FONT LICENSE",
            "roboto_mono_ofl.txt" to "SIL OPEN FONT LICENSE",
            "cousine_ofl.txt" to "SIL OPEN FONT LICENSE",
            "droid_sans_mono_notice.txt" to "Apache License",
            "apache_2_0.txt" to "Apache License",
            "libusb_lgpl_2_1.txt" to "GNU LESSER GENERAL PUBLIC LICENSE",
            "libuvc_bsd.txt" to "BSD",
        ).forEach { (file, text) ->
            val content = licenses.resolve(file)
            assertTrue(content.isFile, "$file is there")
            assertTrue(text.lowercase() in content.readText().lowercase(), "$file has the license text")
        }
        assertEquals(true, licenses.resolve("libusb_lgpl_2_1.txt").readText().contains("Version 2.1"))
    }

    @Test
    fun the_notices_of_the_app_have_the_notice_of_each_library_with_an_mit_bsd_or_edl_license() {
        val files = StoreFixtures.root.resolve("core/resources/src/commonMain/composeResources/files")
        val notices = files.walkTopDown().filter { it.isFile && it.extension == "txt" }.joinToString("\n") { it.readText() }
            .lowercase().replace(" ", "").replace("-", "")
        // The libraries of the table "Libraries in the apps" with a license that needs its notice in each copy.
        val missing = listOf("Kodein", "usb-serial-for-android", "BouncyCastle", "Paho").filter { name ->
            name.lowercase().replace(" ", "").replace("-", "") !in notices
        }
        assertEquals(emptyList(), missing, "the notices of the app do not name these libraries")
    }
}
