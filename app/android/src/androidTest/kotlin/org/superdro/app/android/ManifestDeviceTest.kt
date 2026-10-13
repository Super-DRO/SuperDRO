package org.superdro.app.android

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import org.superdro.app.android.files.AndroidFilePicker
import org.superdro.platform.android.service.AndroidBatteryOptimization
import org.superdro.platform.android.service.BootReceiver
import org.superdro.platform.android.service.ConnectionService

/** The merged manifest has the service, the permissions, and the USB filters. */
@RunWith(AndroidJUnit4::class)
class ManifestDeviceTest {

    private val context = DeviceTestSupport.context
    private val packageManager = context.packageManager

    private fun packageInfo(): PackageInfo = packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)

    @Test
    fun theServiceIsAConnectedDeviceServiceInTheAppProcessThatStaysWithTheTask() {
        val info = packageManager.getServiceInfo(ComponentName(context, ConnectionService::class.java), 0)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, info.foregroundServiceType)
        assertEquals(0, info.flags and ServiceInfo.FLAG_STOP_WITH_TASK, "stopWithTask must be false.")
        assertEquals(context.packageName, info.processName, "No android:process.")
        assertFalse(info.exported)
    }

    @Test
    fun theAppDeclaresThePermissionsOfTheTableAndNoOther() {
        val info = packageInfo()
        val requested = info.requestedPermissions.orEmpty().toList()
        listOf(
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            "android.permission.WAKE_LOCK",
            "android.permission.CHANGE_NETWORK_STATE",
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_LOCAL_NETWORK",
            "android.permission.CAMERA",
        ).forEach { permission -> assertTrue(permission in requested, "$permission is not declared.") }
        // maxSdkVersion="30": Android 16 does not list them. The BlueFalcon extras are removed.
        listOf(
            "android.permission.BLUETOOTH",
            "android.permission.BLUETOOTH_ADMIN",
            "android.permission.BLUETOOTH_ADVERTISE",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
        ).forEach { permission -> assertFalse(permission in requested, "$permission must not be declared.") }
        // Android has no USB permission in the manifest.
        assertTrue(requested.none { permission -> permission.endsWith("USB_PERMISSION") })
        val scan = requested.indexOf("android.permission.BLUETOOTH_SCAN")
        val flags = info.requestedPermissionsFlags!![scan]
        assertNotEquals(0, flags and PackageInfo.REQUESTED_PERMISSION_NEVER_FOR_LOCATION, "BLUETOOTH_SCAN needs neverForLocation.")
    }

    @Test
    fun aUsbAttachOpensMainActivityWithTheDeviceFilterAndNoSeparateProcess() {
        val attach = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).setPackage(context.packageName)
        val activities = packageManager.queryIntentActivities(attach, PackageManager.GET_META_DATA).map { it.activityInfo }
        val main = activities.single { it.name == MainActivity::class.java.name }
        assertEquals(context.packageName, main.processName)
        val filter = main.metaData.getInt(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        assertEquals("superdro_usb_device_filter", context.resources.getResourceEntryName(filter))
        val vendors = mutableListOf<Int>()
        context.resources.getXml(filter).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "usb-device") {
                    vendors += parser.getAttributeValue(null, "vendor-id").toInt()
                }
            }
        }
        assertTrue(0x2341 in vendors, "The filter has the Arduino CDC-ACM vendor.")
        assertTrue(0x10C4 in vendors, "The filter has the CP210x vendor.")
        // The camera trampoline is in :usbattach with its own filter.
        val camera = activities.single { it.name == "org.superdro.platform.android.camera.CameraAttachActivity" }
        assertEquals("${context.packageName}:usbattach", camera.processName)
        assertEquals("superdro_camera_device_filter", context.resources.getResourceEntryName(camera.metaData.getInt(UsbManager.ACTION_USB_DEVICE_ATTACHED)))
    }

    @Test
    fun theBootReceiverGetsBootCompleted() {
        val boot = Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(context.packageName)
        val receivers = packageManager.queryBroadcastReceivers(boot, 0).map { it.activityInfo.name }
        assertEquals(listOf(BootReceiver::class.java.name), receivers)
    }

    @Test
    fun theBatteryRequestAndTheSettingsHaveAnActivity() {
        assertNotNull(packageManager.resolveActivity(AndroidBatteryOptimization.requestIntent(context), 0))
        assertNotNull(packageManager.resolveActivity(AndroidBatteryOptimization.settingsIntent(), 0))
    }

    @Test
    fun theFileProviderGivesTheCacheFolders() {
        val authority = AndroidFilePicker.authorityOf(context.packageName)
        val photo = FileProvider.getUriForFile(context, authority, File(context.cacheDir, "photos/photo-1.jpg"))
        assertEquals("content", photo.scheme)
        assertEquals(authority, photo.authority)
        FileProvider.getUriForFile(context, authority, File(context.cacheDir, "share/log.txt"))
    }
}
