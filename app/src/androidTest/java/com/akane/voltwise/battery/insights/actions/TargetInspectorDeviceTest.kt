package com.akane.voltwise.battery.insights.actions

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akane.voltwise.battery.actions.BackgroundOp
import com.akane.voltwise.battery.actions.CommandPolicy
import com.akane.voltwise.battery.actions.PrivilegedCommand
import com.akane.voltwise.battery.data.db.BatteryDatabase
import com.akane.voltwise.battery.insights.model.ActionType
import com.akane.voltwise.battery.insights.model.Confidence
import com.akane.voltwise.battery.insights.model.Finding
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.Recommendation
import com.akane.voltwise.battery.insights.model.Severity
import com.akane.voltwise.battery.insights.model.Subject
import com.akane.voltwise.battery.util.ShellRunner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real PackageManager inputs through apply's identity checks; never executes a shell command. */
@RunWith(AndroidJUnit4::class)
class TargetInspectorDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val inspector = PackageManagerTargetInspector(context)
    private val db = Room.inMemoryDatabaseBuilder(context, BatteryDatabase::class.java).build()
    private val commands = mutableListOf<PrivilegedCommand>()
    private var allowedRead: PrivilegedCommand.GetBackgroundOp? = null
    private val repository = InsightActionRepository(
        dao = db.insightDao(),
        executor = ActionExecutor { command ->
            // Refusal cases allow nothing. Only the positive control may request one fake read.
            assertEquals("No mutation or unexpected command may reach the executor", allowedRead, command)
            commands += command
            ShellRunner.Outcome.Success("${allowedRead!!.op.name}: ignore", ShellRunner.Mode.NONE)
        },
        inspector = inspector,
        clock = { 1L },
        alertEnabler = { throw AssertionError("No alert setting may change") },
        alertsPostable = { true },
    )

    @After fun close() = db.close()

    @Suppress("DEPRECATION")
    @Test fun defaultHomeIsEnumeratedAndRefusedBeforeAnyCommand() = runBlocking {
        val pkg = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName?.takeUnless { it == "android" }
        assumeTrue("Device has no default home holder", pkg != null)
        assertRoleRefusal(pkg!!)
    }

    @Test fun defaultImeIsEnumeratedAndRefusedBeforeAnyCommand() = runBlocking {
        val pkg = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.let(ComponentName::unflattenFromString)?.packageName
        assumeTrue("Device has no default input method", pkg != null)
        assertRoleRefusal(pkg!!)
    }

    @Test fun systemSharedUidIsEnumeratedButProtectedFloorWins() = runBlocking {
        requireOwnerUser()
        val packages = inspector.packagesForUid(Process.SYSTEM_UID)
        val pkg = packages.firstOrNull { CommandPolicy.isPackageName(it) }
        assumeTrue("No resolvable shared system-UID package on this device", packages.size > 1 && pkg != null)
        assertEquals(Process.SYSTEM_UID, inspector.installedUid(pkg!!))
        assertTrue("System package must be among the shared UID's packages", pkg in packages)
        assertRefused(pkg, Process.SYSTEM_UID, RefusalCode.PROTECTED)
    }

    @Suppress("DEPRECATION")
    @Test fun unprotectedSharedSystemUidIsRefusedAsSharedUidWhenAvailable() = runBlocking {
        requireOwnerUser()
        val app = context.packageManager.getInstalledApplications(0).firstOrNull {
            it.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 &&
                !CommandPolicy.isProtected(it.packageName, it.uid) &&
                context.packageManager.getPackagesForUid(it.uid).orEmpty().size > 1
        }
        assumeTrue("Device has no unprotected shared-UID system package", app != null)
        val pkg = app!!.packageName
        assertEquals(app.uid, inspector.installedUid(pkg))
        val packages = inspector.packagesForUid(app.uid)
        assertTrue("Real inspector must enumerate multiple packages for this UID", packages.size > 1)
        assertTrue(pkg in packages)
        assertRefused(pkg, app.uid, RefusalCode.SHARED_UID)
    }

    @Test fun syntheticOtherUserIdentityIsProtectedAndCannotResolveAnInstall() = runBlocking {
        val pkg = instrumentation.context.packageName
        val currentUid = instrumentation.context.applicationInfo.uid
        val otherUserId = currentUid / 100_000 + 1
        val otherUid = currentUid % 100_000 + 100_000 * otherUserId
        assertEquals(currentUid, inspector.installedUid(pkg, currentUid / 100_000))
        assertNull("Calling user's package view must not stand in for another user's install",
            inspector.installedUid(pkg, otherUserId))
        assertRefused(pkg, otherUid, RefusalCode.PROTECTED)
    }

    @Test fun testApkPassesIdentityChecksAndOnlyRequestsTheFakeRead() = runBlocking {
        requireOwnerUser()
        // targetContext is Voltwise itself and is deliberately protected; context is the test APK.
        val pkg = instrumentation.context.packageName
        val uid = instrumentation.context.applicationInfo.uid
        assertFalse("Positive control must not be a protected package", CommandPolicy.isProtected(pkg, uid))
        assertEquals(uid, inspector.installedUid(pkg))
        assertEquals(listOf(pkg), inspector.packagesForUid(uid))
        assertFalse("Test APK must not be a default role holder", pkg in inspector.roleHolders())
        val read = PrivilegedCommand.GetBackgroundOp(pkg, BackgroundOp.forSdk(inspector.sdkInt))
        allowedRead = read
        assertEquals("Identity validation must reach the safe, already-restricted fake read",
            ActionResult.Refused(RefusalCode.ALREADY_AT_TARGET), apply(pkg, uid, ActionType.RESTRICT_BACKGROUND))
        assertEquals(listOf(read), commands)
        assertTrue("Positive control must not create a journal row", db.insightDao().actionsOnce().isEmpty())
    }

    @Suppress("DEPRECATION")
    private suspend fun assertRoleRefusal(pkg: String) {
        assertTrue("Real inspector must enumerate the default holder $pkg", pkg in inspector.roleHolders())
        val uid = context.packageManager.getApplicationInfo(pkg, 0).uid
        // CommandPolicy's protected floor and shared identity check precede role membership.
        val expected = when {
            CommandPolicy.isProtected(pkg, uid) -> RefusalCode.PROTECTED
            inspector.packagesForUid(uid) != listOf(pkg) -> RefusalCode.SHARED_UID
            else -> RefusalCode.ROLE_HOLDER
        }
        requireOwnerUser()
        assertEquals(uid, inspector.installedUid(pkg))
        assertRefused(pkg, uid, expected)
    }

    private suspend fun assertRefused(pkg: String, uid: Int, reason: RefusalCode) {
        for (action in listOf(ActionType.FORCE_STOP, ActionType.RESTRICT_BACKGROUND)) {
            assertEquals("$action must refuse $pkg before any command", ActionResult.Refused(reason), apply(pkg, uid, action))
        }
        assertTrue("Unsafe identities must never reach the executor", commands.isEmpty())
        assertTrue("Unsafe identities must never create journal rows", db.insightDao().actionsOnce().isEmpty())
    }

    private suspend fun apply(pkg: String, uid: Int, action: ActionType): ActionResult = repository.apply(
        Finding(
            "device-test:$pkg", FindingType.BACKGROUND_RUNAWAY, Severity.HIGH, Confidence.HIGH, 1.0,
            Subject.App(uid, pkg), null, emptyList(), emptyList(), emptyList(),
        ),
        Recommendation(action, action.reversible, action.requiresPrivilege),
    )

    private fun requireOwnerUser() {
        assumeTrue("Privileged action targets are owner-user only", Process.myUid() / 100_000 == 0)
    }
}
