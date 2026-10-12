package com.akane.voltwise.battery.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.akane.voltwise.battery.data.BatteryRepository
import com.akane.voltwise.battery.data.sampling.KeyValueStore
import com.akane.voltwise.battery.data.sampling.SharedPreferencesStore
import com.akane.voltwise.battery.drain.DrainNotificationManager
import kotlinx.coroutines.flow.StateFlow

internal const val START_FROM_PROMPT_ACTION = "com.akane.voltwise.action.START_FROM_PROMPT"

internal fun monitoringStateStore(context: Context): KeyValueStore =
    SharedPreferencesStore(context.getSharedPreferences("monitoring_state", Context.MODE_PRIVATE))

/** Called only after foreground promotion succeeds and history is not being cleared. */
internal fun recordPromptStart(action: String?, store: KeyValueStore) {
    if (action == START_FROM_PROMPT_ACTION) store.edit(mapOf("monitoring_wanted" to "true"))
}

internal fun stopMonitoring(store: KeyValueStore, stopService: () -> Unit) {
    store.edit(mapOf("monitoring_wanted" to "false"))
    stopService()
}

/** Starts and stops [BatteryMonitorService]; the service itself starts and stops the sampler. */
class MonitoringController internal constructor(
    override val isMonitoring: StateFlow<Boolean>,
    private val store: KeyValueStore,
    private val startService: () -> MonitoringControl.StartResult,
    private val stopService: () -> Unit,
) : MonitoringControl {
    constructor(context: Context, repository: BatteryRepository) : this(
        isMonitoring = repository.isMonitoringFlow,
        store = monitoringStateStore(context),
        startService = {
            DrainNotificationManager.ensureChannel(context)
            try {
                ContextCompat.startForegroundService(context, Intent(context, BatteryMonitorService::class.java))
                MonitoringControl.StartResult.STARTED
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (API 31+) is an IllegalStateException, as is
                // any other "not allowed to start service" refusal from the background.
                Log.w("MonitoringController", "Foreground service start refused (${e.javaClass.simpleName})")
                MonitoringControl.StartResult.BLOCKED
            }
        },
        stopService = { context.stopService(Intent(context, BatteryMonitorService::class.java)) },
    )

    // whittle: absent flags preserve pre-upgrade auto-resume; an explicit stop opts out of update resumes.
    override val monitoringWanted: Boolean
        get() = store.getString("monitoring_wanted") != "false"

    override fun start(): MonitoringControl.StartResult {
        val result = if (isMonitoring.value) MonitoringControl.StartResult.ALREADY_RUNNING else startService()
        if (result != MonitoringControl.StartResult.BLOCKED) {
            store.edit(mapOf("monitoring_wanted" to "true"))
        }
        return result
    }

    override fun stop() = stopMonitoring(store, stopService)
}
