package com.akane.voltwise.battery.apps

import com.akane.voltwise.battery.util.BatteryStatsParser

/** A per-UID snapshot of one structured `dumpsys batterystats --proto --charged` dump, keyed to the stats
 * window it was taken in (`windowStartedAt`/`windowStartCount`) so two snapshots can be told
 * apart from a window reset. A3's `app_snapshots`/`app_snapshot_uids` tables model these fields
 * exactly; do not add or rename fields here without updating that schema. */
data class AppUsageSnapshot(
    val windowStartedAt: Long?,
    val windowStartCount: Long?,
    val capturedAt: Long,
    val rows: List<AppUsageRow>,
    val deepIdleMs: Long? = null,
    val deepIdleCount: Long? = null,
    val lightIdleMs: Long? = null,
    val lightIdleCount: Long? = null,
    val screenOffMs: Long? = null,
    val deviceWakers: List<DeviceWaker> = emptyList(),
    val wakersComplete: Boolean? = null,
    val tagHints: Map<Int, AppTagHints> = emptyMap(),
)

/** Device totals keyed by kind + name; names are not interchangeable across kinds. */
data class DeviceWaker(val kind: String, val name: String, val count: Long, val totalMs: Long)
data class AppTagHints(val wakelock: String?, val alarm: String?, val job: String?)

/**
 * Who a batterystats row's power belongs to. Power is accounted per UID, so when several packages share the UID
 * the power is theirs together: [Shared] says so instead of naming one member.
 */
sealed interface UidIdentity {
    /**
     * The package name lookups and stored rows use: the app's, a shared UID's representative, or A2's display name
     * for a UID with no package ("System UID 1000", "UID 10123").
     */
    val packageName: String

    data class App(override val packageName: String) : UidIdentity

    /** [members] are distinct and sorted, so the representative (the first) doesn't depend on the dump's order. */
    data class Shared(val members: List<String>) : UidIdentity {
        override val packageName: String get() = members.first()
        val memberCount: Int get() = members.size
    }

    data class NoPackage(override val packageName: String) : UidIdentity
}

fun BatteryStatsParser.AppPowerStats.identity(): UidIdentity {
    val members = packages.distinct().sorted()
    return when (members.size) {
        0 -> UidIdentity.NoPackage(packageName)
        1 -> UidIdentity.App(members.single())
        else -> UidIdentity.Shared(members)
    }
}

/** Maps a full structured parse to the per-app fields the delta/db layers need. */
fun BatteryStatsParser.FullSnapshot.toAppUsageSnapshot(): AppUsageSnapshot {
    val wakers = kernelWakelocks.map { DeviceWaker("KERNEL_WAKELOCK", it.name, it.count.toLong(), it.totalTimeMs) } +
        wakeupReasons.map { DeviceWaker("WAKEUP_REASON", it.name, it.count.toLong(), it.totalTimeMs) }
    val selectedWakers = wakers.selectSnapshotWakers()
    val wakelockHints = wakelocks.filter { it.type == BatteryStatsParser.WakelockType.PARTIAL }.groupBy { it.uid }
    val alarmHints = alarms.groupBy { it.uid }
    val jobHints = jobs.groupBy { it.uid }
    val networkByUid = network.associateBy { it.uid }
    return AppUsageSnapshot(
        windowStartedAt = startedAt,
        windowStartCount = startCount,
        capturedAt = capturedAt,
        rows = apps.map { app ->
            AppUsageRow(
                uid = app.uid,
                packageName = app.identity().packageName,
                powerMah = app.powerMah,
                cpuTimeMs = app.cpuTimeMs,
                foregroundTimeMs = app.foregroundTimeMs,
                backgroundTimeMs = app.backgroundTimeMs,
                wakelockTimeMs = app.wakeLockTimeMs,
                mobileBytes = sumBytesOrNull(app.mobileRxBytes, app.mobileTxBytes),
                wifiBytes = sumBytesOrNull(app.wifiRxBytes, app.wifiTxBytes),
                wakeupAlarms = app.wakeupAlarmCount,
                partialWakelockCount = app.partialWakelockCount,
                partialWakelockBgMs = app.partialWakelockBgTimeMs,
                jobCount = app.jobCount,
                jobMs = app.jobTimeMs,
                syncCount = app.syncCount,
                fgServiceMs = app.foregroundServiceTimeMs,
                topMs = app.topTimeMs,
                mobileActiveMs = networkByUid[app.uid]?.mobileActiveTimeMs,
                gpsMs = app.gpsTimeMs,
                sensorMs = app.sensorTimeMs,
            )
        },
        deepIdleMs = doze?.deepIdleTimeMs,
        deepIdleCount = doze?.deepIdleCount?.toLong(),
        lightIdleMs = doze?.lightIdleTimeMs,
        lightIdleCount = doze?.lightIdleCount?.toLong(),
        screenOffMs = screenOffTimeMs,
        deviceWakers = selectedWakers.entries,
        wakersComplete = deviceWakersComplete && selectedWakers.complete,
        tagHints = apps.associate { app -> app.uid to AppTagHints(
            wakelockHints[app.uid]?.maxByOrNull { it.totalTimeMs }?.tag,
            alarmHints[app.uid]?.maxByOrNull { it.wakeups }?.tag,
            jobHints[app.uid]?.maxByOrNull { it.totalTimeMs }?.jobName,
        ) },
    )
}

// Reserve 150 time-ranked kernels and 50 count-ranked reasons; lend unused slots within the 200-row budget.
private const val SNAPSHOT_KERNEL_WAKER_LIMIT = 150
private const val SNAPSHOT_WAKEUP_REASON_LIMIT = 50

internal data class SnapshotWakerSelection(val entries: List<DeviceWaker>, val complete: Boolean)

internal fun List<DeviceWaker>.selectSnapshotWakers(): SnapshotWakerSelection {
    val (kernels, reasons) = filter { it.count != 0L || it.totalMs != 0L }
        .partition { it.kind == "KERNEL_WAKELOCK" }
    val selectedKernels = kernels.sortedWith(
        compareByDescending<DeviceWaker> { it.totalMs }.thenBy { it.name },
    ).take(SNAPSHOT_KERNEL_WAKER_LIMIT + (SNAPSHOT_WAKEUP_REASON_LIMIT - reasons.size).coerceAtLeast(0))
    val selectedReasons = reasons.sortedWith(
        compareByDescending<DeviceWaker> { it.count }.thenBy { it.name },
    ).take(SNAPSHOT_KERNEL_WAKER_LIMIT + SNAPSHOT_WAKEUP_REASON_LIMIT - selectedKernels.size)
    return SnapshotWakerSelection(
        entries = selectedKernels + selectedReasons,
        complete = selectedKernels.size == kernels.size && selectedReasons.size == reasons.size,
    )
}

/** rx + tx, null only when both sides are unknown. */
private fun sumBytesOrNull(rx: Long?, tx: Long?): Long? =
    if (rx == null && tx == null) null else (rx ?: 0L) + (tx ?: 0L)
