package org.superdro.core.ui.dataoutput

import androidx.compose.ui.platform.ClipEntry

/**
 * Gives the clipboard entry of the share text [text] of the PSK. Android marks the entry
 * as sensitive, so that the clipboard preview of the system does not show the key.
 */
internal expect fun keyClipEntry(text: String): ClipEntry
