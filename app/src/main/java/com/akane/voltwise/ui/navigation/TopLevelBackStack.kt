package com.akane.voltwise.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * One [NavBackStack] per top-level tab (see [TOP_LEVEL_TABS]), plus which tab is currently
 * visible.
 *
 * Rules:
 * - [select] on the already-selected tab pops that tab's stack back to its root.
 * - [select] on a different tab switches the visible stack; no stack's contents change.
 * - [openRoot] shows a tab at its root, popping that tab's stack (only that one) whether or not it is visible.
 * - Neither pops a stack holding a busy [blockLeaving] entry: the stack stays as it is and the blocker is told.
 * - [onBack] pops the visible tab's stack. Popping the last entry of a non-[Routes.Now] tab
 *   switches to [Routes.Now] instead of leaving that tab empty. Popping [Routes.Now]'s root
 *   returns `false` so the caller (the system back handler) can finish the activity, unless another tab holds a busy
 *   [blockLeaving] entry: finishing would clear that entry too, so its tab is shown instead.
 *
 * Holds no Compose state of its own — [getSelectedTab]/[setSelectedTab] are supplied by the
 * caller — so it can be constructed and exercised with plain JUnit (see `TopLevelBackStackTest`).
 * [rememberTopLevelBackStack] wires it to `rememberNavBackStack`/`rememberSaveable` for Compose, and keeps one
 * [LeaveBlockers] across activity recreation.
 */
class TopLevelBackStack(
    private val backStacks: Map<Routes, NavBackStack<NavKey>>,
    private val getSelectedTab: () -> Routes,
    private val setSelectedTab: (Routes) -> Unit,
    private val leaveBlockers: LeaveBlockers = LeaveBlockers(),
) {
    val selectedTab: Routes get() = getSelectedTab()

    /** The back stack `NavDisplay` should render: the currently visible tab's. */
    val backStack: NavBackStack<NavKey> get() = backStacks.getValue(selectedTab)

    /** [tab]'s own stack: every tab's entries stay decorated (state, ViewModels) while another tab is visible. */
    fun stack(tab: Routes): NavBackStack<NavKey> = backStacks.getValue(tab)

    /**
     * Until the returned function is called, popping a tab to its root ([select] on the visible tab, [openRoot])
     * while [isBusy] is `true` leaves [entry]'s stack as it is and calls [onBlocked] instead. For a screen whose pop
     * would cancel work in its ViewModel (see [blockLeavingWhileBusy]); Back is guarded on the screen itself.
     * Registering [entry] again replaces its blocker, and the replaced registration's function then does nothing.
     */
    fun blockLeaving(entry: NavKey, isBusy: () -> Boolean = { true }, onBlocked: () -> Unit): () -> Unit {
        val blocker = LeaveBlockers.Blocker(isBusy, onBlocked)
        val byEntry = leaveBlockers.byEntry
        byEntry[entry] = blocker
        return { byEntry.remove(entry, blocker) }
    }

    fun select(tab: Routes) {
        if (tab == selectedTab) popToRoot(backStacks.getValue(tab)) else setSelectedTab(tab)
    }

    /**
     * Shows [tab] at its root, whichever tab is visible: a link to what the tab itself shows first.
     * @return `false` if a [blockLeaving] entry kept the stack above its root.
     */
    fun openRoot(tab: Routes): Boolean {
        val atRoot = popToRoot(backStacks.getValue(tab))
        if (tab != selectedTab) setSelectedTab(tab)
        return atRoot
    }

    /** A busy [blockLeaving] entry above [stack]'s root, which popping that stack to its root would clear. */
    private fun busyBlocker(stack: NavBackStack<NavKey>): LeaveBlockers.Blocker? =
        stack.drop(1).firstNotNullOfOrNull { entry -> leaveBlockers.byEntry[entry]?.takeIf { it.isBusy() } }

    /**
     * `true` while any tab holds a busy [blockLeaving] entry, so Back at [Routes.Now]'s root must not finish the
     * activity (see [onBack]). Not observable: a caller re-reads it whenever it recomposes.
     */
    fun isLeavingBlocked(): Boolean = backStacks.values.any { busyBlocker(it) != null }

    private fun popToRoot(stack: NavBackStack<NavKey>): Boolean {
        val blocker = busyBlocker(stack)
        if (blocker != null) {
            blocker.onBlocked()
            return false
        }
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        return true
    }

    /** Pushes [route] onto the currently visible tab's stack, unless it is already on top (a double tap). */
    fun navigate(route: NavKey) {
        if (backStack.lastOrNull() != route) backStack.add(route)
    }

    /**
     * An entry's own up action: pops only while [entry] is still the visible stack's top, so a double tap on Back (or
     * a late "leave" signal after the user already left) cannot pop the parent as well. Always returns `true`: a
     * stale up is handled by doing nothing.
     */
    fun onBack(entry: NavKey): Boolean {
        if (backStack.lastOrNull() == entry) onBack()
        return true
    }

    /** @return `true` if the back press was handled; `false` if the caller should finish the activity. */
    fun onBack(): Boolean {
        val stack = backStack
        return when {
            stack.size > 1 -> {
                stack.removeAt(stack.lastIndex)
                true
            }
            selectedTab != Routes.Now -> {
                setSelectedTab(Routes.Now)
                true
            }
            // Finishing the activity would clear a busy entry's ViewModel and cancel its work: show it instead.
            else -> {
                val (tab, blocker) = backStacks.firstNotNullOfOrNull { (tab, stack) -> busyBlocker(stack)?.let { tab to it } }
                    ?: return false
                setSelectedTab(tab)
                blocker.onBlocked()
                true
            }
        }
    }
}

