package org.superdro.core.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.superdro.core.connection.DeviceId
import org.superdro.core.model.AxisId
import org.superdro.core.ui.presenter.Presenter

/** The back stacks of the tabs and the presenters of their entries. */
class NavigationHolderTest {

    private class Probe : Presenter {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    private val mill = DeviceId("mill")
    private val lathe = DeviceId("lathe")

    @Test
    fun eachTabHasItsOwnBackStackWithTheReadoutAsTheRoot() {
        val holder = NavigationHolder()
        holder.tab(mill).push(KeypadRoute(AxisId.X))
        assertEquals(listOf(ReadoutRoute, KeypadRoute(AxisId.X)), holder.tab(mill).routes)
        assertEquals(listOf<TabRoute>(ReadoutRoute), holder.tab(lathe).routes)
        assertSame(holder.tab(mill), holder.tab(mill))
        assertEquals(listOf(mill, lathe), holder.tabIds)
    }

    @Test
    fun theRootStaysAndTwoEntriesOfOneRouteHaveTwoKeys() {
        val holder = NavigationHolder()
        val stack = holder.tab(mill)
        stack.pop()
        stack.popToRoot()
        assertEquals(listOf<TabRoute>(ReadoutRoute), stack.routes)
        stack.push(MachineSettingsRoute)
        stack.push(MachineSettingsRoute)
        assertNotEquals(stack.entries[1].contentKey, stack.entries[2].contentKey)
        stack.replaceTop(AdapterDetailsRoute)
        assertEquals(listOf(ReadoutRoute, MachineSettingsRoute, AdapterDetailsRoute), stack.routes)
        stack.pop(KeypadRoute(AxisId.X))
        assertEquals(3, stack.entries.size, "pop(route) removes only an entry of that route")
        stack.pop(AdapterDetailsRoute)
        assertEquals(listOf(ReadoutRoute, MachineSettingsRoute), stack.routes)
    }

    @Test
    fun thePresentersOfAnEntryCloseWhenTheEntryLeavesTheStack() {
        val holder = NavigationHolder()
        val stack = holder.tab(mill)
        stack.push(KeypadRoute(AxisId.X))
        val key = stack.entries.last().contentKey
        val probe = holder.presenters.owner(key).getOrCreate("main") { Probe() }
        assertSame(probe, holder.presenters.owner(key).getOrCreate("main") { Probe() })
        // A configuration change disposes the composition: the decorator tells "popped", but the
        // stack still has the entry, so the presenter stays.
        holder.presenters.onPopped(key)
        assertFalse(probe.closed)
        stack.pop()
        assertTrue(probe.closed)
        assertFalse(key in holder.presenters.keys)
    }

    @Test
    fun thePresentersOfATabCloseWhenTheTabGoesAway() {
        val holder = NavigationHolder()
        val stack = holder.tab(mill)
        val root = holder.presenters.owner(stack.entries.first().contentKey).getOrCreate("main") { Probe() }
        val scoped = holder.presenters.owner(stack.scopeKey).getOrCreate("readout") { Probe() }
        holder.presenters.onPopped(stack.scopeKey)
        assertFalse(scoped.closed)
        holder.tab(lathe)
        holder.retainTabs(listOf(lathe))
        assertTrue(root.closed)
        assertTrue(scoped.closed)
        assertEquals(listOf(lathe), holder.tabIds)
        assertNotEquals(stack, holder.tab(mill), "A tab that comes back gets a new stack")
    }

    @Test
    fun backRemovesTheGlobalEntriesFirstThenTheTabEntries() {
        val holder = NavigationHolder()
        holder.tab(mill).push(AxisDetailsRoute(AxisId.Y))
        holder.push(AppSettingsRoute)
        holder.push(AboutRoute)
        val about = holder.presenters.owner(holder.globalEntries.last().contentKey).getOrCreate("main") { Probe() }
        assertTrue(holder.back(mill))
        assertTrue(about.closed)
        assertTrue(holder.back(mill))
        assertTrue(holder.globalEntries.isEmpty())
        assertTrue(holder.back(mill))
        assertEquals(listOf<TabRoute>(ReadoutRoute), holder.tab(mill).routes)
        assertFalse(holder.back(mill), "The root of the tab stays: the system handles the back action")
        holder.push(AboutRoute)
        holder.clear()
        assertTrue(holder.globalEntries.isEmpty())
    }

    @Test
    fun aClosedOwnerClosesEachNewPresenterAtOnce() {
        val holder = NavigationHolder()
        val owner = holder.presenters.owner("tab/x/1")
        owner.close()
        val late = owner.getOrCreate("main") { Probe() }
        assertTrue(late.closed)
        assertEquals(0, owner.size)
    }
}
