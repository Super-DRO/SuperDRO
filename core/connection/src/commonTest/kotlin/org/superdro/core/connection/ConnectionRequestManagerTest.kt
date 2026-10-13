package org.superdro.core.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/** The request bus of the SnapshotPB RequestManager pattern. */
class ConnectionRequestManagerTest {

    @Test
    fun aCollectorGetsTheRequestsInOrder() = runTest {
        val manager = ConnectionRequestManager()
        val received = mutableListOf<ConnectionRequest>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            manager.bus.take(2).toList(received)
        }
        manager.send(ConnectionRequest.RequestPermissions(setOf(PermissionKind.BluetoothConnect)))
        manager.send(ConnectionRequest.RequestIgnoreBatteryOptimizations)
        collector.join()
        assertEquals(
            listOf(
                ConnectionRequest.RequestPermissions(setOf(PermissionKind.BluetoothConnect)),
                ConnectionRequest.RequestIgnoreBatteryOptimizations,
            ),
            received,
        )
    }
}
