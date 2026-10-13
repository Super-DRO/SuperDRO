package org.superdro.core.ui.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.superdro.core.camera.CameraError
import org.superdro.core.camera.CameraErrorKind
import org.superdro.core.ui.text.UiText

/** The message and the "Open settings" rule of a camera error. */
class CameraTextsTest {

    @Test
    fun aBlockedPermissionIsTheFlagOfTheErrorNotItsDetailText() {
        val blocked = CameraError(CameraErrorKind.CameraPermissionMissing, detail = "any text", openAppSettings = true)
        val refused = CameraError(CameraErrorKind.CameraPermissionMissing, detail = "CAMERA blocked")
        assertTrue(CameraTexts.isBlocked(blocked))
        assertFalse(CameraTexts.isBlocked(refused), "The detail text has no part in the rule.")
        assertEquals("camera_permission_blocked", (CameraTexts.message(blocked) as UiText.Key).key)
        assertEquals(CameraErrorKind.CameraPermissionMissing.messageKey, (CameraTexts.message(refused) as UiText.Key).key)
    }
}
