package com.akane.voltwise.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TopLevelBackStackTest {
    private var selected: Routes = Routes.Now
    private lateinit var backStacks: Map<Routes, NavBackStack<NavKey>>
    private lateinit var subject: TopLevelBackStack

    @Before
    fun setUp() {
        selected = Routes.Now
        backStacks = TOP_LEVEL_TABS.associateWith { NavBackStack<NavKey>(it) }
        subject = TopLevelBackStack(backStacks, { selected }, { selected = it })
    }

    @Test
    fun `select on a different tab switches without clearing any stack`() {
        backStacks.getValue(Routes.History).add(Routes.SessionDetails("42"))

        subject.select(Routes.History)

        assertEquals(Routes.History, subject.selectedTab)
        assertEquals(listOf(Routes.History, Routes.SessionDetails("42")), backStacks.getValue(Routes.History).toList())
        // The tab we left is untouched too.
        assertEquals(listOf(Routes.Now), backStacks.getValue(Routes.Now).toList())
    }

    @Test
    fun `select on the current tab pops it back to its root`() {
        selected = Routes.Apps
        backStacks.getValue(Routes.Apps).add(Routes.AppDetails(1, "com.akane.voltwise"))

        subject.select(Routes.Apps)

        assertEquals(Routes.Apps, subject.selectedTab)
        assertEquals(listOf(Routes.Apps), backStacks.getValue(Routes.Apps).toList())
    }

    @Test
    fun `select on the current tab is a no-op when already at its root`() {
        selected = Routes.Settings

        subject.select(Routes.Settings)

        assertEquals(listOf(Routes.Settings), backStacks.getValue(Routes.Settings).toList())
    }

    @Test
    fun `openRoot switches to a tab at its root and leaves the other stacks alone`() {
        backStacks.getValue(Routes.History).add(Routes.SessionDetails("42"))
        backStacks.getValue(Routes.Now).add(Routes.Health)

        subject.openRoot(Routes.History)

        assertEquals(Routes.History, subject.selectedTab)
        assertEquals(listOf(Routes.History), backStacks.getValue(Routes.History).toList())
        assertEquals(listOf(Routes.Now, Routes.Health), backStacks.getValue(Routes.Now).toList())
    }

    @Test
    fun `stack is each tab's own back stack`() {
        TOP_LEVEL_TABS.forEach { assertEquals(backStacks.getValue(it), subject.stack(it)) }
    }

    @Test
    fun `navigate pushes onto the currently selected tab`() {
        selected = Routes.Apps

        subject.navigate(Routes.AppDetails(9, "com.akane.voltwise"))

        assertEquals(
            listOf(Routes.Apps, Routes.AppDetails(9, "com.akane.voltwise")),
            backStacks.getValue(Routes.Apps).toList(),
        )
        // Other stacks are untouched.
        assertEquals(listOf(Routes.Now), backStacks.getValue(Routes.Now).toList())
    }

    @Test
    fun `onBack pops a detail off the current tab`() {
        selected = Routes.History
        backStacks.getValue(Routes.History).add(Routes.SessionDetails("7"))

        val handled = subject.onBack()

        assertTrue(handled)
        assertEquals(Routes.History, subject.selectedTab)
        assertEquals(listOf(Routes.History), backStacks.getValue(Routes.History).toList())
    }

    @Test
    fun `onBack on a non-Now root switches to Now without touching that tab's stack`() {
        selected = Routes.Settings

        val handled = subject.onBack()

        assertTrue(handled)
        assertEquals(Routes.Now, subject.selectedTab)
        assertEquals(listOf(Routes.Settings), backStacks.getValue(Routes.Settings).toList())
    }

    @Test
    fun `onBack on Now's root is unhandled so the caller can finish the activity`() {
        selected = Routes.Now

        val handled = subject.onBack()

        assertFalse(handled)
        assertEquals(Routes.Now, subject.selectedTab)
        assertEquals(listOf(Routes.Now), backStacks.getValue(Routes.Now).toList())
    }

    @Test
    fun `backStack exposes the currently selected tab's stack`() {
        selected = Routes.History

        assertEquals(backStacks.getValue(Routes.History), subject.backStack)
    }

    @Test
    fun `openDestination selects the matching top-level tab`() {
        subject.openDestination(Destinations.HISTORY)

        assertEquals(Routes.History, subject.selectedTab)
    }

    @Test
    fun `openDestination for a session id switches to History and pushes SessionDetails`() {
        subject.openDestination(Destinations.session("99"))

        assertEquals(Routes.History, subject.selectedTab)
        assertEquals(listOf(Routes.History, Routes.SessionDetails("99")), backStacks.getValue(Routes.History).toList())
    }

    @Test
    fun `openDestination for health switches to Now and pushes Health`() {
        selected = Routes.Settings

        subject.openDestination(Destinations.HEALTH)

        assertEquals(Routes.Now, subject.selectedTab)
        assertEquals(listOf(Routes.Now, Routes.Health), backStacks.getValue(Routes.Now).toList())
    }

    @Test
    fun `openDestination for status switches to Settings and pushes SettingsStatus`() {
        subject.openDestination(Destinations.STATUS)

        assertEquals(Routes.Settings, subject.selectedTab)
        assertEquals(listOf(Routes.Settings, Routes.SettingsStatus), backStacks.getValue(Routes.Settings).toList())
    }

    // C05: explicit links open the requested root, not a retained detail stack.

    @Test
    fun `See all link opens Apps at its root even when AppDetails is retained`() {
        backStacks.getValue(Routes.Apps).add(Routes.AppDetails(3, "com.akane.voltwise"))

        // What Now's "See all" (NavGraph onOpenApps) calls.
        subject.openRoot(Routes.Apps)

        assertEquals(Routes.Apps, subject.selectedTab)
        assertEquals(listOf(Routes.Apps), backStacks.getValue(Routes.Apps).toList())
    }

    @Test
    fun `openDestination for apps opens Apps at its root even when AppDetails is retained`() {
        backStacks.getValue(Routes.Apps).add(Routes.AppDetails(3, "com.akane.voltwise"))

        subject.openDestination(Destinations.APPS)

        assertEquals(Routes.Apps, subject.selectedTab)
        assertEquals(listOf(Routes.Apps), backStacks.getValue(Routes.Apps).toList())
    }

    @Test
    fun `openDestination for insights opens Insights at its root even when FindingDetails is retained`() {
        backStacks.getValue(Routes.Insights).add(Routes.FindingDetails("APP_DRAIN_ANOMALY:com.example"))

        subject.openDestination(Destinations.INSIGHTS)

        assertEquals(Routes.Insights, subject.selectedTab)
        assertEquals(listOf(Routes.Insights), backStacks.getValue(Routes.Insights).toList())
    }

    @Test
    fun `Insights is the second of five tabs`() {
        assertEquals(listOf(Routes.Now, Routes.Insights, Routes.History, Routes.Apps, Routes.Settings), TOP_LEVEL_TABS)
    }

    @Test
    fun `back at the Insights root returns to Now`() {
        selected = Routes.Insights

        assertTrue(subject.onBack())

        assertEquals(Routes.Now, subject.selectedTab)
        assertEquals(listOf(Routes.Insights), backStacks.getValue(Routes.Insights).toList())
    }

    @Test
    fun `openDestination for now opens Now at its root even when Health is retained`() {
        selected = Routes.History
        backStacks.getValue(Routes.Now).add(Routes.Health)

        subject.openDestination(Destinations.NOW)

        assertEquals(Routes.Now, subject.selectedTab)
        assertEquals(listOf(Routes.Now), backStacks.getValue(Routes.Now).toList())
    }

    @Test
    fun `openDestination for now from Now itself still drops a retained Health`() {
        backStacks.getValue(Routes.Now).add(Routes.Health)

        subject.openDestination(Destinations.NOW)

        assertEquals(Routes.Now, subject.selectedTab)
        assertEquals(listOf(Routes.Now), backStacks.getValue(Routes.Now).toList())
    }

    @Test
    fun `repeated detail links leave only the root and the latest detail`() {
        // The user switches tabs between links, so each link arrives while its tab is retained but not visible.
        repeat(3) {
            subject.select(Routes.Apps)
            subject.openDestination(Destinations.HEALTH)
        }
        assertEquals(listOf(Routes.Now, Routes.Health), backStacks.getValue(Routes.Now).toList())

        listOf("1", "2", "3").forEach { id ->
            subject.select(Routes.Apps)
            subject.openDestination(Destinations.session(id))
        }
        assertEquals(Routes.History, subject.selectedTab)
        assertEquals(listOf(Routes.History, Routes.SessionDetails("3")), backStacks.getValue(Routes.History).toList())

        repeat(3) {
            subject.select(Routes.Apps)
            subject.openDestination(Destinations.STATUS)
        }
        assertEquals(listOf(Routes.Settings, Routes.SettingsStatus), backStacks.getValue(Routes.Settings).toList())
    }

    // A busy Settings › Data blocks leaving: re-tapping its tab or a link to its root must not pop it (and so clear
    // its ViewModel, cancelling the running export, import or clear).

    @Test
    fun `select on the current tab keeps a blocked entry and reports the refusal`() {
        selected = Routes.Settings
        backStacks.getValue(Routes.Settings).add(Routes.SettingsData)
        var refusals = 0
        subject.blockLeaving(Routes.SettingsData) { refusals++ }

        subject.select(Routes.Settings)

        assertEquals(listOf(Routes.Settings, Routes.SettingsData), backStacks.getValue(Routes.Settings).toList())
        assertEquals(1, refusals)
    }

    @Test
    fun `openRoot of the current tab keeps a blocked entry and reports the refusal`() {
        selected = Routes.Settings
        backStacks.getValue(Routes.Settings).add(Routes.SettingsData)
        var refusals = 0
        subject.blockLeaving(Routes.SettingsData) { refusals++ }

        val atRoot = subject.openRoot(Routes.Settings)

        assertFalse(atRoot)
        assertEquals(Routes.Settings, subject.selectedTab)
        assertEquals(listOf(Routes.Settings, Routes.SettingsData), backStacks.getValue(Routes.Settings).toList())
        assertEquals(1, refusals)
    }

    @Test
    fun `a status link does not push over a blocked entry`() {
        selected = Routes.Settings
        backStacks.getValue(Routes.Settings).add(Routes.SettingsData)
        subject.blockLeaving(Routes.SettingsData) {}

        subject.openDestination(Destinations.STATUS)

        assertEquals(listOf(Routes.Settings, Routes.SettingsData), backStacks.getValue(Routes.Settings).toList())
    }

    @Test
    fun `switching to another tab is not blocked and keeps the blocked entry`() {
        selected = Routes.Settings
        backStacks.getValue(Routes.Settings).add(Routes.SettingsData)
        var refusals = 0
        subject.blockLeaving(Routes.SettingsData) { refusals++ }

        subject.select(Routes.Now)

        assertEquals(Routes.Now, subject.selectedTab)
        assertEquals(listOf(Routes.Settings, Routes.SettingsData), backStacks.getValue(Routes.Settings).toList())
        assertEquals(0, refusals)
    }

    @Test
    fun `once unblocked, select and openRoot pop to the root again`() {
        selected = Routes.Settings
        val settings = backStacks.getValue(Routes.Settings)
        settings.add(Routes.SettingsData)
        var refusals = 0
        val unblock = subject.blockLeaving(Routes.SettingsData) { refusals++ }

        unblock()
        subject.select(Routes.Settings)

        assertEquals(listOf(Routes.Settings), settings.toList())

        settings.add(Routes.SettingsData)
        subject.blockLeaving(Routes.SettingsData) { refusals++ }()
        assertTrue(subject.openRoot(Routes.Settings))

        assertEquals(listOf(Routes.Settings), settings.toList())
        assertEquals(0, refusals)
    }

    @Test
    fun `a block that is not busy lets select and openRoot pop to the root`() {
        selected = Routes.Settings
        val settings = backStacks.getValue(Routes.Settings)
        settings.add(Routes.SettingsData)
        var refusals = 0
        subject.blockLeaving(Routes.SettingsData, isBusy = { false }) { refusals++ }

        subject.select(Routes.Settings)

        assertEquals(listOf(Routes.Settings), settings.toList())
        assertEquals(0, refusals)
    }

    // Back at Now's root finishes the activity, clearing every entry's ViewModel: a busy entry on another tab must hold
    // it the same way, by showing that tab and telling its blocker.

    @Test
    fun `back at Now root is held while another tab has a busy blocker`() {
        val settings = backStacks.getValue(Routes.Settings)
        settings.add(Routes.SettingsData)
        var refusals = 0
        subject.blockLeaving(Routes.SettingsData, isBusy = { true }) { refusals++ }
        subject.select(Routes.Now)

        assertTrue(subject.isLeavingBlocked())
        val handled = subject.onBack()

        assertTrue(handled)
        assertEquals(1, refusals)
        assertEquals(Routes.Settings, subject.selectedTab)
        assertEquals(listOf(Routes.Settings, Routes.SettingsData), settings.toList())
    }

    @Test
    fun `back at Now root still finishes when no blocker is busy`() {
        backStacks.getValue(Routes.Settings).add(Routes.SettingsData)
        var refusals = 0
        subject.blockLeaving(Routes.SettingsData, isBusy = { false }) { refusals++ }
        subject.select(Routes.Now)

        assertFalse(subject.isLeavingBlocked())
        assertFalse(subject.onBack())
        assertEquals(0, refusals)
        assertEquals(Routes.Now, subject.selectedTab)
    }

    @Test
    fun `a replaced block's unblock does not lift the newer block`() {
        selected = Routes.Settings
        val settings = backStacks.getValue(Routes.Settings)
        settings.add(Routes.SettingsData)
        val staleUnblock = subject.blockLeaving(Routes.SettingsData) {}
        subject.blockLeaving(Routes.SettingsData) {}

        staleUnblock()

        assertFalse(subject.openRoot(Routes.Settings))
        assertEquals(listOf(Routes.Settings, Routes.SettingsData), settings.toList())
    }

    // The block is held from the entry's ViewModel, not its composition: with another tab visible, Settings › Data
    // is not composed, yet a settings or status link from a notification or tile would pop it and cancel its task.

    private class HostViewModel : ViewModel()

    private fun hostViewModel(store: ViewModelStore): HostViewModel =
        ViewModelProvider.create(store, viewModelFactory { initializer { HostViewModel() } })[HostViewModel::class]

    private fun TopLevelBackStack.blockData(): (() -> Boolean, () -> Unit) -> () -> Unit =
        { isBusy, onBlocked -> blockLeaving(Routes.SettingsData, isBusy, onBlocked) }

    @Test
    fun `a busy ViewModel's block holds its off-screen entry against settings and status links`() {
        val settings = backStacks.getValue(Routes.Settings)
        settings.add(Routes.SettingsData)
        var busy = true
        val refusals = hostViewModel(ViewModelStore()).blockLeavingWhileBusy(subject.blockData()) { busy }
        selected = Routes.Now

        subject.openDestination(Destinations.SETTINGS)

        assertEquals(Routes.Settings, subject.selectedTab)
        assertEquals(listOf(Routes.Settings, Routes.SettingsData), settings.toList())
        assertTrue("the refusal waits for the screen", refusals.tryReceive().isSuccess)

        subject.select(Routes.Now)
        subject.openDestination(Destinations.STATUS)

        assertEquals(listOf(Routes.Settings, Routes.SettingsData), settings.toList())

        busy = false
        subject.select(Routes.Now)
        subject.openDestination(Destinations.STATUS)

        assertEquals(listOf(Routes.Settings, Routes.SettingsStatus), settings.toList())
    }

    @Test
    fun `clearing the ViewModel lifts its block`() {
        selected = Routes.Settings
        val settings = backStacks.getValue(Routes.Settings)
        settings.add(Routes.SettingsData)
        val store = ViewModelStore()
        val refusals = hostViewModel(store).blockLeavingWhileBusy(subject.blockData()) { true }

        store.clear()

        assertTrue(subject.openRoot(Routes.Settings))
        assertEquals(listOf(Routes.Settings), settings.toList())
        assertTrue(refusals.isClosedForReceive)
    }

    @Test
    fun `a ViewModel registers once and its block outlives a recreated back stack`() {
        selected = Routes.Settings
        val settings = backStacks.getValue(Routes.Settings)
        settings.add(Routes.SettingsData)
        val leaveBlockers = LeaveBlockers()
        val before = TopLevelBackStack(backStacks, { selected }, { selected = it }, leaveBlockers)
        val vm = hostViewModel(ViewModelStore())
        val refusals = vm.blockLeavingWhileBusy(before.blockData()) { true }

        // Activity recreation: a new back stack over the retained stacks and blockers; the screen composes again.
        val after = TopLevelBackStack(backStacks, { selected }, { selected = it }, leaveBlockers)
        var registeredAgain = false
        val noUnblock: () -> Unit = {}
        val again = vm.blockLeavingWhileBusy(
            { _, _ ->
                registeredAgain = true
                noUnblock
            },
        ) { true }

        assertSame(refusals, again)
        assertFalse(registeredAgain)
        assertFalse(after.openRoot(Routes.Settings))
        assertEquals(listOf(Routes.Settings, Routes.SettingsData), settings.toList())
    }

    @Test
    fun `navigate ignores a duplicate of the visible top`() {
        selected = Routes.Apps
        val apps = backStacks.getValue(Routes.Apps)

        subject.navigate(Routes.AppDetails(1, "com.akane.voltwise"))
        subject.navigate(Routes.AppDetails(1, "com.akane.voltwise"))

        assertEquals(listOf(Routes.Apps, Routes.AppDetails(1, "com.akane.voltwise")), apps.toList())

        subject.navigate(Routes.AppDetails(2, "other"))
        assertEquals(3, apps.size)
    }

    @Test
    fun `a second up for an already popped entry does not pop the parent`() {
        selected = Routes.History
        val history = backStacks.getValue(Routes.History)
        history.add(Routes.SessionDetails("42"))

        assertTrue(subject.onBack(Routes.SessionDetails("42")))
        assertTrue(subject.onBack(Routes.SessionDetails("42")))

        assertEquals(Routes.History, subject.selectedTab)
        assertEquals(listOf(Routes.History), history.toList())
    }

    @Test
    fun `up for an entry that is not on top pops nothing`() {
        selected = Routes.Apps
        val apps = backStacks.getValue(Routes.Apps)
        apps.add(Routes.AppDetails(1, "com.akane.voltwise"))
        apps.add(Routes.SettingsStatus)

        subject.onBack(Routes.AppDetails(1, "com.akane.voltwise"))

        assertEquals(3, apps.size)
    }

    @Test
    fun `plain tab select still restores retained details after an explicit link elsewhere`() {
        backStacks.getValue(Routes.Apps).add(Routes.AppDetails(3, "com.akane.voltwise"))
        subject.openDestination(Destinations.HISTORY)

        subject.select(Routes.Apps)

        assertEquals(Routes.Apps, subject.selectedTab)
        assertEquals(
            listOf(Routes.Apps, Routes.AppDetails(3, "com.akane.voltwise")),
            backStacks.getValue(Routes.Apps).toList(),
        )
    }
}