/**
 * Which entries [TopLevelBackStack.blockLeaving] holds. A ViewModel only so [rememberTopLevelBackStack] can keep it
 * across activity recreation: a block registered from an entry's ViewModel ([blockLeavingWhileBusy]) outlives the
 * activity's composition, and the recreated [TopLevelBackStack] must still see it.
 */
class LeaveBlockers : ViewModel() {
    internal class Blocker(val isBusy: () -> Boolean, val onBlocked: () -> Unit)

    internal val byEntry = mutableMapOf<NavKey, Blocker>()
}

private const val LEAVE_HOLD_KEY = "com.akane.voltwise.ui.navigation.LeaveHold"

private class LeaveHold(val refusals: Channel<Unit>, private val unblock: () -> Unit) : AutoCloseable {
    override fun close() {
        unblock()
        refusals.close()
    }
}

/**
 * Blocks leaving this ViewModel's entry whenever [isBusy] says so, for the ViewModel's whole life rather than while its
 * screen is composed: an entry on a tab that isn't visible isn't composed, yet a link to that tab's root would still
 * pop it and clear this ViewModel, cancelling its work. [blockLeaving] is [TopLevelBackStack.blockLeaving] for the
 * entry; only the first call registers, and clearing the ViewModel lifts the block. Every call returns the same
 * channel of refusals, which the screen drains to say why it stayed, including a refusal made while it was off screen.
 */
fun ViewModel.blockLeavingWhileBusy(
    blockLeaving: (isBusy: () -> Boolean, onBlocked: () -> Unit) -> () -> Unit,
    isBusy: () -> Boolean,
): ReceiveChannel<Unit> {
    getCloseable<LeaveHold>(LEAVE_HOLD_KEY)?.let { return it.refusals }
    val refusals = Channel<Unit>(Channel.CONFLATED)
    addCloseable(LEAVE_HOLD_KEY, LeaveHold(refusals, blockLeaving(isBusy) { refusals.trySend(Unit) }))
    return refusals
}

@Composable
fun rememberTopLevelBackStack(): TopLevelBackStack {
    val nowStack = rememberNavBackStack(Routes.Now)
    val insightsStack = rememberNavBackStack(Routes.Insights)
    val historyStack = rememberNavBackStack(Routes.History)
    val appsStack = rememberNavBackStack(Routes.Apps)
    val settingsStack = rememberNavBackStack(Routes.Settings)
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    val leaveBlockers = viewModel { LeaveBlockers() }
    return remember(nowStack, insightsStack, historyStack, appsStack, settingsStack, leaveBlockers) {
        val backStacks: Map<Routes, NavBackStack<NavKey>> = mapOf(
            Routes.Now to nowStack,
            Routes.Insights to insightsStack,
            Routes.History to historyStack,
            Routes.Apps to appsStack,
            Routes.Settings to settingsStack,
        )
        TopLevelBackStack(
            backStacks = backStacks,
            getSelectedTab = { TOP_LEVEL_TABS[selectedIndex] },
            setSelectedTab = { selectedIndex = TOP_LEVEL_TABS.indexOf(it) },
            leaveBlockers = leaveBlockers,
        )
    }
}

/**
 * Applies a `destination` intent extra value (see [Destinations]) to this back stack. Every value is an explicit
 * link, so the target tab is reset to its root first ([openRoot]): a retained detail stack never stands in for the
 * requested screen, and a detail link (session, health, status) leaves exactly root + that detail however often it
 * repeats. Bottom-bar [TopLevelBackStack.select] keeps its stack retention. A detail link whose tab a
 * [TopLevelBackStack.blockLeaving] entry kept off its root pushes nothing: the blocked screen stays on top.
 */
fun TopLevelBackStack.openDestination(value: String) {
    val sessionId = Destinations.sessionIdOrNull(value)
    when {
        sessionId != null -> if (openRoot(Routes.History)) navigate(Routes.SessionDetails(sessionId))
        value == Destinations.NOW -> openRoot(Routes.Now)
        value == Destinations.INSIGHTS -> openRoot(Routes.Insights)
        value == Destinations.HISTORY -> openRoot(Routes.History)
        value == Destinations.APPS -> openRoot(Routes.Apps)
        value == Destinations.SETTINGS -> openRoot(Routes.Settings)
        value == Destinations.HEALTH -> if (openRoot(Routes.Now)) navigate(Routes.Health)
        value == Destinations.STATUS -> if (openRoot(Routes.Settings)) navigate(Routes.SettingsStatus)
    }
}
