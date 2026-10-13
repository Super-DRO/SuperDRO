package org.superdro.app.android.files

import kotlin.test.Test
import kotlin.test.assertEquals

/** The file names of the file picker. */
class FileNamesTest {

    @Test
    fun aSharedFileGetsASafeName() {
        assertEquals("a_b_c.csv", FileNames.safeName("a/b:c.csv"))
        assertEquals("log _1_.txt", FileNames.safeName(" log <1>.txt "))
        assertEquals("file", FileNames.safeName("   "))
        assertEquals("Mill profile.tdset", FileNames.safeName("Mill profile.tdset"))
    }

    @Test
    fun theDisplayNameComesFromTheProviderElseFromThePath() {
        assertEquals("tools.csv", FileNames.displayName("tools.csv", "document/primary:Download/x.csv"))
        assertEquals("x.csv", FileNames.displayName(null, "primary:Download/x.csv"))
        assertEquals("x.csv", FileNames.displayName(" ", "x.csv"))
        assertEquals("file", FileNames.displayName(null, null))
        assertEquals("photo-1760000000000.jpg", FileNames.photoName(1_760_000_000_000L))
        assertEquals("org.superdro.files", AndroidFilePicker.authorityOf("org.superdro"))
    }
}
