package com.akane.voltwise.battery.insights.model

import java.time.ZoneId
import java.time.ZoneOffset

/** Pure analysis inputs. The repository supplies only closed sessions (appWindow only when READY). */
data class InsightInputs(
    val nowMs: Long,
    val todayEpochDay: Long,
    val fullUah: Long?,
    val sessions: List<SessionInput>,
    val days: List<DayInput>,
    val appSessions: List<AppSessionInput>,
    val deviceWakers: List<DeviceWakerInput>,
    val capacity: List<CapacityPointInput>,
    val dozeUserWhitelist: Set<String>?,
    val actions: List<AppliedActionInput>,
    val feedback: Map<String, Double>,
    /** Zone used to assign local epoch days; UTC preserves fixtures without a repository clock. */
    val zone: ZoneId = ZoneOffset.UTC,
    val highBatteryAlertEnabled: Boolean = false,
)

data class SessionInput(
    val id: String,
    val kind: SessionKind,
    val startMs: Long,
    val endMs: Long,
    val observedMs: Long,
    val screenOnMs: Long,
    val screenOffMs: Long,
    val screenOnCoveredMs: Long?,
    val screenOffCoveredMs: Long?,
    val screenOnUah: Long?,
    val screenOffUah: Long?,
    val cpuSuspendMs: Long?,
    val screenOffSuspendMs: Long?,
    val dozeMs: Long?,
    val screenOffDozeMs: Long?,
    val startLevel: Int?,
    val endLevel: Int?,
    val peakTemperatureDeciC: Int?,
    val imported: Boolean,
    val appWindow: AppWindowInput?,
)

data class AppWindowInput(
    val basis: WindowBasis,
    val captureStartMs: Long,
    val captureEndMs: Long,
    val fullRowSet: Boolean,
    /** Additional waker rows stored before profile filtering; null for inputs without storage metadata. */
    val wakersStored: Int? = null,
)

data class DayInput(
    val epochDay: Long,
    val screenOnMs: Long,
    val screenOffMs: Long,
    val screenOnCoveredMs: Long?,
    val screenOffCoveredMs: Long?,
    val screenOnDischargeUah: Long,
    val screenOffDischargeUah: Long,
    val chargedUah: Long,
    val cpuSuspendMs: Long?,
    val screenOffSuspendMs: Long?,
    val dozeMs: Long?,
    val screenOffDozeMs: Long?,
    val peakTemperatureDeciC: Int?,
)

data class AppSessionInput(
    val sessionId: String,
    val uid: Int,
    val packageName: String,
    val rank: Int,
    val powerMah: Double,
    val cpuMs: Long?,
    val fgMs: Long?,
    val bgMs: Long?,
    val wakelockMs: Long?,
    val mobileBytes: Long?,
    val wifiBytes: Long?,
    val wakeupAlarms: Long?,
    val partialWakelockCount: Long?,
    val partialWakelockBgMs: Long?,
    val jobCount: Long?,
    val jobMs: Long?,
    val syncCount: Long?,
    val fgServiceMs: Long?,
    val topMs: Long?,
    val mobileActiveMs: Long?,
    val gpsMs: Long?,
    val sensorMs: Long?,
    val isOthers: Boolean,
    val topWakelockTag: String?,
    val topAlarmTag: String?,
    val topJobName: String?,
)

data class DeviceWakerInput(val sessionId: String, val kind: WakerKind, val name: String, val count: Long, val totalMs: Long)
data class CapacityPointInput(val atMs: Long, val mah: Double, val confidence: Int)
data class AppliedActionInput(
    val id: Long,
    val findingKey: String,
    val type: ActionType,
    val packageName: String?,
    val uid: Int?,
    val appliedAtMs: Long?,
    val status: ActionStatus,
    val metric: Metric? = null,
)

data class InsightReport(val generatedAtMs: Long, val findings: List<Finding>, val headline: Finding?)
data class Finding(
    val key: String,
    val type: FindingType,
    val severity: Severity,
    val confidence: Confidence,
    val score: Double,
    val subject: Subject,
    val direction: Direction?,
    val evidence: List<Evidence>,
    val series: List<SeriesPoint>,
    val recommendations: List<Recommendation>,
    val attributions: List<Attribution> = emptyList(),
)

/** Matched observations are hints, not evidence of causation. */
data class Attribution(
    val kind: AttributionKind,
    val name: String,
    val packageName: String?,
    val value: Double,
    val unit: MetricUnit,
    val sessions: Int,
)
enum class AttributionKind { KERNEL_WAKELOCK, WAKEUP_REASON, APP }

sealed interface Subject {
    data object Device : Subject
    data class App(val uid: Int, val packageName: String) : Subject
}

