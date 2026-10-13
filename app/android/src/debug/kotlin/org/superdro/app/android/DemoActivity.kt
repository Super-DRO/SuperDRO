package org.superdro.app.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * The Demo launcher entry of a debug build. It starts [MainActivity] with the
 * demo extra and finishes. `FLAG_ACTIVITY_CLEAR_TASK` removes the old task, so the demo starts
 * clean and only one [MainActivity] collects the request bus.
 */
class DemoActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(MainActivity.EXTRA_DEMO, true)
        )
        finish()
    }
}
