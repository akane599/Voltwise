package com.akane.voltwise.viewmodel

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akane.voltwise.battery.data.BatteryRepository
import com.akane.voltwise.battery.data.DesignCapacityReading
import com.akane.voltwise.battery.data.DesignCapacitySource
import com.akane.voltwise.battery.data.db.CapacityEstimateRow
import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.SessionType
import com.akane.voltwise.battery.data.uah
import com.akane.voltwise.battery.data.usableStoredEstimates
import com.akane.voltwise.battery.measurement.CapacityConfidence
import com.akane.voltwise.battery.measurement.HealthSummary
import com.akane.voltwise.settings.SettingsWrites
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the Health screen reads, as one seam: [DefaultHealthRepository] on device, a fake in unit tests. The design
 * capacity is the app-wide [DesignCapacitySource] (the same one Now's Health card reads).
 */
interface HealthRepository {
    /** The Settings override, else sysfs `charge_full_design` read once through root (see [DesignCapacitySource]). */
    val design: Flow<DesignCapacityReading>

    /** The newest [limit] sessions of any type, newest first (the query Now's Health card reads). */
    fun recentSessions(limit: Int): Flow<List<ChargeSession>>

    /** The newest [limit] sessions with a stored capacity estimate, newest first (the trend's projection). */
    fun capacityEstimates(limit: Int): Flow<List<CapacityEstimateRow>>

    /** Whether Android reports a cycle count here (`EXTRA_CYCLE_COUNT`, API 34+). */
    val cyclesSupported: Boolean

    /** The sticky `ACTION_BATTERY_CHANGED` cycle count, or null when not reported. Main-safe. */
    suspend fun cycleCount(): Int?

    /** Writes the Settings design-capacity override (0 = automatic); throws when it is invalid or storage fails. */
    suspend fun setDesignCapacity(mAh: Int)
}

/**
 * [HealthRepository] over the app's repositories, the shared design capacity, the settings store (the override) and
 * the sticky battery broadcast.
 */
class DefaultHealthRepository(
    private val context: Context,
    private val repository: BatteryRepository,
    designCapacity: DesignCapacitySource,
    private val settings: SettingsStore,
) : HealthRepository {
    override val design = designCapacity.design
    override fun recentSessions(limit: Int) = repository.sessionDao.filteredSessions(null, "", limit)
    override fun capacityEstimates(limit: Int) = repository.sessionDao.capacityEstimates(limit)

    override val cyclesSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    override suspend fun cycleCount(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return withContext(Dispatchers.IO) {
            try {
                val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                sticky?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)?.takeIf { it >= 0 }
            } catch (e: RuntimeException) {
                null
            }
        }
    }

    override suspend fun setDesignCapacity(mAh: Int) =
        settings.set(DESIGN_CAPACITY_FIELD, SettingsWrites.normalize(DESIGN_CAPACITY_FIELD, mAh))

    private companion object {
        const val DESIGN_CAPACITY_FIELD = "designCapacityMah"
    }
}

/**
 * Health: the combined capacity estimate against the design capacity (the shared [HealthSummary] rule, fed exactly
 * as Now's Health card is: the newest [HealthSummary.SESSIONS] sessions and the shared [DesignCapacitySource]), the
 * cycle count, and the newest [TREND_SESSIONS] stored per-session estimates for the trend. "Set design capacity"
 * writes the Settings override right here ([setDesignCapacity]), as the Settings row does.
 */