data class Evidence(val metric: Metric, val observed: Double, val baseline: Double?, val unit: MetricUnit, val sessions: Int)
data class SeriesPoint(val atMs: Long, val value: Double, val baselineLow: Double?, val baselineHigh: Double?)
data class Recommendation(val action: ActionType, val reversible: Boolean, val requiresPrivilege: Boolean)

enum class SessionKind { CHARGE, DISCHARGE, PLUGGED }
enum class WindowBasis { DELTA, ABSOLUTE, WINDOW_RESET }
enum class WakerKind { KERNEL_WAKELOCK, WAKEUP_REASON }
enum class ActionStatus { PREPARED, APPLIED, FAILED, UNKNOWN, REVERTED, ONE_SHOT }
enum class Severity { INFO, LOW, MEDIUM, HIGH }
enum class Confidence { LOW, MEDIUM, HIGH }
enum class Direction { UP, DOWN }
enum class FindingType {
    APP_DRAIN_ANOMALY, NEW_HEAVY_APP, BACKGROUND_RUNAWAY, STUCK_WAKELOCK, WAKEUP_STORM, JOB_STORM,
    DOZE_BLOCKED, DOZE_WHITELISTED_DRAINER, SCREEN_OFF_DRAIN_HIGH, TREND, CHARGING_AT_FULL,
    HOT_CHARGING, HEALTH_DECLINE, BACKGROUND_LOCATION, BACKGROUND_RADIO, LINGERING_FOREGROUND_SERVICE, ACTION_EFFECT,
}
enum class ActionType(val reversible: Boolean, val requiresPrivilege: Boolean) {
    RESTRICT_BACKGROUND(true, true),
    STANDBY_BUCKET_RESTRICTED(true, true),
    STANDBY_BUCKET_RARE(true, true),
    FORCE_STOP(false, true),
    REMOVE_DOZE_WHITELIST(true, true),
    OPEN_APP_SETTINGS(true, false),
    OPEN_BATTERY_OPTIMIZATION_SETTINGS(true, false),
    ENABLE_HIGH_BATTERY_ALERT(true, false),
}

enum class MetricUnit {
    MAH_PER_H, COUNT_PER_H, MS_PER_H, BYTES_PER_H, SHARE, RATIO, PCT_PER_H, PCT, CELSIUS, MAH,
    MS, COUNT, PCT_PER_YEAR,
}
enum class Metric(val unit: MetricUnit) {
    POWER_MAH_PER_H(MetricUnit.MAH_PER_H),
    WAKEUP_ALARMS_PER_H(MetricUnit.COUNT_PER_H),
    PARTIAL_WAKELOCKS_PER_H(MetricUnit.COUNT_PER_H),
    PARTIAL_WAKELOCK_BG_SHARE(MetricUnit.SHARE),
    BG_TIME_SHARE(MetricUnit.SHARE),
    FGS_TO_FOREGROUND_RATIO(MetricUnit.RATIO),
    CPU_MS_PER_H(MetricUnit.MS_PER_H),
    FOREGROUND_MS_PER_H(MetricUnit.MS_PER_H),
    FGS_MS_PER_H(MetricUnit.MS_PER_H),
    TOP_MS_PER_H(MetricUnit.MS_PER_H),
    WAKELOCK_MS_PER_H(MetricUnit.MS_PER_H),
    JOBS_PER_H(MetricUnit.COUNT_PER_H),
    JOB_MS_PER_H(MetricUnit.MS_PER_H),
    SYNCS_PER_H(MetricUnit.COUNT_PER_H),
    GPS_MS_PER_H(MetricUnit.MS_PER_H),
    SENSOR_MS_PER_H(MetricUnit.MS_PER_H),
    RADIO_ACTIVE_MS_PER_H(MetricUnit.MS_PER_H),
    MOBILE_BYTES_PER_H(MetricUnit.BYTES_PER_H),
    WIFI_BYTES_PER_H(MetricUnit.BYTES_PER_H),
    WINDOW_DRAIN_SHARE(MetricUnit.SHARE),
    SCREEN_OFF_PCT_PER_H(MetricUnit.PCT_PER_H),
    DEEP_DOZE_SHARE(MetricUnit.SHARE),
    SCREEN_OFF_DEEP_SLEEP_SHARE(MetricUnit.SHARE),
    SCREEN_ON_PCT_PER_H(MetricUnit.PCT_PER_H),
    DAILY_USE_PCT(MetricUnit.PCT),
    TEMPERATURE_C(MetricUnit.CELSIUS),
    CAPACITY_MAH(MetricUnit.MAH),
    CAPACITY_CHANGE_PCT_PER_YEAR(MetricUnit.PCT_PER_YEAR),
    PLUGGED_AT_FULL_MS(MetricUnit.MS),
}
