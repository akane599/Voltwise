package com.akane.voltwise.battery.measurement

// At a 2 s cadence BatteryAlerts allows 5_000 * 3 + 10_000 = 25 s for restore.
// A 10 s refresh plus one 2 s step stays below that allowance after process death.
internal const val ALERT_REFRESH_MS = 10_000L

internal fun alertEpisodeWriteDue(
    latchesChanged: Boolean,
    latched: Boolean,
    savedElapsedMs: Long?,
    sampleElapsedMs: Long,
): Boolean = latchesChanged || (latched && (savedElapsedMs == null ||
    sampleElapsedMs < savedElapsedMs || sampleElapsedMs - savedElapsedMs >= ALERT_REFRESH_MS))
