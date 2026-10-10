package com.akane.voltwise.viewmodel

import com.akane.voltwise.battery.data.BatteryRepository
import com.akane.voltwise.battery.data.SessionDrain
import com.akane.voltwise.battery.data.usableStoredEstimates
import com.akane.voltwise.battery.data.db.BatterySample
import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.DailySummary
import com.akane.voltwise.battery.data.sampling.ChargerType
import com.akane.voltwise.battery.data.sampling.DailySummaryReplay
import com.akane.voltwise.battery.measurement.BatteryReading
import com.akane.voltwise.battery.measurement.CalibrationState
import com.akane.voltwise.battery.measurement.CurrentCalibration
import com.akane.voltwise.battery.measurement.EtaHold
import com.akane.voltwise.battery.measurement.HealthSummary
import com.akane.voltwise.battery.measurement.PowerState
import com.akane.voltwise.ui.components.chart.TimePoint
import com.akane.voltwise.ui.components.chart.TimeWindow

/**
 * How [NowViewModel] turns repository values into [NowUiState] parts. Pure; the reusable rules live below it
 * ([HealthSummary], [SessionDrain], `TopApps`, `AppLabel`), this is only Now's presentation of them.
 */
internal object NowMapping {
    const val DOWNSAMPLE_ABOVE = 2_400
    const val DOWNSAMPLE_BUCKETS = 600

    /** The live trace breaks where readings are further apart than 3 screen-on polls (the app was away). */
    const val LIVE_MAX_GAP_MS = 95_000L

    /**
     * Time left needs monitoring (the discharge estimate is the writer's); time to full is shown while charging even
     * without it, because every capture carries Android's own. The held estimate itself ([EtaHold]) is shared with
     * the ongoing notification.
     */
    fun hero(reading: EtaHold.Reading, monitoring: Boolean, startBlocked: Boolean): HeroState {
        val realtime = reading.reading
        val sample = realtime.sample
        val showEta = sample != null && (monitoring || realtime.powerState == PowerState.CHARGING)
        val eta = if (showEta && sample != null) {
            reading.held?.let { held -> Eta((held.remainingMs - (sample.timestamp - held.atMs)).coerceAtLeast(0), held.basis) }
        } else null
        return HeroState(
            hasReading = sample != null,
            level = realtime.level,
            power = realtime.powerState,
            charger = ChargerType.of(realtime.plugged),
            eta = eta,
            monitoring = monitoring,
            startBlocked = startBlocked && !monitoring,
            etaPending = if (sample == null || eta != null) null else etaPending(realtime.powerState, monitoring),
        )
    }

    /** What to say without an estimate ([EtaPending]); null outside discharging and charging. */
    fun etaPending(power: PowerState, monitoring: Boolean): EtaPending? = when (power) {
        PowerState.DISCHARGING -> if (monitoring) EtaPending.ESTIMATING_LEFT else EtaPending.NEEDS_MONITORING_LEFT
        PowerState.CHARGING -> if (monitoring) EtaPending.ESTIMATING_FULL else EtaPending.NEEDS_MONITORING_FULL
        PowerState.PLUGGED, PowerState.UNKNOWN -> null
    }

    fun readouts(reading: BatteryRepository.Realtime) = Readouts(
        currentMa = reading.currentMa,
        powerW = reading.powerMw?.div(1_000),
        temperatureC = reading.temperatureC?.toDouble(),
        voltageV = reading.voltageMv?.div(1_000.0),
    )

    /** The fuel gauge's full charge from the latest reading, for %/h (null → the Health estimate stands in). */
    fun counterFullUah(reading: BatteryRepository.Realtime): Long? =
        HealthSummary.counterFullUah(reading.sample?.chargeCounterUah, reading.level)

    /** Adds [sample] when newer than the last one and drops what fell out of the live window; true if added. */
    fun appendLive(buffer: MutableList<BatterySample>, sample: BatterySample): Boolean {
        if (sample.source != DailySummaryReplay.SAMPLE_SOURCE) return false
        val last = buffer.lastOrNull()
        if (last != null && sample.timestamp <= last.timestamp) return false
        buffer += sample
        val cutoff = sample.timestamp - TraceRange.LIVE.spanMs
        buffer.removeAll { it.timestamp < cutoff }
        return true
    }

