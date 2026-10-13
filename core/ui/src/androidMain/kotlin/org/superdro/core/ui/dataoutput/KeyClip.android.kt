package org.superdro.core.ui.dataoutput

import android.content.ClipData
import android.os.PersistableBundle
import androidx.compose.ui.platform.ClipEntry

/** The extra `ClipDescription.EXTRA_IS_SENSITIVE` of Android 13 (API 33). Older versions ignore it. */
private const val EXTRA_IS_SENSITIVE: String = "android.content.extra.IS_SENSITIVE"

internal actual fun keyClipEntry(text: String): ClipEntry {
    val data = ClipData.newPlainText("SuperDRO", text)
    data.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
    return ClipEntry(data)
}
