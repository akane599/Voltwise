package com.akane.voltwise.battery.insights

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.akane.voltwise.R
import com.akane.voltwise.battery.BatteryMainActivity
import com.akane.voltwise.battery.data.sampling.KeyValueStore
import com.akane.voltwise.battery.diagnostics.DiagnosticCode
import com.akane.voltwise.battery.insights.model.Confidence
import com.akane.voltwise.battery.insights.model.Finding
import com.akane.voltwise.battery.insights.model.InsightReport
import com.akane.voltwise.battery.insights.model.Severity
import com.akane.voltwise.ui.navigation.Destinations
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * [InsightRepository.report] carries only ACTIVE findings, so dismissed or not-a-problem keys never
 * reach [select]. Cooldown and the notified keys still in the report persist in [store].
 * The posted key is tracked separately until its notification is replaced or cancelled.
 */
class InsightNotificationPolicy(private val store: KeyValueStore, private val nowMs: () -> Long) {
    fun select(report: InsightReport): Finding? {
        val findings = listOfNotNull(report.headline) + report.findings
        val savedKeys = notifiedKeys()
        // Prune keys absent from the report even during cooldown; reports can exceed 12 findings.
        val notifiedKeys = savedKeys.intersect(findings.map { it.key }.toSet())
        if (notifiedKeys != savedKeys || store.getString(LAST_KEY) != null) {
            store.edit(mapOf(NOTIFIED_KEYS to Json.encodeToString(notifiedKeys), LAST_KEY to null))
        }
        val lastAt = store.getString(LAST_AT)?.toLongOrNull()
        val now = nowMs()
        // A clock set back must not silence notifications for longer than one cooldown.
        if (lastAt != null && now - lastAt in 0 until COOLDOWN_MS) return null
        return findings.firstOrNull {
            it.severity == Severity.HIGH && it.confidence >= Confidence.MEDIUM && it.key !in notifiedKeys
        }
    }

    fun shouldCancel(report: InsightReport): Boolean {
        val postedKey = store.getString(POSTED_KEY) ?: return false
        return report.headline?.key != postedKey && report.findings.none { it.key == postedKey }
    }

    fun markCancelled() {
        store.edit(mapOf(POSTED_KEY to null))
    }

    fun markNotified(finding: Finding) {
        store.edit(
            mapOf(
                LAST_AT to nowMs().toString(),
                POSTED_KEY to finding.key,
                NOTIFIED_KEYS to Json.encodeToString(notifiedKeys() + finding.key),
                LAST_KEY to null,
            ),
        )
    }

    private fun notifiedKeys(): Set<String> =
        store.getString(NOTIFIED_KEYS)?.let { Json.decodeFromString<Set<String>>(it) }.orEmpty() +
            listOfNotNull(store.getString(LAST_KEY))

    companion object {
        const val COOLDOWN_MS = 24L * 60 * 60 * 1000
        const val LAST_AT = "notify_last_at"
        const val LAST_KEY = "notify_last_key"
        const val POSTED_KEY = "notify_posted_key"
        const val NOTIFIED_KEYS = "notify_keys"
    }
}

/** One low-importance notification per day for a high-severity finding; failures never escape. */
class InsightNotifier(
    private val context: Context,
    private val policy: InsightNotificationPolicy,
    private val onFailure: (DiagnosticCode) -> Unit = {},
) {
    fun maybeNotify(report: InsightReport) {
        try {
            if (policy.shouldCancel(report)) {
                context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                policy.markCancelled()
            }
            val finding = policy.select(report) ?: return
            if (!canPost()) return
            ensureChannel()
            val content = PendingIntent.getActivity(
                context, REQUEST_CODE,
                Intent(context, BatteryMainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(Destinations.EXTRA_DESTINATION, Destinations.INSIGHTS),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val text = context.getString(R.string.insights_notification_text)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.insights_notification_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_stat_battery)
                .setContentIntent(content)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
            policy.markNotified(finding)
        } catch (_: Exception) {
            onFailure(DiagnosticCode.NOTIFICATION_FAILED)
        }
    }

    private fun canPost(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)?.importance !=
        NotificationManager.IMPORTANCE_NONE

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.insights_channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = context.getString(R.string.insights_channel_description)
                    setShowBadge(false)
                },
        )
    }

    companion object {
        const val CHANNEL_ID = "insights"
        private const val NOTIFICATION_ID = 1200
        internal const val REQUEST_CODE = 23
    }
}
