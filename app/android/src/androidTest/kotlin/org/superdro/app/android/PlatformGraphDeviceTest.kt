package org.superdro.app.android

import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.superdro.app.android.files.AndroidFilePicker
import org.superdro.app.android.sound.AndroidUiSounds
import org.superdro.core.camera.UvcCameraBackend
import org.superdro.core.connection.AdapterDiscovery
import org.superdro.core.connection.BatteryOptimization
import org.superdro.core.connection.CapabilitySource
import org.superdro.core.connection.ConnectionKeepAlive
import org.superdro.core.connection.ConnectionManager
import org.superdro.core.connection.FileStreamRecorder
import org.superdro.core.connection.KeepAliveController
import org.superdro.core.connection.NetworkInterfaces
import org.superdro.core.connection.StreamRecorder
import org.superdro.core.connection.TransportFactory
import org.superdro.core.model.TransportKind
import org.superdro.core.ui.sound.UiSounds
import org.superdro.core.util.FilePicker
import org.superdro.core.util.SecretStore
import org.superdro.platform.android.camera.AndroidUvcCameraBackend
import org.superdro.platform.android.di.AndroidCapabilitySource
import org.superdro.platform.android.network.AndroidNetworkInterfaces
import org.superdro.platform.android.service.AndroidBatteryOptimization
import org.superdro.platform.android.service.AndroidConnectionKeepAlive
import org.superdro.platform.android.service.ConnectionServiceHost
import org.superdro.platform.android.service.LinkIoDispatcher
import org.superdro.platform.android.store.AndroidKeystoreSecretStore

/**
 * The graph of the app gives each platform binding that the common graph takes, and the link
 * threads have a high priority.
 */
@RunWith(AndroidJUnit4::class)
class PlatformGraphDeviceTest {

    @Test
    fun eachPlatformBindingResolves() {
        val factories: Map<TransportKind, TransportFactory> = DeviceTestSupport.get()
        // The debug build adds the fake link type.
        assertEquals(TransportKind.entries.toSet(), factories.keys)
        val discoveries: List<AdapterDiscovery> = DeviceTestSupport.get()
        assertEquals(listOf(TransportKind.BluetoothSpp, TransportKind.UsbSerial, TransportKind.Ble, TransportKind.Fake), discoveries.map { it.kind })
        assertIs<AndroidCapabilitySource>(DeviceTestSupport.get<CapabilitySource>())
        assertIs<AndroidConnectionKeepAlive>(DeviceTestSupport.get<ConnectionKeepAlive>())
        assertIs<LinkIoDispatcher>(DeviceTestSupport.get<CoroutineDispatcher>(LinkIoDispatcher.DI_TAG))
        assertIs<AndroidUiSounds>(DeviceTestSupport.get<UiSounds>())
        assertIs<AndroidFilePicker>(DeviceTestSupport.get<FilePicker>())
        assertIs<AndroidKeystoreSecretStore>(DeviceTestSupport.get<SecretStore>())
        assertIs<AndroidNetworkInterfaces>(DeviceTestSupport.get<NetworkInterfaces>())
        assertIs<FileStreamRecorder>(DeviceTestSupport.get<StreamRecorder>())
        assertIs<AndroidUvcCameraBackend>(DeviceTestSupport.get<UvcCameraBackend>())
        assertIs<AndroidBatteryOptimization>(DeviceTestSupport.get<BatteryOptimization>())
        assertIs<AppServiceHost>(DeviceTestSupport.get<ConnectionServiceHost>())
        assertSame(DeviceTestSupport.get<ConnectionManager>(), DeviceTestSupport.get<ConnectionManager>())
        assertSame(DeviceTestSupport.get<KeepAliveController>(), DeviceTestSupport.get<KeepAliveController>())
    }

    @Test
    fun theLinkThreadsHaveAHighPriority() = runBlocking {
        val linkIo: CoroutineDispatcher = DeviceTestSupport.get(LinkIoDispatcher.DI_TAG)
        val priority = withContext(linkIo) { Process.getThreadPriority(Process.myTid()) }
        Log.i("PlatformGraphDeviceTest", "The priority of a link thread is $priority")
        assertTrue(priority <= LinkIoDispatcher.MINIMUM_PRIORITY, "The priority $priority is not -4 or higher.")
        assertEquals(LinkIoDispatcher.TARGET_PRIORITY, priority)
    }
}
