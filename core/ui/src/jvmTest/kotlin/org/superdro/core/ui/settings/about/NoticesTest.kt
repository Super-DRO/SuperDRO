package org.superdro.core.ui.settings.about

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.superdro.core.resources.Res

/**
 * The third-party notices of the app: a notice for each third-party library that is in an
 * APK, with its license and its license text.
 */
class NoticesTest {

    private val notices = ThirdPartyNotices.ALL
    private val file = File("../resources/src/commonMain/composeResources/files/$ALL_NOTICES_FILE").readText()

    private fun notice(id: String): ThirdPartyNotice = notices.single { item -> item.id == id }

    @Test
    fun eachLibraryOfAnApkHasItsNoticeWithItsLicense() {
        val expected = mapOf(
            "kotlin-stdlib" to "Apache License 2.0",
            "kotlinx-coroutines" to "Apache License 2.0",
            "kotlinx-serialization" to "Apache License 2.0",
            "kotlinx-datetime" to "Apache License 2.0",
            "kotlinx-io" to "Apache License 2.0",
            "kodein" to "MIT License",
            "arbor" to "Apache License 2.0",
            "compose-multiplatform" to "Apache License 2.0",
            "jetpack-compose" to "Apache License 2.0",
            "material3" to "Apache License 2.0",
            "material3-adaptive" to "Apache License 2.0",
            "navigation3" to "Apache License 2.0",
            "lifecycle" to "Apache License 2.0",
            "androidx-activity" to "Apache License 2.0",
            "androidx-core" to "Apache License 2.0",
            "androidx-other" to "Apache License 2.0",
            "guava-listenablefuture" to "Apache License 2.0",
            "sqldelight" to "Apache License 2.0",
            "androidx-sqlite" to "Apache License 2.0",
            "osmerion-sqlite-android" to "Apache License 2.0",
            "sqlite" to "Public domain",
            "bluefalcon" to "Apache License 2.0",
            "ktor" to "Apache License 2.0",
            "usb-serial-for-android" to "MIT License",
            "bouncycastle" to "MIT License (Bouncy Castle Licence)",
            "paho" to "Eclipse Distribution License 1.0 (EDL 1.0, BSD-3-Clause)",
            "bignum" to "Apache License 2.0",
            "libusb" to "GNU Lesser General Public License 2.1 or later",
            "libuvc" to "BSD 3-clause license",
            "dseg7" to "SIL Open Font License 1.1",
            "jetbrains-mono" to "SIL Open Font License 1.1",
            "roboto-mono" to "SIL Open Font License 1.1",
            "cousine" to "SIL Open Font License 1.1",
            "droid-sans-mono" to "Apache License 2.0",
            "material-icons" to "Apache License 2.0",
        )
        assertEquals(expected.keys, notices.map { it.id }.toSet())
        expected.forEach { (id, license) -> assertEquals(license, notice(id).license, id) }
        notices.forEach { item ->
            assertTrue(item.copyright.isNotBlank(), "${item.id} has a copyright line")
            assertTrue(item.version.isNotBlank(), "${item.id} has a version")
            assertTrue(item.files.isNotEmpty(), "${item.id} has a license text")
        }
    }

    @Test
    fun theMitBsdAndEdlLibrariesHaveTheirOwnLicenseTextWithTheCopyrightLine() = runBlocking {
        mapOf(
            "kodein" to "Copyright (c) 2020 KODEIN SAS",
            "usb-serial-for-android" to "Copyright (c) 2013 Mike Wakerly",
            "bouncycastle" to "The Legion of the Bouncy Castle Inc.",
            "paho" to "Eclipse Distribution License - v 1.0",
            "libuvc" to "Ken Tossell",
            "sqlite" to "The author disclaims copyright to this source code.",
        ).forEach { (id, line) ->
            val texts = notice(id).files.map { path -> Res.readBytes("files/$path").decodeToString() }
            assertTrue(texts.any { text -> line in text }, "$id: the license text has \"$line\"")
        }
        assertTrue("Permission is hereby granted" in Res.readBytes("files/licenses/bouncy_castle_licence.txt").decodeToString())
        assertTrue("Redistribution and use in source and binary forms" in Res.readBytes("files/licenses/eclipse_distribution_license_1_0.txt").decodeToString())
    }

    @Test
    fun eachLicenseFileOfANoticeIsInTheResources() = runBlocking {
        notices.flatMap { it.files }.distinct().forEach { path ->
            assertTrue(Res.readBytes("files/$path").size > 100, path)
        }
    }

    @Test
    fun theTextFileHasEachNoticeWithItsVersionLicenseCopyrightAndFiles() {
        notices.forEach { item ->
            assertTrue(item.name in file, "${item.id}: the name")
            val block = file.substringAfter(item.name + "\n").substringBefore("\n\n")
            assertTrue("Version: ${item.version}" in block, "${item.id}: the version")
            assertTrue("License: ${item.license}" in block, "${item.id}: the license")
            assertTrue(item.copyright in block, "${item.id}: the copyright line")
            assertTrue("License text: ${item.files.joinToString(", ")}" in block, "${item.id}: the files")
        }
        // The libusb notice has the source offer of the LGPL 2.1.
        assertTrue("notices_camera.txt" in file)
    }
}
