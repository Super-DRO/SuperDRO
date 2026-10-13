package org.superdro.app.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.TabState
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.store.json.TabStore
import org.superdro.core.store.repository.MachineRepository

/**
 * The stored state of the shell tests of `app/android/scripts/service-device-test.sh`: one
 * remembered fake machine that is not paused. The sticky restart and the start at boot must
 * connect it again. The class runs only with the argument
 * `serviceLifecycle true` of `am instrument`.
 */
@RunWith(AndroidJUnit4::class)
class ServiceLifecycleSetup {

    private fun requested(): Boolean =
        InstrumentationRegistry.getArguments().getString("serviceLifecycle") == "true"

    @Test
    fun rememberOneFakeMachine() {
        assumeTrue("Only for service-device-test.sh", requested())
        val machines: MachineRepository = DeviceTestSupport.get()
        val tabs: TabStore = DeviceTestSupport.get()
        val settings: AppSettingsStore = DeviceTestSupport.get()
        runBlocking {
            if (machines.loadProfile(PROFILE_ID) == null) {
                machines.createProfile(MachineProfile(id = PROFILE_ID, name = "Sticky mill", adapter = AdapterAddress.Fake("still")))
            }
            tabs.awaitLoaded()
            tabs.update { TabState() }
            tabs.remember(PROFILE_ID)
            tabs.awaitWrites()
            settings.awaitLoaded()
            settings.update { value -> value.copy(batteryExemptionAsked = true) }
            settings.awaitWrites()
        }
    }

    @Test
    fun forgetTheFakeMachine() {
        assumeTrue("Only for service-device-test.sh", InstrumentationRegistry.getArguments().getString("serviceLifecycleCleanup") == "true")
        val machines: MachineRepository = DeviceTestSupport.get()
        val tabs: TabStore = DeviceTestSupport.get()
        runBlocking {
            tabs.awaitLoaded()
            tabs.update { TabState() }
            tabs.awaitWrites()
            if (machines.loadProfile(PROFILE_ID) != null) machines.deleteProfile(PROFILE_ID, deleteTools = true)
        }
    }

    companion object {
        const val PROFILE_ID: String = "sticky-mill"
    }
}
