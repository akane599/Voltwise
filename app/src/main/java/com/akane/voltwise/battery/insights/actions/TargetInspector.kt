package com.akane.voltwise.battery.insights.actions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager

interface TargetInspector {
    fun installedUid(pkg: String, userId: Int = 0): Int?
    fun packagesForUid(uid: Int): List<String>
    fun roleHolders(): Set<String>
    val sdkInt: Int
}

/** Uses only the calling user's package view; never mistakes a work-profile install for user 0. */
class PackageManagerTargetInspector(private val context: Context) : TargetInspector {
    override val sdkInt: Int get() = Build.VERSION.SDK_INT

    @Suppress("DEPRECATION")
    override fun installedUid(pkg: String, userId: Int): Int? {
        if (Process.myUid() / 100_000 != userId) return null
        return try {
            context.packageManager.getApplicationInfo(pkg, 0).uid.takeIf { it / 100_000 == userId }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    override fun packagesForUid(uid: Int): List<String> =
        context.packageManager.getPackagesForUid(uid)?.toList().orEmpty()

    /**
     * RoleManager enumeration is system-only on compileSdk 37. Public default-app lookups are
     * independent and best-effort; CommandPolicy remains the hard protected-package floor.
     * whittle: assistant/browser/other roles are not detected; upgrade when a public holder API exists.
     */
    @Suppress("DEPRECATION")
    override fun roleHolders(): Set<String> = buildSet {
        runCatching {
            context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName?.takeUnless { it == "android" }
        }.getOrNull()?.let(::add)
        runCatching {
            context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        }.getOrNull()?.let(::add)
        runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()?.let(::add)
        runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.let(ComponentName::unflattenFromString)?.packageName
        }.getOrNull()?.let(::add)
    }
}
