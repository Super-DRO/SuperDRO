package org.superdro.core.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CameraIdentityTest {

    @Test
    fun theKeyHasFourLowercaseHexDigitsForEachId() {
        assertEquals("1d6b:0102", CameraIdentity(0x1D6B, 0x0102).key)
        assertEquals("0000:000a", CameraIdentity(0, 10).key)
        assertEquals("046d:0825", CameraIdentity(0x046D, 0x0825).key)
        assertEquals("ffff:0001", CameraIdentity.keyOf(0xFFFF, 1))
    }

    @Test
    fun theLabelIsTheManufacturerAndTheProductElseTheKey() {
        val camera = CameraIdentity(0x046D, 0x0825, manufacturerName = "Logitech", productName = "C270 HD WEBCAM")
        assertEquals("Logitech C270 HD WEBCAM", camera.label)
        assertFalse(camera.labelIsKey)
        assertEquals("C270", CameraIdentity(0x046D, 0x0825, manufacturerName = " ", productName = "C270").label)
        val noNames = CameraIdentity(0x046D, 0x0825, manufacturerName = "", productName = null)
        assertEquals("046d:0825", noNames.label)
        assertTrue(noNames.labelIsKey)
    }

    @Test
    fun theListAddsTheLastAttachedCameraAndSortsByDeviceName() {
        val first = CameraIdentity(1, 2, deviceName = "/dev/bus/usb/001/003")
        val second = CameraIdentity(3, 4, deviceName = "/dev/bus/usb/001/002")
        val attached = CameraIdentity(5, 6, deviceName = "/dev/bus/usb/002/001")
        assertEquals(listOf(second, first, attached), CameraList.of(listOf(first, second), lastAttached = attached))
        assertEquals(listOf(second, first), CameraList.of(listOf(first, second), lastAttached = first))
    }

    @Test
    fun theSelectionIsTheNamedCameraElseTheFirst() {
        val first = CameraIdentity(1, 2, deviceName = "a")
        val second = CameraIdentity(3, 4, deviceName = "b")
        assertEquals(second, CameraList.select(listOf(first, second), "b"))
        assertEquals(first, CameraList.select(listOf(first, second), "c"))
        assertEquals(first, CameraList.select(listOf(first, second), null))
        assertNull(CameraList.select(emptyList(), "a"))
    }

    @Test
    fun aCameraHasAnInterfaceOfTheClassFourteen() {
        assertEquals(14, UsbVideoClass.CLASS_VIDEO)
        assertEquals(1, UsbVideoClass.SUBCLASS_VIDEO_CONTROL)
        assertTrue(UsbVideoClass.isCamera(listOf(1, 14, 14)))
        assertFalse(UsbVideoClass.isCamera(listOf(2, 10)))
        assertFalse(UsbVideoClass.isCamera(emptyList()))
    }
}
