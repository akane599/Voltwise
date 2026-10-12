package com.akane.voltwise.battery.util

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.akane.voltwise.battery.measurement.BatteryAlert
import com.akane.voltwise.battery.data.db.BatterySample
import com.akane.voltwise.settings.AppSettings
import java.util.Locale
import com.akane.voltwise.R
import com.akane.voltwise.battery.BatteryMainActivity
import com.akane.voltwise.battery.drain.DrainNotificationManager
import com.akane.voltwise.battery.drain.formatDrainRate
import com.akane.voltwise.battery.drain.formatTemperature
import com.akane.voltwise.battery.service.BatteryMonitorService
import com.akane.voltwise.battery.service.START_FROM_PROMPT_ACTION
import com.akane.voltwise.settings.useFahrenheit
import com.akane.voltwise.ui.navigation.Destinations

internal data class StartPromptIntents<T>(val action: T, val content: T = action)

object Notifier {
    private const val START_PROMPT_ID = 1000
    internal const val OPEN_APP_REQUEST_CODE = 20

    fun promptStartOnBoot(ctx: Context) {
        DrainNotificationManager.ensureChannel(ctx)
        // Keep the direct service PendingIntent: the notification tap grants the API 31+ FGS exemption.
        val startIntent = Intent(ctx, BatteryMonitorService::class.java).setAction(START_FROM_PROMPT_ACTION)
        val pi = if (Build.VERSION.SDK_INT >= 26) {
            PendingIntent.getForegroundService(
                ctx, 1, startIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            PendingIntent.getService(
                ctx, 1, startIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        val intents = StartPromptIntents(pi)
        val n = NotificationCompat.Builder(ctx, DrainNotificationManager.CHANNEL_ID)
            .setContentTitle(ctx.getString(R.string.monitoring_ready))
            .setContentText(ctx.getString(R.string.tap_to_start))
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setAutoCancel(true)
            .setContentIntent(intents.content)
            .addAction(android.R.drawable.ic_media_play, ctx.getString(R.string.start_monitoring), intents.action)
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(START_PROMPT_ID, n)
    }

    fun cancelStartPrompt(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(START_PROMPT_ID)
    }

    const val ALERT_CHANNEL_ID = "battery_alerts"

    /** Sound and vibration start on and belong to the user in the channel's system settings (settings v3). */
    fun ensureAlertChannel(ctx: Context) {
        val manager = ctx.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(ALERT_CHANNEL_ID) != null) return
        manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL_ID,
            ctx.getString(R.string.alert_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = ctx.getString(R.string.alert_channel_description)
            enableVibration(true)
            setShowBadge(false)
        })
    }

    fun canPostAlerts(ctx: Context): Boolean = NotificationManagerCompat.from(ctx).areNotificationsEnabled() &&
        ctx.getSystemService(NotificationManager::class.java).getNotificationChannel(ALERT_CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE

    // Episode latches prevent duplicate posts; a new episode must alert even with the same ID.
    internal fun alertOnlyAlertOnce(): Boolean = false

    /**
     * One threshold alert. [sample] carries the calibrated current (the service copies it in), so the discharge
     * value matches the realtime readings; it is shown as a positive drain. Tapping opens Now.
     */
    fun batteryAlert(ctx: Context, type: BatteryAlert, sample: BatterySample, settings: AppSettings) {
        val locale = ctx.resources.configuration.locales[0] ?: Locale.getDefault()
        val (title, text) = when (type) {
            BatteryAlert.LOW -> R.string.low_battery_alert to ctx.getString(R.string.alert_level_reported, sample.levelPercent)
            BatteryAlert.HIGH -> R.string.high_battery_alert to ctx.getString(R.string.alert_level_reported, sample.levelPercent)
            BatteryAlert.FULL -> R.string.charging_complete_alert to ctx.getString(R.string.alert_full_reported)
            BatteryAlert.TEMPERATURE -> R.string.high_temperature to ctx.getString(R.string.alert_temperature_reported,
                formatTemperature(sample.temperatureDeciC?.div(10.0), settings.useFahrenheit, locale))
            BatteryAlert.DISCHARGE -> R.string.high_discharge to ctx.getString(R.string.alert_discharge_reported,
                formatDrainRate(sample.currentNowUa?.let { -it / 1_000.0 }, locale))
        }
        val content = PendingIntent.getActivity(ctx, OPEN_APP_REQUEST_CODE, Intent(ctx, BatteryMainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(Destinations.EXTRA_DESTINATION, Destinations.NOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(ctx, ALERT_CHANNEL_ID)
            .setContentTitle(ctx.getString(title)).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_battery).setContentIntent(content)
            .setAutoCancel(true).setOnlyAlertOnce(alertOnlyAlertOnce()).setWhen(sample.timestamp)
            .setCategory(NotificationCompat.CATEGORY_STATUS).build()
        // One stable ID per condition; successive observations do not create new notifications.
        ctx.getSystemService(NotificationManager::class.java).notify(1100 + type.ordinal, notification)
    }
}
