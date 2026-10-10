package com.akane.voltwise.di

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.akane.voltwise.battery.insights.InsightNotificationPolicy
import com.akane.voltwise.battery.insights.InsightNotifier
import com.akane.voltwise.battery.util.Notifier
import com.akane.voltwise.battery.insights.InsightRepository
import com.akane.voltwise.battery.insights.readUserDozeWhitelist
import com.akane.voltwise.battery.insights.actions.ActionExecutor
import com.akane.voltwise.battery.insights.actions.InsightActionRepository
import com.akane.voltwise.battery.insights.actions.PackageManagerTargetInspector
import com.akane.voltwise.battery.insights.actions.ShellRunnerActionExecutor
import com.akane.voltwise.battery.insights.actions.TargetInspector
import com.akane.voltwise.viewmodel.AppDetailsRepository
import com.akane.voltwise.viewmodel.DefaultInsightsRepository
import com.akane.voltwise.viewmodel.FindingDetailsViewModel
import com.akane.voltwise.viewmodel.InsightsRepository
import com.akane.voltwise.viewmodel.InsightsViewModel
import com.akane.voltwise.viewmodel.NowRepository
import java.time.Clock
import java.time.ZoneId
import com.akane.voltwise.battery.apps.AppInfoRepository
import com.akane.voltwise.battery.apps.AppInfoSource
import com.akane.voltwise.battery.apps.AppStatsRepository
import com.akane.voltwise.battery.apps.AppStatsSource
import com.akane.voltwise.battery.apps.RoomSessionSnapshotStore
import com.akane.voltwise.battery.apps.SessionSnapshotCollector
import com.akane.voltwise.battery.apps.SessionSnapshotStore
import com.akane.voltwise.battery.apps.ShellRunnerStatsShell
import com.akane.voltwise.battery.data.DesignCapacitySource
import com.akane.voltwise.battery.diagnostics.DiagnosticCode
import com.akane.voltwise.battery.diagnostics.DiagnosticStore
import com.akane.voltwise.battery.data.BatteryRepository
import com.akane.voltwise.battery.data.CalibrationOverrides
import com.akane.voltwise.battery.data.CalibrationStore
import com.akane.voltwise.battery.data.ExportImportManager
import com.akane.voltwise.battery.data.HistoryMaintenance
import com.akane.voltwise.battery.data.HistoryRetention
import com.akane.voltwise.battery.data.db.BatteryDatabase
import com.akane.voltwise.battery.data.sampling.SamplerState
import com.akane.voltwise.battery.data.sampling.SamplingController
import com.akane.voltwise.battery.data.sampling.SharedPreferencesStore
import com.akane.voltwise.battery.drain.DrainNotificationManager
import com.akane.voltwise.battery.service.MonitoringControl
import com.akane.voltwise.battery.service.MonitoringController
import com.akane.voltwise.battery.service.SamplingDemand
import com.akane.voltwise.battery.shizuku.ShizukuBridge
import com.akane.voltwise.battery.util.ShellRunner
import com.akane.voltwise.settings.AppSettings
import com.akane.voltwise.settings.AppSettingsSchema
import com.akane.voltwise.settings.SettingsMigrations
import com.akane.voltwise.settings.SettingsMigrator
import com.akane.voltwise.settings.createSettingsDataStore
import com.akane.voltwise.settings.withDefaultsOnReadFailure
import com.akane.voltwise.viewmodel.AppDetailsViewModel
import com.akane.voltwise.viewmodel.AppsViewModel
import com.akane.voltwise.viewmodel.DataViewModel
import com.akane.voltwise.viewmodel.DefaultAppDetailsRepository
import com.akane.voltwise.viewmodel.DefaultAppsRepository
import com.akane.voltwise.viewmodel.DefaultDataRepository
import com.akane.voltwise.viewmodel.DefaultHealthRepository
import com.akane.voltwise.viewmodel.DefaultHistoryRepository
import com.akane.voltwise.viewmodel.DefaultNowRepository
import com.akane.voltwise.viewmodel.DefaultSessionDetailsRepository
import com.akane.voltwise.viewmodel.DefaultStatusRepository
import com.akane.voltwise.viewmodel.HealthViewModel
import com.akane.voltwise.viewmodel.HistoryViewModel
import com.akane.voltwise.viewmodel.KmpSettingsStore
import com.akane.voltwise.viewmodel.NowViewModel
import com.akane.voltwise.viewmodel.SessionDetailsViewModel
import com.akane.voltwise.viewmodel.SettingsViewModel
import com.akane.voltwise.viewmodel.StatusViewModel
import io.github.mlmgames.settings.core.SettingsRepository
import io.github.mlmgames.settings.core.backup.DeviceInfo
import io.github.mlmgames.settings.core.backup.SettingsBackupManager
import io.github.mlmgames.settings.core.managers.ResetManager
import io.github.mlmgames.settings.core.resources.AndroidStringResourceProvider
import io.github.mlmgames.settings.core.resources.StringResourceProvider
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

