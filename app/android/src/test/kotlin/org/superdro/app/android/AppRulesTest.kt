package org.superdro.app.android

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.connection.PermissionKind
import org.superdro.core.ui.navigation.AddDeviceRoute
import org.superdro.core.ui.navigation.BatteryOptimizationRoute

/** The rules of the request bus and of the launch intents. */
class AppRulesTest {

    @Test
    fun eachKindGivesItsRunTimePermissionOnlyWhereTheApiLevelHasIt() {
        val bluetooth = setOf(PermissionKind.BluetoothConnect, PermissionKind.BluetoothScan)
        assertEquals(
            listOf("android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN"),
            PermissionRules.manifestPermissions(bluetooth, sdkInt = 31),
        )
        assertEquals(emptyList(), PermissionRules.manifestPermissions(bluetooth, sdkInt = 30))
        assertEquals(listOf("android.permission.POST_NOTIFICATIONS"), PermissionRules.manifestPermissions(setOf(PermissionKind.Notifications), sdkInt = 33))
        assertEquals(emptyList(), PermissionRules.manifestPermissions(setOf(PermissionKind.Notifications), sdkInt = 32))
        assertEquals(listOf("android.permission.CAMERA"), PermissionRules.manifestPermissions(setOf(PermissionKind.Camera), sdkInt = 24))
        assertEquals(emptyList(), PermissionRules.manifestPermissions(setOf(PermissionKind.LocalNetwork), sdkInt = 36))
        assertEquals(
            listOf("android.permission.ACCESS_LOCAL_NETWORK"),
            PermissionRules.manifestPermissions(setOf(PermissionKind.LocalNetwork), sdkInt = 37),
        )
    }

    @Test
    fun allKindsTogetherGiveOneListInAStableOrder() {
        assertEquals(
            listOf(
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.BLUETOOTH_SCAN",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.ACCESS_LOCAL_NETWORK",
                "android.permission.CAMERA",
            ),
            PermissionRules.manifestPermissions(PermissionKind.entries.toSet(), sdkInt = 37),
        )
    }

    @Test
    fun theFirstConnectAsksForTheNotificationsThenForTheBatteryExemption() {
        assertEquals(
            listOf(FirstConnectPrompt.Notifications, FirstConnectPrompt.BatteryOptimization),
            FirstConnectRules.prompts(sdkInt = 36, notificationsGranted = false, notificationsAsked = false, batteryAsked = false, batteryExempt = false),
        )
        assertEquals(
            listOf(FirstConnectPrompt.BatteryOptimization),
            FirstConnectRules.prompts(sdkInt = 36, notificationsGranted = true, notificationsAsked = false, batteryAsked = false, batteryExempt = false),
        )
        assertEquals(
            listOf(FirstConnectPrompt.BatteryOptimization),
            FirstConnectRules.prompts(sdkInt = 32, notificationsGranted = false, notificationsAsked = false, batteryAsked = false, batteryExempt = false),
        )
    }

    @Test
    fun theBatteryRequestComesOneTimeAndNotWhenTheAppIsExempt() {
        assertEquals(
            emptyList(),
            FirstConnectRules.prompts(sdkInt = 36, notificationsGranted = true, notificationsAsked = false, batteryAsked = true, batteryExempt = false),
        )
        assertEquals(
            emptyList(),
            FirstConnectRules.prompts(sdkInt = 36, notificationsGranted = false, notificationsAsked = true, batteryAsked = false, batteryExempt = true),
        )
    }

    @Test
    fun theFirstConnectOpensTheOneBatteryDialogOfCoreUiOneTime() {
        assertTrue(BatteryRequestRules.shouldOpen(emptyList()))
        assertTrue(BatteryRequestRules.shouldOpen(listOf(AddDeviceRoute)))
        assertFalse(BatteryRequestRules.shouldOpen(listOf(AddDeviceRoute, BatteryOptimizationRoute)))
        // The app shell has no second dialog with the battery reason text.
        val shell = File("src/main/kotlin").walkTopDown().filter { file -> file.isFile && file.extension == "kt" }.toList()
        assertTrue(shell.isNotEmpty())
        assertEquals(emptyList(), shell.filter { file -> "service_battery_reason" in file.readText() }.map { it.name })
    }

    @Test
    fun onlyTheLauncherAndTheDemoEntryChangeTheDemoSwitch() {
        assertNull(LaunchRules.demoSwitch(hasIntent = false, isLauncher = false, demoExtra = null))
        assertEquals(false, LaunchRules.demoSwitch(hasIntent = true, isLauncher = true, demoExtra = null))
        assertEquals(true, LaunchRules.demoSwitch(hasIntent = true, isLauncher = false, demoExtra = true))
        assertEquals(true, LaunchRules.demoSwitch(hasIntent = true, isLauncher = true, demoExtra = true))
        // A notification tap or a USB attach does not change the demo.
        assertNull(LaunchRules.demoSwitch(hasIntent = true, isLauncher = false, demoExtra = null))
    }
}