class HealthViewModel(
    private val source: HealthRepository,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val designWriteFailed = MutableStateFlow(false)

    private val cycles: Flow<CycleCountState> = flow {
        emit(if (!source.cyclesSupported) CycleCountState.Unsupported else source.cycleCount()?.let(CycleCountState::Count) ?: CycleCountState.NotReported)
    }

    val state: StateFlow<HealthUiState> = combine(
        source.recentSessions(HealthSummary.SESSIONS),
        source.capacityEstimates(TREND_SESSIONS),
        source.design,
        cycles,
        designWriteFailed,
    ) { sessions, trend, design, cycleCount, writeFailed -> map(sessions, trend, design, cycleCount).copy(designWriteFailed = writeFailed) }
        .distinctUntilChanged()
        .flowOn(computeDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HealthUiState())

    /** The dialog's value (0 = automatic); a failed write shows until the next one succeeds. */
    fun setDesignCapacity(mAh: Int) {
        viewModelScope.launch {
            designWriteFailed.value = try {
                source.setDesignCapacity(mAh)
                false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                true
            }
        }
    }

    private fun map(
        sessions: List<ChargeSession>,
        trend: List<CapacityEstimateRow>,
        design: DesignCapacityReading,
        cycleCount: CycleCountState,
    ): HealthUiState {
        // Same list, same parse, same rule and design capacity as Now's card (NowMapping.healthSummary).
        val summary = HealthSummary.withDesign(
            usableStoredEstimates(sessions),
            design.uah,
        )
        return HealthUiState(
            loaded = true,
            summary = summary?.let { HealthFigures(it.estimate.fullMah, it.estimate.confidence, it.healthPercent) },
            design = when (design) {
                is DesignCapacityReading.Known -> DesignCapacityState.Known(
                    mah = ((design.uah + UAH_ROUNDING) / UAH_PER_MAH).toInt(),
                    source = if (design.fromSettings) DesignSource.SETTINGS else DesignSource.BATTERY,
                )
                DesignCapacityReading.Checking -> DesignCapacityState.Checking
                DesignCapacityReading.Unknown -> DesignCapacityState.Unknown
            },
            cycles = cycleCount,
            estimates = trend.mapNotNull(::pointOf).sortedWith(compareBy({ it.timeMs }, { it.sessionId })),
        )
    }

    private fun pointOf(row: CapacityEstimateRow): CapacityPoint? {
        val estimate = HealthSummary.storedEstimate(row.capacityEstimateMah, row.capacityConfidence, row.capacityBasis) ?: return null
        return CapacityPoint(
            sessionId = row.sessionId,
            timeMs = row.endTime ?: row.lastSampleTime ?: row.startTime,
            type = row.type,
            startLevel = row.startLevel,
            endLevel = row.endLevel,
            capacityMah = estimate.fullMah,
            confidence = estimate.confidence,
        )
    }

    companion object {
        /** How many of the newest estimates the trend reads (months of history at a few sessions a day). */
        const val TREND_SESSIONS = 1_000

        private const val STOP_TIMEOUT_MS = 5_000L
        private const val UAH_PER_MAH = 1_000L
        private const val UAH_ROUNDING = 500L
    }
}

/** The Health screen: plain values; the UI formats them with the viewer's locale and string resources. */
@Immutable
data class HealthUiState(
    /** False until the first sessions query answers (the screen shows only its header until then). */
    val loaded: Boolean = false,
    /** The combined estimate (and health % when a design capacity is known); null before the first estimate. */
    val summary: HealthFigures? = null,
    val design: DesignCapacityState = DesignCapacityState.Checking,
    val cycles: CycleCountState = CycleCountState.Unsupported,
    /** Every stored per-session estimate in the trend window, oldest first. */
    val estimates: List<CapacityPoint> = emptyList(),
    /** Health's own design-capacity write failed (shown until the next write succeeds). */
    val designWriteFailed: Boolean = false,
)

/** [HealthSummary] as shown: the same numbers as Now's Health card. */
@Immutable
data class HealthFigures(val capacityMah: Int, val confidence: CapacityConfidence, val healthPercent: Double?)

sealed interface DesignCapacityState {
    /** Settings is on auto and the one root sysfs read (app-wide, [DesignCapacitySource]) hasn't answered yet. */
    data object Checking : DesignCapacityState

    /** Settings is on auto and the battery doesn't report one (or there's no root): health % can't be shown. */
    data object Unknown : DesignCapacityState
    @Immutable
    data class Known(val mah: Int, val source: DesignSource) : DesignCapacityState
}

enum class DesignSource {
    /** The Settings override. */
    SETTINGS,

    /** sysfs `charge_full_design`, read through root. */
    BATTERY,
}

sealed interface CycleCountState {
    /** Below API 34 Android has no cycle count: the UI hides it. */
    data object Unsupported : CycleCountState
    data object NotReported : CycleCountState
    @Immutable
    data class Count(val cycles: Int) : CycleCountState
}

/** One session's stored full-capacity estimate, at the time the session ended (or its latest save while open). */
@Immutable
data class CapacityPoint(
    val sessionId: String,
    val timeMs: Long,
    val type: SessionType,
    val startLevel: Int?,
    val endLevel: Int?,
    val capacityMah: Int,
    val confidence: CapacityConfidence,
)
