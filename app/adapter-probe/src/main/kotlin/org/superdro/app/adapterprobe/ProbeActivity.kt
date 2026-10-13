package org.superdro.app.adapterprobe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.lifecycleScope
import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.superdro.app.adapterprobe.ui.ActivityActions
import org.superdro.app.adapterprobe.ui.ProbeApp
import org.superdro.app.adapterprobe.ui.ProbeTheme
import org.superdro.core.resources.Res
import org.superdro.core.resources.probe_report_share
import org.superdro.platform.android.bluetooth.BluetoothIntents

/**
 * The one visible activity of the adapter probe app. It shows the screens Link, Tests, Test,
 * Console, and Report. The app does not use `core:ui`: it uses Material 3 with its own copy of the
 * Snapshot colors.
 *
 * While a test or the console holds a link, the screen stays on.
 */
class ProbeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val application = application as ProbeApplication
        val graph = application.graph
        val actions = ActivityActions(
            share = { report -> share(graph, report) },
            openBluetoothSettings = {
                runCatching { startActivity(BluetoothIntents.openBluetoothSettings()) }.onFailure { log.w(it) { "No Bluetooth settings" } }
            },
        )
        setContent {
            ProbeTheme {
                val active by graph.active.collectAsState(initial = false)
                KeepScreenOn(active)
                PermissionHost(graph.permissions)
                ProbeApp(application.presenters, actions)
            }
        }
    }

    private fun share(graph: ProbeGraph, report: String) {
        lifecycleScope.launch {
            val title = getString(Res.string.probe_report_share)
            val file = withContext(Dispatchers.IO) { ProbeShare.zip(this@ProbeActivity, graph, report.ifEmpty { ProbeShare.report(graph) }) }
            ProbeShare.share(this@ProbeActivity, file, title)
        }
    }

    private companion object {
        val log = Arbor.tag("ProbeActivity")
    }
}

/** Keeps the screen on while [on] is true. */
@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

/** Shows the Android permission dialog for each runtime permission request of the probe, also for TT1. */
@Composable
private fun PermissionHost(bridge: PermissionBridge) {
    val pending = remember { ArrayDeque<PermissionAsk>() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        pending.removeFirstOrNull()?.result?.complete(result)
    }
    LaunchedEffect(bridge) {
        bridge.requests.collect { ask ->
            pending.addLast(ask)
            launcher.launch(ask.permissions.toTypedArray())
        }
    }
}
