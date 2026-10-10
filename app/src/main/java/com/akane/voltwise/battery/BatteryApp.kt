package com.akane.voltwise.battery

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import com.akane.voltwise.battery.apps.AppInfoRepository
import com.akane.voltwise.battery.apps.SessionSnapshotCollector
import com.akane.voltwise.battery.data.BatteryRepository
import com.akane.voltwise.battery.data.db.BatteryDatabase
import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.diagnostics.DiagnosticCode
import com.akane.voltwise.battery.diagnostics.DiagnosticStore
import com.akane.voltwise.battery.insights.InsightNotifier
import com.akane.voltwise.battery.insights.InsightRepository
import com.akane.voltwise.battery.insights.model.InsightReport
import com.akane.voltwise.battery.insights.actions.InsightActionRepository
import com.akane.voltwise.battery.service.refreshOnFinalizedSessions
import com.akane.voltwise.battery.shizuku.ShizukuBridge
import com.akane.voltwise.di.appModule
import com.akane.voltwise.settings.AppSettings
import com.akane.voltwise.settings.SettingsMigrator
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.startKoin

class BatteryApp : Application() {

    private val appScope: CoroutineScope by inject()
    private val settingsMigrator: SettingsMigrator by inject()
    private val shizukuBridge: ShizukuBridge by inject()
    private val appInfo: AppInfoRepository by inject()
    private val repository: BatteryRepository by inject()
    private val sessionSnapshots: SessionSnapshotCollector by inject()
    internal val insightRefreshReady = CompletableDeferred<Unit>()
    private val insightActions: InsightActionRepository by inject()
    private val insights: InsightRepository by inject()
    private val insightNotifier: InsightNotifier by inject()
    private val diagnostics: DiagnosticStore by inject()

    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidContext(this@BatteryApp)
            modules(appModule)
        }

        shizukuBridge.warmUp()

        // App icons are the only sizeable cache: dropped on trim, and re-rendered after a density change.
        // The repository is resolved lazily here, so an app that never showed an icon creates it on the first trim.
        registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) = appInfo.onTrimMemory()
            override fun onConfigurationChanged(newConfig: Configuration) = appInfo.onConfigurationChanged(newConfig)
            @Deprecated("Superseded by onTrimMemory")
            override fun onLowMemory() = appInfo.onTrimMemory()
        })

        // Initialization stays off main; monitoring awaits registration without blocking onCreate.
        appScope.launch(Dispatchers.IO) {
            startInsightSessionRefresh(
                ready = insightRefreshReady,
                finalizedSessions = { sessionSnapshots.finalizedSessions },
                closedSessions = { repository.closedSessions },
                record = diagnostics::record,
                refresh = { insights.refresh() },
            )
        }

        // Settings migration; history retention cleanup waits until it has ended.
        appScope.launch {
            settingsMigrator.run()
        }

        appScope.launch(Dispatchers.IO) {
            startInsightNotifications(
                awaitMigrated = { settingsMigrator.awaitMigrated() },
                catchUp = {
                    reconcileAndCatchUpInsights(
                        reconcile = { insightActions.reconcile() },
                        lastAnalyzedAt = { insights.awaitLastAnalyzedAt() },
                        refresh = { insights.refresh() },
                    )
                },
                reports = { insights.report },
                maybeNotify = { insightNotifier.maybeNotify(it) },
                onFailure = diagnostics::record,
            )
        }

        // History › Days for upgraders with monitoring off: the one-time backfill also runs at app start (the
        // repository is created here, off the main thread).
        appScope.launch(Dispatchers.IO) {
            repository.backfillDailySummariesOnce()
        }
    }
}

// Providers retain off-main initialization, and readiness failures unblock monitoring with the cause.
internal suspend fun startInsightSessionRefresh(
    ready: CompletableDeferred<Unit>,
    finalizedSessions: () -> Flow<String>,
    closedSessions: () -> Flow<ChargeSession>,
    record: (DiagnosticCode) -> Unit,
    refresh: suspend () -> Unit,
) {
    try {
        refreshOnFinalizedSessions(finalizedSessions(), record, closedSessions(),
            onSubscribed = { ready.complete(Unit) }, refresh = refresh)
    } catch (e: Exception) {
        ready.completeExceptionally(e)
        throw e
    }
}

// Providers keep Android-backed dependencies unresolved until startup reaches them off the main thread.
internal suspend fun startInsightNotifications(
    awaitMigrated: suspend () -> Unit,
    catchUp: suspend () -> Unit,
    reports: () -> Flow<InsightReport?>,
    maybeNotify: (InsightReport) -> Unit,
    onFailure: (DiagnosticCode) -> Unit,
) {
    awaitMigrated()
    try {
        catchUp()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        onFailure(DiagnosticCode.APP_SCOPE_FAILED)
    }
    reports().filterNotNull().collect(maybeNotify)
}

internal suspend fun reconcileAndCatchUpInsights(
    reconcile: suspend () -> Unit,
    lastAnalyzedAt: suspend () -> Long?,
    refresh: suspend () -> Unit,
    clock: () -> Long = System::currentTimeMillis,
) {
    reconcile()
    val last = lastAnalyzedAt()
    val now = clock()
    // A corrected wall clock can put the saved analysis in the future; catch up rather than wait for it.
    if (last == null || last > now || now - last > 6 * 60 * 60 * 1_000L) refresh()
}

/**
 * Legacy accessor for components. Prefer using Koin injection directly.
 */
object BatteryGraph : KoinComponent {
    val db: BatteryDatabase by inject()
    val repo: BatteryRepository by inject()
    val settings: SettingsRepository<AppSettings> by inject()
}