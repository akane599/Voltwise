package com.akane.voltwise.battery.service

import com.akane.voltwise.battery.diagnostics.DiagnosticCode
import com.akane.voltwise.battery.diagnostics.DiagnosticStore
import android.app.Service
import android.app.Notification
import androidx.core.app.NotificationCompat
import com.akane.voltwise.R
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.akane.voltwise.battery.BatteryApp
import com.akane.voltwise.battery.apps.SessionSnapshotCollector
import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.SessionType
import com.akane.voltwise.battery.data.BatteryRepository
import com.akane.voltwise.battery.data.MONITORING_STOPPED_CLOSE_REASON
import com.akane.voltwise.battery.drain.DrainNotificationManager
import com.akane.voltwise.battery.drain.NotificationIssue
import com.akane.voltwise.battery.util.ShellRunner
import com.akane.voltwise.battery.util.Notifier
import com.akane.voltwise.battery.measurement.BatteryAlerts
import com.akane.voltwise.battery.measurement.BatteryAlert
import com.akane.voltwise.battery.measurement.BatteryAlertSettings
import com.akane.voltwise.battery.measurement.AlertReading
import com.akane.voltwise.battery.measurement.alertEpisodeWriteDue
import com.akane.voltwise.battery.util.UpdateGate
import com.akane.voltwise.battery.widget.WidgetUpdater
import com.akane.voltwise.settings.useFahrenheit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.koin.android.ext.android.inject

class BatteryMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository: BatteryRepository by inject()
    private val notifications: DrainNotificationManager by inject()
    private val shell: ShellRunner by inject()
    private val sessionSnapshots: SessionSnapshotCollector by inject()
    private val diagnostics: DiagnosticStore by inject()
    private var started = false
    private var monitoringStartedElapsed = 0L

    private fun startForegroundWith(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(DrainNotificationManager.NOTIFICATION_ID,
            notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(DrainNotificationManager.NOTIFICATION_ID, notification)
    }

    /**
     * A minimal notification that is cheap to build; the full one replaces it right after [startForegroundWith].
     * It carries the monitoring session's `when`, as every full post does, so the replacement doesn't move it.
     */
    private fun placeholder(sessionWhen: Long): Notification {
        DrainNotificationManager.ensureChannel(this)
        return NotificationCompat.Builder(this, DrainNotificationManager.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_battery)
            .setContentTitle(getString(R.string.monitor_channel))
            .setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .setWhen(sessionWhen).setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // After startForegroundService(), Android crashes the app unless startForeground() comes promptly and before
        // any stopSelf() (ForegroundServiceDidNotStartInTimeException). So promote first, with a cheap notification
        // when not yet running: building the full one can be slow while the main thread is busy. Not yet running
        // starts a monitoring session: one `when` for all its notifications.
        val promoted = try {
            startForegroundWith(if (started) notifications.promotionNotification() else placeholder(notifications.startSession()))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Log.e("BatteryMonitorService", "Could not enter the foreground", e)
            false
        }
        if (repository.isClearingHistory) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!promoted) {
            diagnostics.record(DiagnosticCode.START_FAILED)
            runCatching { Notifier.promptStartOnBoot(this) }
            stopSelf()
            return START_NOT_STICKY
        }
        recordPromptStart(intent?.action, monitoringStateStore(this))
        display("start prompt") { Notifier.cancelStartPrompt(this) }
        if (started) return START_STICKY
        try {
            notifications.post(notifications.getNotification())
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // Monitoring continues; the update loop below replaces the placeholder on its next push.
            Log.w("BatteryMonitorService", "Could not show the full notification", e)
        }
        started = true
        monitoringStartedElapsed = SystemClock.elapsedRealtime()
        // Application subscriptions outlive STOP writes and must be ready before either producer.
        serviceScope.startMonitoringWhenInsightsReady(
            ready = (application as BatteryApp).insightRefreshReady,
            startSampling = { repository.startSampling() },
            snapshots = { sessionSnapshots.run() },
            onFailure = {
                diagnostics.record(DiagnosticCode.APP_SCOPE_FAILED)
                stopSelf()
            },
        )
        serviceScope.launch(Dispatchers.IO) {
            // Private, excluded from automatic backup. Only latched episodes need a fresh timestamp.
            val preferences = getSharedPreferences("battery_alert_episodes", MODE_PRIVATE)
            val saved = preferences.getStringSet("latched", emptySet()).orEmpty()
            var savedElapsedMs = preferences.getLong("last_elapsed_ms", -1L).takeIf { it >= 0 }
            val savedBootCount = preferences.getInt("boot_count", -1).takeIf { it >= 0 }
            val bootCount = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 }
            val alerts = BatteryAlerts(BatteryAlert.entries.filter { it.name in saved }.toSet(), savedElapsedMs, savedBootCount)
            var alertChannelReady = false
            var previousIntervalMs: Long? = null
            combine(repository.realtimeFlow, repository.settingsFlow) { reading, settings -> reading to settings }
                .collect { (reading, settings) ->
                    val sample = reading.sample
                    if (sample == null || sample.elapsedMs == null || sample.elapsedMs < monitoringStartedElapsed) return@collect
                    val before = alerts.latches
                    // The longer of the two cadences around this interval, as ObservationEngine's gap rule
                    // allows: a 300 s → 30 s switch at screen-on is not a gap.
                    val intervalMs = maxOf(previousIntervalMs ?: reading.expectedIntervalMs, reading.expectedIntervalMs)
                    previousIntervalMs = reading.expectedIntervalMs
                    // Calibrated current: an inverted or mA-reporting device still trips the discharge alert.
                    val events = alerts.accept(AlertReading(sample.elapsedMs, sample.levelPercent,
                        sample.status, sample.plugged, reading.currentUa, sample.temperatureDeciC, intervalMs, bootCount),
                        BatteryAlertSettings(settings.lowBatteryAlertEnabled, settings.lowBatteryThreshold,
                            settings.highBatteryAlertEnabled, settings.highBatteryThreshold,
                            settings.temperatureWarningEnabled, settings.temperatureThreshold.toDouble(),
                            settings.dischargeAlertEnabled, settings.dischargeCurrentThreshold, settings.chargingCompleteAlert))
                    try {
                        if (events.isNotEmpty()) {
                            if (!alertChannelReady) {
                                Notifier.ensureAlertChannel(this@BatteryMonitorService)
                                alertChannelReady = true
                            }
                            if (Notifier.canPostAlerts(this@BatteryMonitorService)) {
                                // The alert text shows the calibrated current, as the realtime values do.
                                val shown = sample.copy(currentNowUa = reading.currentUa)
                                events.forEach { Notifier.batteryAlert(this@BatteryMonitorService, it, shown, settings) }
                            } else alerts.retryDelivery()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: RuntimeException) {
                        alerts.retryDelivery()
                        diagnostics.record(DiagnosticCode.ALERT_FAILED)
                        Log.w("BatteryAlerts", "Alert delivery failed (${e.javaClass.simpleName})")
                    }
                    if (alertEpisodeWriteDue(alerts.latches != before, alerts.latches.isNotEmpty(), savedElapsedMs, sample.elapsedMs)) {
                        // Refresh while latched so an abrupt process death does not age a still-observed episode.
                        preferences.edit().putStringSet("latched", alerts.latches.map { it.name }.toSet())
                            .putLong("last_elapsed_ms", sample.elapsedMs)
                            .putInt("boot_count", bootCount ?: -1).apply()
                        savedElapsedMs = sample.elapsedMs
                    }
                }
        }
        // Display updates: only with the screen on, on changed content, ≥5 s apart; SCREEN_ON pushes at once (gated in run).
        serviceScope.launch {
            val issue = combine(repository.error, shell.lastError, shell.access) { historyError, shellError, access ->
                NotificationIssue.of(historyError, shellError, hasAdvancedAccess = access != ShellRunner.Mode.NONE)
            }
            notifications.run(issue) { content ->
                display("notification") {
                    notifications.post(notifications.build(content))
                }
            }
        }
        serviceScope.launch {
            val gate = UpdateGate<WidgetUpdater.Content>()
            // Held estimate: one content per capture, and the time widget never drops to "—" between them.
            combine(WidgetUpdater.readings(repository.realtimeFlow), repository.settingsFlow.map { it.useFahrenheit }.distinctUntilChanged()) { reading, fahrenheit ->
                WidgetUpdater.content(this@BatteryMonitorService, reading, monitoring = true, fahrenheit) to (reading.reading.sample?.screenOn != false)
            }.collectLatest { (content, screenOn) ->
                if (!gate.awaitTurn(content, screenOn, SystemClock::uptimeMillis)) return@collectLatest
                val shown = display("widget") { WidgetUpdater.deliver(this@BatteryMonitorService, content) }
                gate.pushed(content.takeIf { shown }, SystemClock.uptimeMillis())
            }
        }
        return START_STICKY
    }

    private inline fun display(surface: String, update: () -> Unit): Boolean = try {
        update()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        diagnostics.record(DiagnosticCode.NOTIFICATION_FAILED)
        Log.w("BatteryMonitorService", "Monitoring $surface update failed (${e.javaClass.simpleName})")
        false
    }

    override fun onDestroy() {
        started = false
        repository.stopSampling()
        serviceScope.cancel()
        notifications.stopNotification()
        WidgetUpdater.push(this, repository.realtimeFlow.value.sample, monitoring = false)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

/** Keeps synchronous sampling startup on the service lifecycle dispatcher; only snapshots move off main. */
internal fun CoroutineScope.startMonitoringWhenInsightsReady(
    ready: Deferred<Unit>,
    startSampling: () -> Unit,
    snapshots: suspend () -> Unit,
    onFailure: () -> Unit,
    snapshotsDispatcher: CoroutineDispatcher = Dispatchers.Default,
): Job = launch {
    try {
        ready.await()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        onFailure()
        return@launch
    }
    // The service scope uses Main.immediate, serial with onDestroy; no suspension separates this start.
    startSampling()
    withContext(snapshotsDispatcher) { snapshots() }
}

/** Coalesce bursts without cancelling analysis: one refresh runs, and only the latest pending event is kept. */
internal suspend fun refreshOnFinalizedSessions(
    finalizedSessions: Flow<String>,
    record: (DiagnosticCode) -> Unit,
    closedSessions: Flow<ChargeSession> = emptyFlow(),
    onSubscribed: () -> Unit = {},
    refresh: suspend () -> Unit,
) = coroutineScope {
    val pending = Channel<Unit>(Channel.CONFLATED)
    // UNDISPATCHED makes registration complete before the caller starts event producers.
    val finalized = launch(start = CoroutineStart.UNDISPATCHED) {
        finalizedSessions.collect { pending.trySend(Unit) }
    }
    val closed = launch(start = CoroutineStart.UNDISPATCHED) {
        closedSessions.collect {
            // Stop cancels the service's snapshot collector before this application writer commits.
            // Its ordinary device measurements are ready now; other discharges await finalization.
            if (it.type != SessionType.DISCHARGE || it.closeReason == MONITORING_STOPPED_CLOSE_REASON) {
                pending.trySend(Unit)
            }
        }
    }
    // A synchronously failing source cancels this scope before registration can be ready.
    currentCoroutineContext().ensureActive()
    onSubscribed()
    launch {
        joinAll(finalized, closed)
        pending.close()
    }
    try {
        for (event in pending) {
            try {
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                record(DiagnosticCode.APP_SCOPE_FAILED)
            }
        }
    } finally {
        pending.cancel()
    }
}
