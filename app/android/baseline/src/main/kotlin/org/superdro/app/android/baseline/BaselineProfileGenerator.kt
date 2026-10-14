package org.superdro.app.android.baseline

import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Makes the Baseline Profile and the startup profile of `app:android`. Gradle runs it on its
 * managed device: `./gradlew :app:android:generateBaselineProfile`.
 *
 * The app starts with the demo extra. The profile build types of `app:android` bind the fake
 * adapter, so the two demo machines connect and the live readout shows. The UI has English text
 * on the managed device, thus the selectors use the English content descriptions.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /**
     * The start of the app to the first live readout. These rules also go into the startup
     * profile, which sets the order of the classes in the DEX files.
     */
    @Test
    fun startToTheReadout() = rule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = true) {
        startWithDemo()
    }

    /** The tab switches, the main functions, the global toggles, and the app settings. */
    @Test
    fun readoutJourney() = rule.collect(packageName = TARGET_PACKAGE) {
        startWithDemo()

        // The tab switches between the two demo machines.
        repeat(2) {
            tap(By.descStartsWith(LATHE_TAB))
            waitFor(READOUT)
            tap(By.descStartsWith(MILL_TAB))
            waitFor(READOUT)
        }

        // The global toggles: two taps give the first state again.
        repeat(2) { tap(By.descStartsWith(UNITS_BUTTON)) }
        repeat(2) { tap(By.descStartsWith(MODE_BUTTON)) }

        // The main functions of the mill. Each one opens a route, and "back" closes it.
        MILL_FUNCTIONS.forEach { label ->
            if (tap(By.desc(label))) {
                device.waitForIdle()
                device.pressBack()
                waitFor(READOUT)
            }
        }

        // The app settings.
        if (tap(By.desc(SETTINGS_BUTTON))) {
            device.waitForIdle()
            device.pressBack()
            waitFor(READOUT)
        }
    }

    /**
     * Starts the app with the demo extra and waits for the live readout. The permission grant and
     * the battery exemption prevent the questions of the first connect.
     */
    private fun MacrobenchmarkScope.startWithDemo() {
        device.executeShellCommand("pm grant $TARGET_PACKAGE android.permission.POST_NOTIFICATIONS")
        device.executeShellCommand("dumpsys deviceidle whitelist +$TARGET_PACKAGE")
        pressHome()
        startActivityAndWait(
            Intent(Intent.ACTION_MAIN)
                .setClassName(TARGET_PACKAGE, MAIN_ACTIVITY)
                .putExtra(EXTRA_DEMO, true),
        )
        waitFor(By.descStartsWith(MILL_TAB))
        waitFor(READOUT)
        // A question that shows still, for example on a different API level, closes here.
        tap(By.text(NOT_NOW), timeoutMs = SHORT_TIMEOUT_MS)
    }

    /** Waits for [selector]. Gives false when it does not show in [timeoutMs]. */
    private fun MacrobenchmarkScope.waitFor(selector: BySelector, timeoutMs: Long = TIMEOUT_MS): Boolean =
        device.wait(Until.hasObject(selector), timeoutMs) == true

    /**
     * Taps [selector] when it shows. Gives false when it does not show. The live readout changes
     * the nodes many times each second, so a node can go stale between the search and the tap.
     * Then the function searches again.
     */
    private fun MacrobenchmarkScope.tap(selector: BySelector, timeoutMs: Long = TIMEOUT_MS): Boolean {
        repeat(TAP_ATTEMPTS) {
            val target = device.wait(Until.findObject(selector), timeoutMs) ?: return false
            try {
                target.click()
                device.waitForIdle()
                return true
            } catch (_: StaleObjectException) {
                // The node changed. Search again.
            }
        }
        return false
    }

    private companion object {
        const val TARGET_PACKAGE = "org.superdro"
        const val MAIN_ACTIVITY = "org.superdro.app.android.MainActivity"

        /** `MainActivity.EXTRA_DEMO`. */
        const val EXTRA_DEMO = "demo"

        /** The tab descriptions start with the names of the demo machines of the fake adapter. */
        const val MILL_TAB = "Demo mill,"
        const val LATHE_TAB = "Demo lathe,"

        /** The content descriptions of the function strip and the top row. */
        const val UNITS_BUTTON = "Units:"
        const val MODE_BUTTON = "Mode:"
        const val SETTINGS_BUTTON = "Settings"
        const val NOT_NOW = "Not now"
        val MILL_FUNCTIONS = listOf("Hole circle", "Hole grid", "Edge finder", "Dimension", "Graphics")

        /** The "Zero set" button shows only on the live readout. */
        val READOUT: BySelector = By.desc("Zero set")

        const val TIMEOUT_MS = 10_000L
        const val SHORT_TIMEOUT_MS = 1_000L
        const val TAP_ATTEMPTS = 5
    }
}
