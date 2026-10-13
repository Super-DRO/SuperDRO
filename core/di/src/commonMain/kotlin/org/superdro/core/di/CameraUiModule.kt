package org.superdro.core.di

import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import org.superdro.core.ui.camera.CameraSetupGate
import org.superdro.core.ui.camera.CameraUi

/**
 * The bindings of the USB camera UI: the open state of "Camera
 * setup" and the factory of the camera presenters ([CameraUi]). The platform module binds the
 * `UvcCameraBackend`; the JVM tests use the fake back end of `noOpPlatformModule`.
 */
internal val cameraUiModule = DI.Module("cameraUiModule") {
    bindSingleton { CameraSetupGate() }
    bindSingleton {
        CameraUi(
            backend = instance(),
            cameras = instance(),
            machines = instance(),
            probes = instance(),
            gate = instance(),
            requests = instance(),
            context = instance(),
        )
    }
}
