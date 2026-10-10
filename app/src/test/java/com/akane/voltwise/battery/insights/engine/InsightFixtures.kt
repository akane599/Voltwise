package com.akane.voltwise.battery.insights.engine

import com.akane.voltwise.battery.insights.model.AppSessionInput
import com.akane.voltwise.battery.insights.model.AppWindowInput
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.InsightInputs
import com.akane.voltwise.battery.insights.model.SessionInput
import com.akane.voltwise.battery.insights.model.SessionKind
import com.akane.voltwise.battery.insights.model.WindowBasis

internal const val HOUR = 3_600_000L
internal const val APP = "example.app"
internal const val UID = 10001

internal fun session(index: Int, duration: Long = HOUR): SessionInput {
    val start = (index + 1) * 24 * HOUR
    return SessionInput(
        id = "s$index", kind = SessionKind.DISCHARGE, startMs = start, endMs = start + duration,
        observedMs = duration, screenOnMs = 0, screenOffMs = duration,
        screenOnCoveredMs = 0, screenOffCoveredMs = duration, screenOnUah = 0, screenOffUah = 200_000,
        cpuSuspendMs = null, screenOffSuspendMs = null, dozeMs = null, screenOffDozeMs = null,
        startLevel = 90, endLevel = 85, peakTemperatureDeciC = null, imported = false,
        appWindow = AppWindowInput(WindowBasis.DELTA, start, start + duration, false),
    )
}

internal fun row(id: String): AppSessionInput = AppSessionInput(
    sessionId = id, uid = UID, packageName = APP, rank = 1, powerMah = 5.0,
    cpuMs = 1_000, fgMs = 0, bgMs = 1_000, wakelockMs = 1_000,
    mobileBytes = 1_000, wifiBytes = 1_000, wakeupAlarms = 1, partialWakelockCount = 1,
    partialWakelockBgMs = 1_000, jobCount = 1, jobMs = 1_000, syncCount = 1,
    fgServiceMs = 1_000, topMs = 0, mobileActiveMs = 1_000, gpsMs = 1_000, sensorMs = 1_000,
    isOthers = false, topWakelockTag = null, topAlarmTag = null, topJobName = null,
)

internal fun highRow(id: String): AppSessionInput = row(id).copy(
    powerMah = 40.0, cpuMs = 300_000, bgMs = 2_000_000, partialWakelockBgMs = 1_000_000,
    wakeupAlarms = 100, jobCount = 100, syncCount = 100, fgServiceMs = 1_800_000,
    mobileActiveMs = 1_000_000, gpsMs = 1_000_000, sensorMs = 1_000_000,
)

internal fun inputs(sessions: List<SessionInput>, rows: List<AppSessionInput>): InsightInputs = InsightInputs(
    nowMs = (sessions.maxOfOrNull { it.endMs } ?: 0L) + HOUR, todayEpochDay = 100,
    fullUah = 4_000_000, sessions = sessions, days = emptyList(),
    appSessions = rows, deviceWakers = emptyList(), capacity = emptyList(),
    dozeUserWhitelist = emptySet(), actions = emptyList(), feedback = emptyMap(),
)

internal fun detectorInputs(type: FindingType, baselineCount: Int = 4): InsightInputs {
    val recent = if (type == FindingType.BACKGROUND_RUNAWAY) 2 else 1
    val sessions = (0 until baselineCount + recent).map { session(it) }
    val rows = sessions.mapIndexed { index, session ->
        if (index < baselineCount) {
            val row = row(session.id)
            if (type == FindingType.NEW_HEAVY_APP) row.copy(uid = 10002, packageName = "example.other") else row
        } else {
            val row = highRow(session.id)
            if (type == FindingType.STUCK_WAKELOCK) row.copy(cpuMs = 1_000) else row
        }
    }
    return inputs(sessions, rows)
}

internal fun AppSessionInput.unsupported(): AppSessionInput = copy(
    powerMah = Double.NaN, cpuMs = null, fgMs = null, bgMs = null, wakelockMs = null,
    mobileBytes = null, wifiBytes = null, wakeupAlarms = null, partialWakelockCount = null,
    partialWakelockBgMs = null, jobCount = null, jobMs = null, syncCount = null,
    fgServiceMs = null, topMs = null, mobileActiveMs = null, gpsMs = null, sensorMs = null,
)