private const val SCHEMA_VERSION = SettingsMigrations.CURRENT_VERSION
private const val DATASTORE_NAME = "batstats_settings"
private const val RAW_SETTINGS_DATASTORE = "rawSettingsDataStore"

internal fun createAppScope(record: (DiagnosticCode) -> Unit): CoroutineScope = CoroutineScope(
    SupervisorJob() + CoroutineExceptionHandler { _, _ -> record(DiagnosticCode.APP_SCOPE_FAILED) },
)

val appModule = module {
    // Resolve diagnostics only on failure: DiagnosticStore itself depends on this scope.
    single { createAppScope { code -> get<DiagnosticStore>().record(code) } }
    single { BatteryDatabase.get(androidContext()) }
    single<DataStore<Preferences>>(named(RAW_SETTINGS_DATASTORE)) {
        createSettingsDataStore(androidContext().preferencesDataStoreFile(DATASTORE_NAME))
    }
    // One underlying store; only normal settings reads substitute defaults on IOException.
    single<DataStore<Preferences>> { get<DataStore<Preferences>>(named(RAW_SETTINGS_DATASTORE)).withDefaultsOnReadFailure() }

    single { ShizukuBridge(androidContext()) }
    single { ShellRunner(androidContext(), get()) }
    single { DiagnosticStore(androidContext(), get()) }
    // The one batterystats reader (on demand only; concurrent callers share a dump) and installed-app info.
    single { AppStatsRepository(ShellRunnerStatsShell(get()), get(), get<DiagnosticStore>()::record) } bind AppStatsSource::class
    single { AppInfoRepository(androidContext()) } bind AppInfoSource::class
    single<SessionSnapshotStore> { RoomSessionSnapshotStore(get()) }
    // Started and stopped by BatteryMonitorService.
    single {
        SessionSnapshotCollector(
            get(), get(), get<BatteryRepository>().powerTransitions, onDiagnostic = get<DiagnosticStore>()::record,
        )
    }

    single<SettingsRepository<AppSettings>> {
        SettingsRepository(dataStore = get(), schema = AppSettingsSchema)
    }

    single<StringResourceProvider> { AndroidStringResourceProvider(androidContext()) }
    single { ResetManager(get(), AppSettingsSchema) }
    // BatteryApp runs it at start; history retention waits for it (HistoryRetention).
    single { SettingsMigrator(get()) }

    single {
        val app = androidApplication()
        val appVersion = try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "1.0.0"
        } catch (e: Exception) { "1.0.0" }

        SettingsBackupManager(
            dataStore = get(named(RAW_SETTINGS_DATASTORE)),
            schema = AppSettingsSchema,
            appId = "app.batstats",
            schemaVersion = SCHEMA_VERSION,
            deviceInfoProvider = { DeviceInfo("Android", Build.VERSION.RELEASE, appVersion) }
        )
    }

    // One design capacity for Now's Health card and the Health screen: sysfs is read once (root), then cached.
    single {
        DesignCapacitySource(get<SettingsRepository<AppSettings>>().flow, DesignCapacitySource::readRootChargeFullDesignUah, get())
    }

    single { HistoryMaintenance() }
    single {
        val context = androidContext()
        val preferences = context.getSharedPreferences(SamplerState.PREFS_NAME, Context.MODE_PRIVATE)
        HistoryRetention(get(), get(named(RAW_SETTINGS_DATASTORE)), SamplerState(SharedPreferencesStore(preferences))) {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        }
    }
    single { ExportImportManager(androidContext(), get(), get()) }
    // One sampler thread per process; screens, the tile and details hold it as SamplingDemand.
    single { SamplingController(androidContext(), get()) } bind SamplingDemand::class
    single {
        val preferences = androidContext().getSharedPreferences(CalibrationStore.PREFS_NAME, Context.MODE_PRIVATE)
        val overrides = get<SettingsRepository<AppSettings>>().flow.map { settings ->
            CalibrationOverrides(settings.currentUnitOverride.unit, settings.currentSignOverride.sign)
        }
        CalibrationStore(SharedPreferencesStore(preferences), overrides, get())
    }
    single {
        val samplerPreferences = androidContext().getSharedPreferences(SamplerState.PREFS_NAME, Context.MODE_PRIVATE)
        BatteryRepository(get(), get(), get(), get(), get(), get(), get(), get(), SharedPreferencesStore(samplerPreferences))
    }
    single<MonitoringControl> { MonitoringController(androidContext(), get()) }

    single { DrainNotificationManager(androidContext(), get()) }

    single { get<BatteryDatabase>().insightDao() }
    single { ShellRunnerActionExecutor(get()) } bind ActionExecutor::class
    single { PackageManagerTargetInspector(androidContext()) } bind TargetInspector::class
    single {
        InsightActionRepository(
            get(), get(), get(), System::currentTimeMillis,
            alertEnabler = { get<SettingsRepository<AppSettings>>().set(AppSettings::highBatteryAlertEnabled.name, true) },
            alertsPostable = { Notifier.canPostAlerts(androidContext()) },
        )
    }
    single {
        val database = get<BatteryDatabase>()
        val shell = get<ShellRunner>()
        val battery = get<BatteryRepository>()
        val preferences = androidContext().getSharedPreferences("insights", Context.MODE_PRIVATE)
        InsightRepository(
            database.sessionDao(), database.dailySummaryDao(), database.appUsageDao(), get(),
            get(), Clock.systemDefaultZone(),
            currentZone = ZoneId::systemDefault,
            dozeWhitelist = { readUserDozeWhitelist(shell) },
            store = SharedPreferencesStore(preferences),
            maintenance = get(),
            capacityReading = {
                val sample = battery.realtimeFlow.value.sample
                sample?.chargeCounterUah to sample?.levelPercent
            },
            highBatteryAlertEnabled = { get<SettingsRepository<AppSettings>>().flow.first().highBatteryAlertEnabled },
        )
    }
    single {
        val preferences = androidContext().getSharedPreferences("insights", Context.MODE_PRIVATE)
        val diagnostics = get<DiagnosticStore>()
        InsightNotifier(
            androidContext(),
            InsightNotificationPolicy(SharedPreferencesStore(preferences), System::currentTimeMillis),
            onFailure = diagnostics::record,
        )
    }
    single<InsightsRepository> { DefaultInsightsRepository(get(), get(), get(), get<BatteryDatabase>().sessionDao()) }
    single<NowRepository> {
        val insights = get<InsightRepository>()
        DefaultNowRepository(
            get(), get(), get(), get(), get(), get(), get(), insights.report, insights.lastAnalyzedAt,
            get<InsightsRepository>().eligibleSessionCount,
        )
    }
    single<AppDetailsRepository> {
        val insights = get<InsightRepository>().report
        DefaultAppDetailsRepository(DefaultAppsRepository(androidContext(), get(), get(), get(), get()), get(), insights)
    }

    single { com.akane.voltwise.viewmodel.InsightApplyResults() }
    viewModel { InsightsViewModel(get(), get(), get(), get()) }
    viewModel { FindingDetailsViewModel(get(), get(), get(), get()) }
    viewModel {
        NowViewModel(get(), get(), get(), savedStateHandle = get())
    }
    viewModel { SettingsViewModel(KmpSettingsStore(get()), get()) }
    // The second get() is the nav entry's SavedStateHandle (mode, range, chip and selected day survive process death).
    viewModel { HistoryViewModel(DefaultHistoryRepository(get(), get()), get()) }
    viewModel { DataViewModel(DefaultDataRepository(androidContext(), get(), get(), get(), get()), get()) }
    viewModel { StatusViewModel(DefaultStatusRepository(androidContext(), get(), get(), get(), get(), get(), get())) }
    viewModel { HealthViewModel(DefaultHealthRepository(androidContext(), get(), get(), KmpSettingsStore(get()))) }
    // Apps' second get() is the nav entry's SavedStateHandle (sort, query and "show system" survive process death).
    viewModel { AppsViewModel(DefaultAppsRepository(androidContext(), get(), get(), get(), get()), get()) }
    viewModel { (uid: Int, packageName: String) ->
        AppDetailsViewModel(get(), uid, packageName)
    }

    viewModel { (sessionId: String) ->
        SessionDetailsViewModel(DefaultSessionDetailsRepository(get(), get(), get(), get(), get(), get(), get()), get(), sessionId)
    }
}
