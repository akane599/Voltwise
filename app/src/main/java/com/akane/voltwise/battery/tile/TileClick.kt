package com.akane.voltwise.battery.tile

import com.akane.voltwise.battery.drain.actionsRequireAuth

/** Match Stop/Reset notification authentication on API 31+; unlocked taps remain immediate. */
internal fun tileClickNeedsUnlock(sdkInt: Int, locked: Boolean): Boolean = locked && actionsRequireAuth(sdkInt)
