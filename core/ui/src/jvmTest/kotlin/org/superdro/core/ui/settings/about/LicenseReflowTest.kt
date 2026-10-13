package org.superdro.core.ui.settings.about

import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.superdro.core.resources.Res
import kotlin.test.Test
import kotlin.test.assertEquals

/** The reflow of a license text for the detail pane of the about screen. */
class LicenseReflowTest {

    @Test
    fun theLinesOfAParagraphJoinAndTheBlankLinesStay() {
        val text = "The MIT License (MIT)\r\n\r\nPermission is hereby granted,\r\n  free of charge.\n \nTHE SOFTWARE\nIS PROVIDED.\n"
        assertEquals(
            "The MIT License (MIT)\n\nPermission is hereby granted, free of charge.\n\nTHE SOFTWARE IS PROVIDED.",
            reflowLicenseText(text),
        )
    }

    @OptIn(ExperimentalResourceApi::class)
    @Test
    fun eachNoticeFileKeepsAllItsWordsInTheirOrder() = runBlocking {
        val paths = ThirdPartyNotices.ALL.flatMap { notice -> notice.files }.distinct() + ALL_NOTICES_FILE
        paths.forEach { path ->
            val text = Res.readBytes("files/$path").decodeToString()
            val words = { value: String -> value.split(Regex("\\s+")).filter { word -> word.isNotEmpty() } }
            assertEquals(words(text), words(reflowLicenseText(text)), path)
        }
    }
}
