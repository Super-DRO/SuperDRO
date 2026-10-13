package org.superdro.core.ui.dataoutput

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import java.awt.datatransfer.StringSelection

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun keyClipEntry(text: String): ClipEntry = ClipEntry(StringSelection(text))
