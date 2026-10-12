package com.akane.voltwise.battery.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.akane.voltwise.R
import com.akane.voltwise.battery.mainActivityIntent
import com.akane.voltwise.battery.data.BatteryRepository
import com.akane.voltwise.battery.service.MonitoringControl
import com.akane.voltwise.battery.service.SamplingDemand
import com.akane.voltwise.ui.navigation.Destinations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Quick Settings tile: live current/power while monitoring is on and the shade is open (holds
 * [SamplingDemand] so the sampler runs at 2 s), tap toggles [MonitoringControl], long-press opens the
 * app (Android reserves long-press for the activity named by the `QS_TILE_PREFERENCES` filter).
 */
class MonitorTileService : TileService(), KoinComponent {
    private val repository: BatteryRepository by inject()
    private val demand: SamplingDemand by inject()
    private val monitoring: MonitoringControl by inject()

    private val session = TileListenSession()

    override fun onStartListening() {
        super.onStartListening()
        val listenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        session.start(listenScope, monitoring.isMonitoring) { demand.acquire(DEMAND_TAG) }
        combine(repository.realtimeFlow, monitoring.isMonitoring, ::render).launchIn(listenScope)
    }

    override fun onStopListening() {
        session.stop()
        super.onStopListening()
    }

    /** Defensive: covers the tile being removed from the shade while still listening. */
    override fun onTileRemoved() {
        session.stop()
        super.onTileRemoved()
    }

    /** Defensive: covers the service being torn down without a preceding [onStopListening]. */
    override fun onDestroy() {
        session.stop()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        if (tileClickNeedsUnlock(Build.VERSION.SDK_INT, isLocked)) {
            unlockAndRun { toggle() }
        } else {
            toggle()
        }
    }

    private fun toggle() {
        if (monitoring.isMonitoring.value) {
            monitoring.stop()
        } else if (monitoring.start() == MonitoringControl.StartResult.BLOCKED) {
            openApp()
        }
    }

    private fun render(realtime: BatteryRepository.Realtime, isMonitoring: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (isMonitoring) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        val text = TileText.of(
            isMonitoring = isMonitoring,
            currentMa = realtime.currentMa,
            powerMw = realtime.powerMw,
            offText = getString(R.string.tile_off),
            noReadingText = getString(R.string.component_no_value),
            unitMa = getString(R.string.tile_unit_ma),
            unitW = getString(R.string.tile_unit_w),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.label = getString(R.string.tile_label)
            tile.subtitle = text
        } else {
            tile.label = text
        }
        tile.updateTile()
    }

    // The deprecated Intent overload runs below API 34 only, where it is the one that exists.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = mainActivityIntent(this, Destinations.NOW)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                OPEN_APP_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        internal const val OPEN_APP_REQUEST_CODE = 21
        private const val DEMAND_TAG = "qs_tile"
    }
}
