package org.superdro.platform.android.usb

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.w3c.dom.Element

class UsbDeviceTableTest {

    @Test
    fun theTableHasAllIds() {
        assertEquals(34, UsbDeviceTable.ids.size)
        val byDriver = UsbDeviceTable.entries.entries.groupBy({ (_, driver) -> driver }, { (id, _) -> id })
        assertEquals(17, byDriver.getValue(UsbDriverKind.CdcAcm).size)
        assertEquals(3, byDriver.getValue(UsbDriverKind.Cp210x).size)
        assertEquals(5, byDriver.getValue(UsbDriverKind.Ftdi).size)
        assertEquals(7, byDriver.getValue(UsbDriverKind.Pl2303).size)
        assertEquals(2, byDriver.getValue(UsbDriverKind.Ch34x).size)

        assertEquals(
            listOf(0x0001, 0x0043, 0x0010, 0x0042, 0x003B, 0x0044, 0x003F, 0x8036, 0x8037),
            UsbDeviceTable.ids.filter { id -> id.vendorId == 0x2341 }.map { id -> id.productId },
        )
        assertEquals(UsbDriverKind.CdcAcm, UsbDeviceTable.driverOf(0x16C0, 0x0483))
        assertEquals(UsbDriverKind.CdcAcm, UsbDeviceTable.driverOf(0x1A86, 0x55D4))
        assertEquals(UsbDriverKind.Cp210x, UsbDeviceTable.driverOf(0x10C4, 0xEA60))
        assertEquals(UsbDriverKind.Ftdi, UsbDeviceTable.driverOf(0x0403, 0x6015))
        assertEquals(UsbDriverKind.Pl2303, UsbDeviceTable.driverOf(0x067B, 0x23F3))
        assertEquals(UsbDriverKind.Ch34x, UsbDeviceTable.driverOf(0x1A86, 0x7523))
        assertEquals(UsbDriverKind.Ch34x, UsbDeviceTable.driverOf(0x1A86, 0x5523))
        assertNull(UsbDeviceTable.driverOf(0x0451, 0xF432))
        assertFalse(UsbDeviceTable.isKnown(0x2341, 0x9999))
    }

    @Test
    fun theTableIsTheUnionOfThe332FilterAndTheSixCdcAcmEntries() {
        val filter332 = buildSet {
            listOf(0x6001, 0x6010, 0x6011, 0x6014, 0x6015).forEach { add(UsbDeviceId(0x0403, it)) }
            listOf(0xEA60, 0xEA70, 0xEA71).forEach { add(UsbDeviceId(0x10C4, it)) }
            listOf(0x2303, 0x23A3, 0x23B3, 0x23C3, 0x23D3, 0x23E3, 0x23F3).forEach { add(UsbDeviceId(0x067B, it)) }
            listOf(0x7523, 0x5523, 0x55D4).forEach { add(UsbDeviceId(0x1A86, it)) }
            listOf(0x0001, 0x0010, 0x003B, 0x003F, 0x0042, 0x0043, 0x0044, 0x8036, 0x8037).forEach { add(UsbDeviceId(0x2341, it)) }
            add(UsbDeviceId(0x16C0, 0x0483))
        }
        assertEquals(28, filter332.size)
        val cdcAdded = setOf(
            UsbDeviceId(0x03EB, 0x2044),
            UsbDeviceId(0x1EAF, 0x0004),
            UsbDeviceId(0x0D28, 0x0204),
            UsbDeviceId(0x0483, 0x5740),
            UsbDeviceId(0x2E8A, 0x0005),
            UsbDeviceId(0x2E8A, 0x000A),
        )
        assertEquals(filter332 + cdcAdded, UsbDeviceTable.ids)
        assertTrue(cdcAdded.all { id -> UsbDeviceTable.driverOf(id.vendorId, id.productId) == UsbDriverKind.CdcAcm })
    }

    @Test
    fun theDeviceFilterOfMainActivityHasTheSameIds() {
        val file = File("src/main/res/xml/superdro_usb_device_filter.xml")
        assertTrue(file.isFile, "The test runs in the module directory: ${File(".").absolutePath}")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        assertEquals("resources", document.documentElement.tagName)
        val devices = document.getElementsByTagName("usb-device")
        val ids = (0 until devices.length).map { index ->
            val element = devices.item(index) as Element
            assertEquals(2, element.attributes.length, "Only vendor-id and product-id")
            UsbDeviceId(element.getAttribute("vendor-id").toInt(), element.getAttribute("product-id").toInt())
        }
        assertEquals(34, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(UsbDeviceTable.ids, ids.toSet())
    }
}
