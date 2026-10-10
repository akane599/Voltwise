package com.akane.voltwise.battery.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.akane.voltwise.battery.data.sampling.SamplerState
import com.akane.voltwise.settings.Retention
import com.akane.voltwise.settings.SettingsMigrator
import com.akane.voltwise.settings.resolveRetention
import kotlinx.coroutines.flow.first

/** Single-writer age maintenance; size limits remain independent of this settings/clock authority. */
class HistoryRetention(
    private val migrator: SettingsMigrator,
    private val store: DataStore<Preferences>,
    private val state: SamplerState,
    private val bootCount: () -> Int,
) {
    /**
     * Uses a non-backed-up clock, seeded from history read BEFORE the generation's first write.
     * A future sample cannot replace that authority; a backwards wall clock cannot lower it.
     * The cutoff also requires wall-clock agreement so a future seed or RTC fallback cannot purge
     * recent rows. Without a prior reference the first call establishes one but does not purge.
     * An absent choice uses the normal default unless settings recovered from corruption;
     * invalid choices pause.
     *
     * whittle: elapsedRealtime counts same-boot gaps and the current uptime after a reboot, but
     * misses power-off time and the previous boot's time after its last cleanup. History can live
     * longer than the chosen age. Count these gaps only when an independent trusted clock exists.
     * Initial adoption assumes the pre-existing history reference was recorded with a sane clock.
     */
    suspend fun cutoff(nowMs: Long, elapsedMs: Long = 0, previousWallMs: Long? = null): Long? {
        check(migrator.awaitMigrated()) { "Settings migration did not complete; history retention is paused" }
        val prefs = store.data.first()
        val boot = bootCount()
        val before = state.retentionClock
        val reference = before?.wallMs ?: previousWallMs?.coerceAtMost(nowMs)
        val elapsed = when {
            before == null -> 0
            before.bootCount == boot && elapsedMs >= before.elapsedMs -> elapsedMs - before.elapsedMs
            else -> elapsedMs // The current boot started after the stored reference.
        }
        val trustedNow = reference?.let { maxOf(it, minOf(nowMs, it + elapsed)) } ?: nowMs
        state.retentionClock = SamplerState.RetentionClock(trustedNow, elapsedMs, boot)
        val days = (resolveRetention(prefs) as? Retention.Days)?.days
        return if (reference != null) days?.let { minOf(trustedNow, nowMs) - it * DAY_MS } else null
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
