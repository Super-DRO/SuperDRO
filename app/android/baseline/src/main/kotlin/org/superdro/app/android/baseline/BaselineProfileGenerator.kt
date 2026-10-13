package org.superdro.app.android.baseline

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Makes the Baseline Profile of `app:android`. It needs a
 * connected device: `./gradlew :app:android:generateBaselineProfile`. The profile records only the
 * start of the app and the empty frame.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startAndShowTheFrame() = rule.collect(packageName = TARGET_PACKAGE) {
        pressHome()
        startActivityAndWait()
    }

    private companion object {
        const val TARGET_PACKAGE = "org.superdro"
    }
}
