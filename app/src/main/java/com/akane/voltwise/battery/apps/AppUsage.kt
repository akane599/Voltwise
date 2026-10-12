package com.akane.voltwise.battery.apps

/** How a session's per-app numbers were derived; shown next to the breakdown. */
enum class AppUsageBasis {
    /** Clamped end − baseline within the same batterystats window. */
    DELTA,
    /** The stats window reset during the session; end values are used. */
    WINDOW_RESET,
    /** No baseline existed; absolute values since the last full charge are used. */
    ABSOLUTE,
}

/** Per-session breakdown state stored on `charge_sessions.appUsageStatus`. */
enum class AppUsageStatus {
    /** Session open (or snapshot debounce pending); nothing to show yet. */
    PENDING,
    READY,
    /** No root/Shizuku/ADB access when the snapshot was due. */
    NO_ACCESS,
    FAILED,
    /** Charging sessions show charging insights instead. */
    NOT_APPLICABLE,
}

/** One app's usage over a session or a stats window. `isOthers` marks the aggregated tail row. */
data class AppUsageRow(
    val uid: Int,
    val packageName: String,
    val powerMah: Double,
    val cpuTimeMs: Long? = null,
    val foregroundTimeMs: Long? = null,
    val backgroundTimeMs: Long? = null,
    val wakelockTimeMs: Long? = null,
    val mobileBytes: Long? = null,
    val wifiBytes: Long? = null,
    val isOthers: Boolean = false,
    val wakeupAlarms: Long? = null,
    val partialWakelockCount: Long? = null,
    val partialWakelockBgMs: Long? = null,
    val jobCount: Long? = null,
    val jobMs: Long? = null,
    val syncCount: Long? = null,
    val fgServiceMs: Long? = null,
    val topMs: Long? = null,
    val mobileActiveMs: Long? = null,
    val gpsMs: Long? = null,
    val sensorMs: Long? = null,
    val topWakelockTag: String? = null,
    val topAlarmTag: String? = null,
    val topJobName: String? = null,
)