    /**
     * Rows → calibrated mA points in [endMs]'s window (plus one row before it, so the line reaches the edge). A gap
     * marker goes where monitoring restarted (a new observation id) or a row recorded an interruption.
     */
    fun trace(range: TraceRange, samples: List<BatterySample>, endMs: Long, calibration: CurrentCalibration): TraceState {
        val startMs = endMs - range.spanMs
        val live = samples.filter { it.source == DailySummaryReplay.SAMPLE_SOURCE }
        val first = (live.indexOfFirst { it.timestamp >= startMs }.takeIf { it >= 0 } ?: live.size).minus(1).coerceAtLeast(0)
        val points = ArrayList<TimePoint>()
        var previous: BatterySample? = null
        for (sample in live.subList(first, live.size)) {
            val before = previous
            if (before != null && (before.observationId != sample.observationId || sample.boundaryReason != null)) {
                points += TimePoint((before.timestamp + sample.timestamp) / 2, null)
            }
            points += TimePoint(sample.timestamp, BatteryReading.calibratedUa(sample.currentNowUa, calibration)?.div(1_000.0))
            previous = sample
        }
        return TraceState(
            range = range,
            points = points,
            window = TimeWindow(startMs, endMs),
            maxGapMs = if (range == TraceRange.LIVE) LIVE_MAX_GAP_MS else null,
        )
    }

    /**
     * [newest] is the newest DISCHARGE session. It is the current window only while it is open and monitoring runs
     * (a row left open by a stopped process is history); otherwise it is shown as the last time on battery, ending at
     * its end or last save. All figures come from that one row, so windows never mix.
     */
    fun sinceUnplug(newest: ChargeSession?, monitoring: Boolean, fullUah: Long?): SinceUnplugState? {
        newest ?: return null
        val drain = SessionDrain.of(newest, fullUah)
        return SinceUnplugState(
            current = newest.endTime == null && monitoring,
            startedAtMs = newest.startTime,
            endedAtMs = newest.endTime ?: newest.lastSampleTime ?: newest.startTime,
            screenOn = DrainState(drain.screenOn.durationMs, drain.screenOn.currentMa, drain.screenOn.percentPerHour),
            screenOff = DrainState(drain.screenOff.durationMs, drain.screenOff.currentMa, drain.screenOff.percentPerHour),
            deepSleepPercent = drain.deepSleepPercent,
        )
    }

    /**
     * Time on battery with no covered counter interval and no charge: missing data, not none used (a legacy row's null
     * coverage counts as none, so its stored charge stands unless it is 0 as well).
     */
    fun unmeasured(row: DailySummary): Boolean {
        val coveredMs = (row.screenOnCoveredMs ?: 0) + (row.screenOffCoveredMs ?: 0)
        return row.screenOnMs + row.screenOffMs > 0 && coveredMs == 0L && row.screenOnDischargeUah + row.screenOffDischargeUah == 0L
    }

    /** [row]'s charge taken in; null on an [unmeasured] day that stored 0 (with no counter evidence, 0 is unknown). Shared with History. */
    fun chargedUah(row: DailySummary): Long? = row.chargedUah.takeUnless { unmeasured(row) && it == 0L }

    /** Today's row, as % of [fullUah] (the capacity Since unplug's %/h uses) when known, else mAh; see [unmeasured]. */
    fun today(row: DailySummary, fullUah: Long?): TodayState {
        val used = (row.screenOnDischargeUah + row.screenOffDischargeUah).takeUnless { unmeasured(row) }
        val charged = chargedUah(row)
        fun percent(uah: Long?) = if (uah != null && fullUah != null && fullUah > 0) uah * 100.0 / fullUah else null
        return TodayState(
            usedMah = used?.div(1_000.0),
            chargedMah = charged?.div(1_000.0),
            screenOnMs = row.screenOnMs,
            usedPercent = percent(used),
            chargedPercent = percent(charged),
        )
    }

    /** The newest sessions' stored estimates → [HealthSummary] (the rule the Health screen shares). */
    fun healthSummary(sessions: List<ChargeSession>, designUah: Long?): HealthSummary? = HealthSummary.withDesign(
        usableStoredEstimates(sessions),
        designUah,
    )

    fun health(summary: HealthSummary) = HealthState(summary.estimate.fullMah, summary.estimate.confidence, summary.healthPercent)

    fun notice(state: CalibrationState): CurrentCalibration? =
        if (state.noticePending) state.detected ?: state.effective else null
}
