package org.superdro.platform.android.camera

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File

/**
 * The process test of the application. The camera service
 * runs in `:camera`, and the attach trampolines run in `:usbattach`. The application object of
 * `app:android` calls [isAuxiliary] at the start of `onCreate` and skips its start-up in these
 * processes: no database, no settings, and no adapter link.
 */
object CameraProcess {

    /** True in the process `:camera` or `:usbattach` of the app. */
    fun isAuxiliary(context: Context): Boolean = CameraWire.isAuxiliaryProcess(processName(), context.packageName)

    /** The name of this process. */
    fun processName(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            runCatching { File("/proc/${Process.myPid()}/cmdline").readText().trimEnd('\u0000') }.getOrNull()
        }
}
