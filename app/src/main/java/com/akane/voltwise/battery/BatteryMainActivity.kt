package com.akane.voltwise.battery

import android.Manifest
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.DEFAULT_ARGS_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import com.akane.voltwise.settings.AppSettings
import com.akane.voltwise.ui.navigation.Destinations
import com.akane.voltwise.ui.navigation.mainActivityLaunchFlags
import com.akane.voltwise.ui.screens.MainScreen
import com.akane.voltwise.ui.theme.MainTheme
import io.github.mlmgames.settings.core.SettingsRepository
import org.koin.compose.koinInject

internal fun mainActivityIntent(context: Context, destination: String? = null): Intent =
    Intent(context, BatteryMainActivity::class.java)
        .addFlags(mainActivityLaunchFlags())
        .apply {
            if (destination != null) putExtra(Destinations.EXTRA_DESTINATION, destination)
        }

/** Where the app records that it has asked for POST_NOTIFICATIONS (here on first launch, or from Settings). */
internal const val NOTIFICATION_PERMISSION_PREFS = "notification_permission"
internal const val NOTIFICATION_PERMISSION_ASKED = "asked_once"

internal fun shouldRequestNotificationPermission(
    sdkInt: Int,
    granted: Boolean,
    restored: Boolean,
    askedBefore: Boolean,
    rationale: Boolean,
): Boolean = sdkInt >= 33 && !granted && !restored && !askedBefore && !rationale

/**
 * Theme settings before the settings store first emits. Null, so the activity renders no themed content and the
 * dark XML window background shows instead of default colours that would flash to an OLED or dynamic-colour
 * user's stored theme a few frames later.
 */
internal val themeSettingsBeforeFirstEmission: AppSettings? = null

class BatteryMainActivity : ComponentActivity() {
    // Launch intents select destinations, never ViewModel state. Keep owners so genuine saved state still restores.
    override val defaultViewModelCreationExtras: CreationExtras
        get() = MutableCreationExtras(super.defaultViewModelCreationExtras).apply {
            set(DEFAULT_ARGS_KEY, Bundle())
        }

    private val destination = MutableStateFlow<String?>(null)
    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark-only app: light bar icons over transparent bars on every API level.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        destination.value = Destinations.initialDestination(
            extra = intent.getStringExtra(Destinations.EXTRA_DESTINATION),
            restored = savedInstanceState != null,
            launchedFromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0,
        )

        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            val permissionPreferences = getSharedPreferences(NOTIFICATION_PERMISSION_PREFS, MODE_PRIVATE)
            if (shouldRequestNotificationPermission(
                    sdkInt = Build.VERSION.SDK_INT,
                    granted = granted,
                    restored = savedInstanceState != null,
                    askedBefore = permissionPreferences.getBoolean(NOTIFICATION_PERMISSION_ASKED, false),
                    rationale = shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS),
                )
            ) {
                // Record before launch so recreation while the dialog is open cannot request again.
                permissionPreferences.edit().putBoolean(NOTIFICATION_PERMISSION_ASKED, true).apply()
                notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            val settingsRepository: SettingsRepository<AppSettings> = koinInject()
            val settings by settingsRepository.flow.collectAsStateWithLifecycle(
                initialValue = themeSettingsBeforeFirstEmission,
            )
            val loaded = settings ?: return@setContent
            MainTheme(oled = loaded.oledBlack, dynamicColor = loaded.dynamicColors) {
                val requested by destination.collectAsStateWithLifecycle()
                MainScreen(destination = requested, onDestinationHandled = {
                    destination.value = null
                    intent.removeExtra(Destinations.EXTRA_DESTINATION)
                })
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        destination.value = intent.getStringExtra(Destinations.EXTRA_DESTINATION)
    }
}