package com.akane.voltwise.battery.insights.actions

import com.akane.voltwise.battery.actions.*
import com.akane.voltwise.battery.data.db.*
import com.akane.voltwise.battery.data.db.InsightActionStatus.*
import com.akane.voltwise.battery.insights.UnusedInsightDao
import com.akane.voltwise.battery.insights.model.*
import com.akane.voltwise.battery.util.ExecutionCertainty
import com.akane.voltwise.battery.util.ShellRunner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InsightActionRepositoryTest {
    private val pkg = "com.example.drainer"
    private val uid = 10123
    private fun finding(subject: Subject = Subject.App(uid, pkg)) = Finding(
        "finding", FindingType.BACKGROUND_RUNAWAY, Severity.HIGH, Confidence.HIGH, 1.0,
        subject, null, emptyList(), emptyList(), emptyList(),
    )
    private fun rec(type: ActionType) = Recommendation(type, true, true)
    private fun ok(output: String = "") = ShellRunner.Outcome.Success(output, ShellRunner.Mode.ROOT)
    private fun denied() = ShellRunner.Outcome.NoAccess(ShellRunner.Mode.NONE, "sensitive diagnostic")
    private fun failure() = ShellRunner.Outcome.Failure(ShellRunner.Mode.ROOT, "sensitive diagnostic")
    private fun uncertainFailure() = ShellRunner.Outcome.Failure(ShellRunner.Mode.ROOT, "response lost", ExecutionCertainty.UNKNOWN)

    private inner class Inspector : TargetInspector {
        var inspectionFails = false
        var installed: Int? = uid
        var packages = listOf(pkg)
        var roles = emptySet<String>()
        override var sdkInt = 35
        override fun installedUid(pkg: String, userId: Int): Int? { assertEquals(0, userId); if (inspectionFails) throw SecurityException(); return installed }
        override fun packagesForUid(uid: Int) = packages
        override fun roleHolders() = roles
    }
    private class Dao : UnusedInsightDao() {
        val rows = MutableStateFlow<List<InsightActionEntity>>(emptyList())
        var beforeInsert: suspend () -> Unit = {}
        var beforeUpdate: suspend () -> Unit = {}
        override fun actions() = rows
        override suspend fun actionsOnce() = rows.value
        override suspend fun actionsWithStatus(statuses: List<InsightActionStatus>) = rows.value.filter { it.status in statuses }
        override suspend fun insertAction(entity: InsightActionEntity): Long {
            beforeInsert()
            val id = (rows.value.maxOfOrNull { it.id } ?: 0) + 1
            rows.value += entity.copy(id = id)
            return id
        }
        override suspend fun updateAction(entity: InsightActionEntity) {
            beforeUpdate()
            check(rows.value.any { it.id == entity.id })
            rows.value = rows.value.map { if (it.id == entity.id) entity else it }
        }
    }
    private inner class Fixture {
        val dao = Dao()
        val inspector = Inspector()
        val commands = mutableListOf<PrivilegedCommand>()
        val replies = ArrayDeque<ShellRunner.Outcome>()
        var intercept: suspend (PrivilegedCommand) -> Unit = {}
        var now = 100L
        var alerts = 0
        val repo = InsightActionRepository(dao, ActionExecutor {
            commands += it
            intercept(it)
            replies.removeFirst()
        }, inspector, { now++ }, { alerts++ })
        fun reply(vararg output: String) { replies.addAll(output.map(::ok)) }
        fun row() = dao.rows.value.single()
        suspend fun apply(type: ActionType = ActionType.RESTRICT_BACKGROUND) = repo.apply(finding(), rec(type))
    }

    @Test fun applyStoresLeadMetricInPreparedAndAppliedJournal() = runTest {
        for (metric in listOf(Metric.JOBS_PER_H, null)) {
            val f = Fixture()
            val appliedFinding = finding().copy(
                type = FindingType.JOB_STORM,
                evidence = metric?.let { listOf(Evidence(it, 60.0, 1.0, it.unit, 5)) } ?: emptyList(),
            )
            f.reply("No operations.", "", "RUN_ANY_IN_BACKGROUND: ignore")
            f.intercept = {
                if (it is PrivilegedCommand.SetBackgroundOp) {
                    assertEquals(PREPARED, f.row().status)
                    assertEquals(metric?.name, f.row().metric)
                }
            }
            assertEquals(ActionResult.Applied(1), f.repo.apply(appliedFinding, rec(ActionType.RESTRICT_BACKGROUND)))
            assertEquals(APPLIED, f.row().status)
            assertEquals(metric?.name, f.row().metric)
        }
    }

    @Test fun backgroundRoundTripRestoresEffectiveAllowAndPublishesJournal() = runTest {
        val f = Fixture()
        f.reply("No operations.", "", "RUN_ANY_IN_BACKGROUND: ignore")
        f.intercept = { if (it is PrivilegedCommand.SetBackgroundOp) assertEquals(PREPARED, f.row().status) }
        assertEquals(ActionResult.Applied(1), f.apply())
        assertEquals(APPLIED, f.repo.actions.first().single().status)
        assertEquals("RUN_ANY_IN_BACKGROUND:ALLOW", f.row().priorState)
        assertEquals(1, f.row().priorStateVersion)
        f.intercept = {}
        f.reply("RUN_ANY_IN_BACKGROUND: ignore", "", "No operations.")
        assertEquals(ActionResult.Reverted, f.repo.undo(1))
        assertEquals(PrivilegedCommand.SetBackgroundOp(pkg, BackgroundOp.RUN_ANY_IN_BACKGROUND, AppOpMode.ALLOW), f.commands[4])
        assertEquals(REVERTED, f.row().status)
        assertNotNull(f.row().revertedAt)
    }

    @Test fun bucketsRoundTripAndRestrictedFallback() = runTest {
        for ((sdk, type, target) in listOf(
            Triple(35, ActionType.STANDBY_BUCKET_RESTRICTED, StandbyBucket.RESTRICTED),
            Triple(29, ActionType.STANDBY_BUCKET_RESTRICTED, StandbyBucket.RARE),
            Triple(28, ActionType.STANDBY_BUCKET_RARE, StandbyBucket.RARE),
        )) {
            val f = Fixture()
            f.inspector.sdkInt = sdk
            f.reply("20", "", target.code.toString())
            val result = f.apply(type)
            if (sdk == 29) assertEquals(ActionResult.AppliedWithFallback(1, FallbackCode.RESTRICTED_TO_RARE), result)
            else assertEquals(ActionResult.Applied(1), result)
            assertEquals(PrivilegedCommand.SetStandbyBucket(pkg, target), f.commands[1])
            f.reply(target.token, "", "working_set")
            assertEquals(ActionResult.Reverted, f.repo.undo(1))
            assertEquals(PrivilegedCommand.SetStandbyBucket(pkg, StandbyBucket.WORKING_SET), f.commands[4])
        }
    }

    @Test fun dozeUndoRestoresOnlyUserMembershipAndRequiresListConfirmation() = runTest {
        val f = Fixture()
        val system = "system,android,1000"
        f.reply("$system\nuser,$pkg,$uid", "Unknown package: $pkg", system)
        assertEquals(ActionResult.Applied(1), f.apply(ActionType.REMOVE_DOZE_WHITELIST))
        assertEquals("PRESENT", f.row().priorState)
        f.reply(system, "Added: $pkg", "$system\nuser,$pkg,$uid")
        assertEquals(ActionResult.Reverted, f.repo.undo(1))
        assertEquals(PrivilegedCommand.AddDozeWhitelist(pkg), f.commands[4])
        assertEquals(REVERTED, f.row().status)
        assertNotNull(f.row().revertedAt)
        assertNull(f.row().message)
    }

    @Test fun dozeUndoAfterUninstallOrReinstallSettlesAsChangedExternallyWithoutCommands() = runTest {
        for (installed in listOf(uid + 1, null)) {
            val f = Fixture()
            f.reply("system,android,1000\nuser,$pkg,$uid", "Removed: $pkg", "system,android,1000")
            assertEquals(ActionResult.Applied(1), f.apply(ActionType.REMOVE_DOZE_WHITELIST))
            assertEquals("PRESENT", f.row().priorState)
            assertEquals("ABSENT", f.row().targetState)
            f.inspector.installed = installed

            assertEquals("nothing was restored for installed uid $installed", ActionResult.ChangedExternally(null), f.repo.undo(1))
            assertEquals(REVERTED, f.row().status)
            assertNotNull(f.row().revertedAt)
            assertEquals("CHANGED_EXTERNALLY", f.row().message)
            assertEquals(3, f.commands.size)
            assertEquals(ActionResult.Failed(FailureCode.NOT_UNDOABLE), f.repo.undo(1))
            assertEquals(3, f.commands.size)
        }
    }

    @Test fun dozeReconcileAfterUninstallOrReinstallRecordsExternalChangeWithoutCommands() = runTest {
        for (installed in listOf(uid + 1, null)) {
            for (status in listOf(PREPARED, UNKNOWN)) {
                val f = Fixture()
                f.reply("system,android,1000\nuser,$pkg,$uid", "Removed: $pkg", "system,android,1000")
                assertEquals(ActionResult.Applied(1), f.apply(ActionType.REMOVE_DOZE_WHITELIST))
                f.dao.updateAction(f.row().copy(status = status, message = null))
                f.inspector.installed = installed

                f.repo.reconcile()

                assertEquals(REVERTED, f.row().status)
                assertNotNull(f.row().revertedAt)
                assertEquals("CHANGED_EXTERNALLY", f.row().message)
                assertEquals(3, f.commands.size)
                assertEquals(ActionResult.Failed(FailureCode.NOT_UNDOABLE), f.repo.undo(1))
            }
        }
    }

    @Test fun reapplyAfterExternalDozeChangeClosesOldRowBeforeWritingAndPreventsOldUndo() = runTest {
        val f = Fixture()
        val system = "system,android,1000"
        val present = "$system\nuser,$pkg,$uid"
        f.reply(present, "Removed: $pkg", system)
        assertEquals(ActionResult.Applied(1), f.apply(ActionType.REMOVE_DOZE_WHITELIST))
        val old = f.row()

        // Settings re-adds the app; the next apply observes that live membership.
        f.reply(present, "Removed: $pkg", system)
        f.intercept = {
            if (it is PrivilegedCommand.RemoveDozeWhitelist) {
                val closed = f.dao.rows.value.first { row -> row.id == old.id }
                assertEquals(REVERTED, closed.status)
                assertEquals("CHANGED_EXTERNALLY", closed.message)
                assertNotNull(closed.revertedAt)
                assertEquals(old.appliedAt, closed.appliedAt)
                assertEquals(PREPARED, f.dao.rows.value.last().status)
            }
        }
        assertEquals(ActionResult.Applied(2), f.apply(ActionType.REMOVE_DOZE_WHITELIST))
        assertEquals(APPLIED, f.dao.rows.value.last().status)
        val commandsBeforeUndo = f.commands.size
        assertEquals(ActionResult.Failed(FailureCode.NOT_UNDOABLE), f.repo.undo(old.id))
        assertEquals(commandsBeforeUndo, f.commands.size)
        assertEquals(APPLIED, f.dao.rows.value.last().status)

        f.intercept = {}
        f.reply(system, "Added: $pkg", present)
        assertEquals(ActionResult.Reverted, f.repo.undo(2))
        assertEquals(PrivilegedCommand.AddDozeWhitelist(pkg), f.commands[7])
    }

    @Test fun reapplySettlesUnknownDozeRowAtPriorBeforeWritingAndPreventsBackdatedAuthority() = runTest {
        for (appliedAt in listOf(null, 90L)) {
            val f = Fixture()
            val system = "system,android,1000"
            val present = "$system\nuser,$pkg,$uid"
            f.replies.addAll(listOf(ok(present), uncertainFailure(), failure()))
            assertEquals(ActionResult.Unknown, f.apply(ActionType.REMOVE_DOZE_WHITELIST))
            f.replies += denied()
            f.repo.reconcile()
            assertEquals(UNKNOWN, f.row().status)
            assertNull(f.row().appliedAt)
            f.dao.updateAction(f.row().copy(appliedAt = appliedAt))
            val old = f.row()
            val expectedStatus = if (appliedAt == null) FAILED else REVERTED
            f.now += 1_000L
            f.reply(present, "Removed: $pkg", system)
            f.intercept = {
                if (it is PrivilegedCommand.RemoveDozeWhitelist) {
                    val settled = f.dao.rows.value.first()
                    assertEquals(expectedStatus, settled.status)
                    assertEquals(appliedAt, settled.appliedAt)
                    if (appliedAt == null) {
                        assertEquals("STATE_MISMATCH", settled.message)
                        assertNull(settled.revertedAt)
                    } else {
                        assertNull(settled.message)
                        assertEquals(1_101L, settled.revertedAt)
                    }
                }
            }
            assertEquals(ActionResult.Applied(2), f.apply(ActionType.REMOVE_DOZE_WHITELIST))
            f.intercept = {}
            val settled = f.dao.rows.value.first()
            // The live target of the new action must not resurrect the old row.
            f.repo.reconcile()
            assertEquals(settled, f.dao.rows.value.first())
            assertEquals(listOf(2L), f.dao.rows.value.filter { it.status in listOf(APPLIED, UNKNOWN) }.map { it.id })
            val commandsBeforeUndo = f.commands.size
            assertEquals(ActionResult.Failed(FailureCode.NOT_UNDOABLE), f.repo.undo(old.id))
            assertEquals(commandsBeforeUndo, f.commands.size)
            f.reply(system, "Added: $pkg", present)
            assertEquals(ActionResult.Reverted, f.repo.undo(2))
        }
    }

    @Test fun reapplyAfterReinstallSettlesStaleUnknownRowAsChangedExternally() = runTest {
        for (appliedAt in listOf(null, 90L)) {
            val f = Fixture()
            f.replies.addAll(listOf(ok("active"), uncertainFailure(), failure()))
            assertEquals(ActionResult.Unknown, f.apply(ActionType.STANDBY_BUCKET_RESTRICTED))
            assertEquals(UNKNOWN, f.row().status)
            assertEquals("ACTIVE", f.row().priorState)
            f.dao.updateAction(f.row().copy(appliedAt = appliedAt))
            val old = f.row()
            f.inspector.installed = uid + 1
            f.reply("active", "", "restricted")
            f.intercept = {
                if (it is PrivilegedCommand.SetStandbyBucket) {
                    val settled = f.dao.rows.value.first()
                    assertEquals("CHANGED_EXTERNALLY", settled.message)
                    assertEquals(REVERTED, settled.status)
                    assertNotNull(settled.revertedAt)
                    assertEquals(old.uid, settled.uid)
                    assertEquals(appliedAt, settled.appliedAt)
                }
            }

            assertEquals(ActionResult.Applied(2), f.repo.apply(
                finding(Subject.App(uid + 1, pkg)), rec(ActionType.STANDBY_BUCKET_RESTRICTED),
            ))
            assertEquals(APPLIED, f.dao.rows.value.last().status)
            assertEquals(uid + 1, f.dao.rows.value.last().uid)
            val commandsBeforeUndo = f.commands.size
            assertEquals(ActionResult.Failed(FailureCode.NOT_UNDOABLE), f.repo.undo(old.id))
            assertEquals(commandsBeforeUndo, f.commands.size)
        }
    }

    @Test fun reapplyAfterReinstallSettlesStaleAppliedRowEvenWhenTargetMatchesRead() = runTest {
        val f = Fixture()
        f.reply("active", "", "restricted")
        assertEquals(ActionResult.Applied(1), f.apply(ActionType.STANDBY_BUCKET_RESTRICTED))
        val old = f.row()
        f.inspector.installed = uid + 1
        f.reply("restricted")

        assertEquals(ActionResult.Refused(RefusalCode.ALREADY_AT_TARGET), f.repo.apply(
            finding(Subject.App(uid + 1, pkg)), rec(ActionType.STANDBY_BUCKET_RESTRICTED),
        ))
        assertEquals("CHANGED_EXTERNALLY", f.row().message)
        assertEquals(REVERTED, f.row().status)
        assertNotNull(f.row().revertedAt)
        assertEquals(old.uid, f.row().uid)
        assertEquals(old.appliedAt, f.row().appliedAt)
        assertEquals(4, f.commands.size)
        assertEquals(ActionResult.Failed(FailureCode.NOT_UNDOABLE), f.repo.undo(old.id))
        assertEquals(4, f.commands.size)
    }

    @Test fun newThirdStatePriorRetiresOldUnknownBeforeWriteAndCannotRestoreOlderPrior() = runTest {
        val f = Fixture()
        f.reply("active", "", "working_set")
        assertEquals(ActionResult.Unknown, f.apply(ActionType.STANDBY_BUCKET_RESTRICTED))
        val old = f.row()
        f.reply("working_set", "", "restricted")
        f.intercept = {
            if (it is PrivilegedCommand.SetStandbyBucket) {
                assertEquals("Old Undo authority must be retired before dispatch", REVERTED, f.dao.rows.value.first().status)
                assertEquals("CHANGED_EXTERNALLY", f.dao.rows.value.first().message)
            }
        }
        assertEquals(ActionResult.Applied(2), f.apply(ActionType.STANDBY_BUCKET_RESTRICTED))
        f.intercept = {}
        assertEquals("WORKING_SET", f.dao.rows.value.last().priorState)
        val before = f.commands.size
        assertEquals(ActionResult.Failed(FailureCode.NOT_UNDOABLE), f.repo.undo(old.id))
        assertEquals(before, f.commands.size)
        f.repo.reconcile()
        assertEquals(before, f.commands.size)
        assertEquals(REVERTED, f.dao.rows.value.first().status)
        f.reply("restricted", "", "working_set")
        assertEquals(ActionResult.Reverted, f.repo.undo(2))
        assertEquals(PrivilegedCommand.SetStandbyBucket(pkg, StandbyBucket.WORKING_SET), f.commands[before + 1])
    }

    @Test fun reapplyBeforeDelayedReconcileRetiresCrashPreparedThirdStateAuthority() = runTest {
        val f = Fixture()
        f.reply("active", "")
        var reads = 0
        f.intercept = {
            if (it is PrivilegedCommand.GetStandbyBucket && ++reads == 2) {
                throw IllegalStateException("simulated process death before committed readback")
            }
        }
        try {
            f.apply(ActionType.STANDBY_BUCKET_RESTRICTED)
            fail("crash required")
        } catch (_: IllegalStateException) { }
        val old = f.row()
        assertEquals(PREPARED, old.status)
        assertEquals("ACTIVE", old.priorState)
        f.reply("working_set", "", "restricted")
        f.intercept = {
            if (it is PrivilegedCommand.SetStandbyBucket) {
                assertEquals("Retire PREPARED authority before dispatch", REVERTED, f.dao.rows.value.first().status)
                assertEquals("CHANGED_EXTERNALLY", f.dao.rows.value.first().message)
            }
        }
        assertEquals(ActionResult.Applied(2), f.apply(ActionType.STANDBY_BUCKET_RESTRICTED))
        f.intercept = {}
        assertEquals("WORKING_SET", f.dao.rows.value.last().priorState)
        val before = f.commands.size
        // Startup recovery is delayed until after the newly confirmed apply.
        f.repo.reconcile()
        assertEquals(before, f.commands.size)
        assertEquals(REVERTED, f.dao.rows.value.first().status)
        assertEquals(ActionResult.Failed(FailureCode.NOT_UNDOABLE), f.repo.undo(old.id))
        assertEquals(before, f.commands.size)
        f.reply("restricted", "", "working_set")
        assertEquals(ActionResult.Reverted, f.repo.undo(2))
        assertEquals(PrivilegedCommand.SetStandbyBucket(pkg, StandbyBucket.WORKING_SET), f.commands[before + 1])
    }

    @Test fun reapplySettlesPreparedAtOriginalPriorBeforeDispatch() = runTest {
        val f = Fixture()
        f.reply("active", "", "working_set")
        assertEquals(ActionResult.Unknown, f.apply(ActionType.STANDBY_BUCKET_RESTRICTED))
        f.dao.updateAction(f.row().copy(status = PREPARED))
        f.reply("active", "", "restricted")
        f.intercept = {
            if (it is PrivilegedCommand.SetStandbyBucket) {
                assertEquals(FAILED, f.dao.rows.value.first().status)
                assertEquals(FailureCode.STATE_MISMATCH.name, f.dao.rows.value.first().message)
            }
        }
        assertEquals(ActionResult.Applied(2), f.apply(ActionType.STANDBY_BUCKET_RESTRICTED))
        f.intercept = {}
        val before = f.commands.size
        f.repo.reconcile()
        assertEquals(before, f.commands.size)
        assertEquals(ActionResult.Failed(FailureCode.NOT_UNDOABLE), f.repo.undo(1))
        assertEquals(before, f.commands.size)
    }

    @Test fun preparedNoWriteAndUnrelatedRowsKeepTheirAuthority() = runTest {
        for ((live, reason) in listOf("never" to RefusalCode.UNRESTORABLE_PRIOR,
            "restricted" to RefusalCode.ALREADY_AT_TARGET)) {
            val f = Fixture()
            f.reply("active", "", "working_set")
            assertEquals(ActionResult.Unknown, f.apply(ActionType.STANDBY_BUCKET_RESTRICTED))
            val old = f.row().copy(status = PREPARED)
            f.dao.updateAction(old)
            f.reply(live)
            assertEquals(ActionResult.Refused(reason), f.apply(ActionType.STANDBY_BUCKET_RESTRICTED))
            assertEquals(old, f.row())
            assertEquals(4, f.commands.size)
        }
        val f = Fixture()
        f.reply("active", "", "working_set")
        assertEquals(ActionResult.Unknown, f.apply(ActionType.STANDBY_BUCKET_RESTRICTED))
        val old = f.row().copy(status = PREPARED)
        f.dao.updateAction(old)
        val unrelated = listOf(
            old.copy(id = 2, packageName = "com.example.other"),
            old.copy(id = 3, type = ActionType.STANDBY_BUCKET_RARE.name),
        )
        f.dao.rows.value += unrelated
        f.reply("working_set", "", "restricted")
        assertEquals(ActionResult.Applied(4), f.apply(ActionType.STANDBY_BUCKET_RESTRICTED))
        assertEquals(REVERTED, f.dao.rows.value.first().status)
        assertEquals(unrelated, f.dao.rows.value.slice(1..2))
    }

    @Test fun refusedNewApplyDoesNotRetireThirdStateUnknownAuthority() = runTest {
        for ((live, reason) in listOf("never" to RefusalCode.UNRESTORABLE_PRIOR,
            "restricted" to RefusalCode.ALREADY_AT_TARGET)) {
            val f = Fixture()
            f.reply("active", "", "working_set")
            assertEquals(ActionResult.Unknown, f.apply(ActionType.STANDBY_BUCKET_RESTRICTED))
            val old = f.row()
            f.reply(live)
            assertEquals(ActionResult.Refused(reason), f.apply(ActionType.STANDBY_BUCKET_RESTRICTED))
            assertEquals(old, f.row())
            assertEquals(4, f.commands.size)
        }
    }

    @Test fun applyRetiresSameActionThirdStateUnknownButPreservesOtherTypesAndPackages() = runTest {
        val f = Fixture()
        f.replies.addAll(listOf(ok("active"), uncertainFailure(), failure()))
        assertEquals(ActionResult.Unknown, f.apply(ActionType.STANDBY_BUCKET_RARE))
        val old = f.row()
        val retained = listOf(
            old.copy(id = 2, priorState = "WORKING_SET", packageName = "com.example.other"),
            old.copy(id = 3, priorState = "WORKING_SET", type = ActionType.STANDBY_BUCKET_RESTRICTED.name),
        )
        f.dao.rows.value += retained
        f.reply("working_set", "", "rare")
        assertEquals(ActionResult.Applied(4), f.apply(ActionType.STANDBY_BUCKET_RARE))
        assertEquals(REVERTED, f.dao.rows.value.first().status)
        assertEquals(retained, f.dao.rows.value.slice(1..2))
    }

    @Test fun applyRetiresStaleMatchingAuthorityAndPreservesOtherTypesPackagesAndLiveTargets() = runTest {
        val f = Fixture()
        f.reply("active", "", "rare")
        assertEquals(ActionResult.Applied(1), f.apply(ActionType.STANDBY_BUCKET_RARE))
        val stale = f.row()
        val retained = listOf(
            stale.copy(id = 2, packageName = "com.example.other"),
            stale.copy(id = 3, type = ActionType.STANDBY_BUCKET_RESTRICTED.name),
            stale.copy(id = 4, status = UNKNOWN),
            stale.copy(id = 5, targetState = "WORKING_SET"),
        )
        f.dao.rows.value += retained
        f.reply("working_set", "", "rare")
        assertEquals(ActionResult.Applied(6), f.apply(ActionType.STANDBY_BUCKET_RARE))
        assertEquals(REVERTED, f.dao.rows.value.first().status)
        assertEquals("CHANGED_EXTERNALLY", f.dao.rows.value.first().message)
        assertEquals(REVERTED, f.dao.rows.value.first { it.id == 4L }.status)
        assertEquals(retained.filter { it.id != 4L }, f.dao.rows.value.filter { it.id in listOf(2L, 3L, 5L) })
    }

    @Test fun unknownPriorDoesNotCloseAppliedRows() = runTest {
        val f = Fixture()
        f.reply("active", "", "rare")
        assertEquals(ActionResult.Applied(1), f.apply(ActionType.STANDBY_BUCKET_RARE))
        val old = f.row()
        f.reply("garbage")
        assertEquals(ActionResult.Failed(FailureCode.READ_FAILED), f.apply(ActionType.STANDBY_BUCKET_RARE))
        assertEquals(old, f.row())
    }

    @Test fun refusesUnrestorablePriorStatesWithoutMutationOrJournal() = runTest {
        for (mode in listOf("default", "deny", "foreground")) {
            val f = Fixture()
            f.reply("RUN_ANY_IN_BACKGROUND: $mode", "", "RUN_ANY_IN_BACKGROUND: ignore")
            assertEquals(ActionResult.Refused(RefusalCode.UNRESTORABLE_PRIOR), f.apply())
            assertEquals(1, f.commands.size)
            assertTrue(f.dao.rows.value.isEmpty())
        }
        for (bucket in listOf("exempted", "never")) {
            val f = Fixture()
            f.reply(bucket, "", "rare")
            assertEquals(ActionResult.Refused(RefusalCode.UNRESTORABLE_PRIOR), f.apply(ActionType.STANDBY_BUCKET_RARE))
            assertEquals(1, f.commands.size)
            assertTrue(f.dao.rows.value.isEmpty())
        }
    }

    @Test fun refusesUnsafeIdentitiesBeforeAnyCommand() = runTest {
        val cases: List<Pair<RefusalCode, (Inspector) -> Unit>> = listOf(
            RefusalCode.NOT_INSTALLED to { it.installed = null },
            RefusalCode.UID_MISMATCH to { it.installed = uid + 1 },
            RefusalCode.SHARED_UID to { it.packages = listOf(pkg, "com.example.other") },
            RefusalCode.SHARED_UID to { it.packages = emptyList() },
            RefusalCode.ROLE_HOLDER to { it.roles = setOf(pkg) },
        )
        for ((code, setup) in cases) {
            val f = Fixture(); setup(f.inspector)
            assertEquals(ActionResult.Refused(code), f.apply())
            assertTrue(f.commands.isEmpty()); assertTrue(f.dao.rows.value.isEmpty())
        }
        for (app in listOf(Subject.App(9999, pkg), Subject.App(uid, "com.android.systemui"), Subject.App(110123, pkg))) {
            val f = Fixture()
            assertEquals(ActionResult.Refused(RefusalCode.PROTECTED), f.repo.apply(finding(app), rec(ActionType.FORCE_STOP)))
            assertTrue(f.commands.isEmpty())
        }
        val f = Fixture()
        assertEquals(ActionResult.Refused(RefusalCode.INVALID_SUBJECT), f.repo.apply(finding(Subject.Device), rec(ActionType.FORCE_STOP)))
        assertEquals(ActionResult.Refused(RefusalCode.INVALID_PACKAGE), f.repo.apply(finding(Subject.App(uid, "bad;pkg")), rec(ActionType.FORCE_STOP)))
    }

    @Test fun sdkGatingAndLegacyOp() = runTest {
        for (sdk in listOf(26, 27)) {
            for (type in listOf(ActionType.RESTRICT_BACKGROUND, ActionType.STANDBY_BUCKET_RARE, ActionType.STANDBY_BUCKET_RESTRICTED)) {
                val f = Fixture(); f.inspector.sdkInt = sdk
                f.reply("No operations.", "", "RUN_IN_BACKGROUND: ignore")
                assertEquals("$type API $sdk", ActionResult.Refused(RefusalCode.UNSUPPORTED_SDK), f.apply(type))
                assertTrue("Unsupported fixes must not issue shell commands", f.commands.isEmpty())
                assertTrue("Unsupported fixes must not create journal rows", f.dao.rows.value.isEmpty())
            }
        }
        val f = Fixture(); f.inspector.sdkInt = 28
        f.reply("No operations.", "", "RUN_ANY_IN_BACKGROUND: ignore")
        assertEquals(ActionResult.Applied(1), f.apply())
        assertEquals(PrivilegedCommand.SetBackgroundOp(pkg, BackgroundOp.RUN_ANY_IN_BACKGROUND, AppOpMode.IGNORE), f.commands[1])
    }

    @Test fun legacyBackgroundJournalRestoresRecordedOpOnApi26AndAfterUpgrade() = runTest {
        for (sdk in listOf(26, 35)) {
            val f = Fixture(); f.inspector.sdkInt = sdk
            f.dao.insertAction(InsightActionEntity(
                findingKey = "finding", type = ActionType.RESTRICT_BACKGROUND.name,
                packageName = pkg, uid = uid, userId = 0, status = APPLIED,
                priorStateVersion = 1, priorState = "RUN_IN_BACKGROUND:ALLOW",
                targetState = "RUN_IN_BACKGROUND:IGNORE", createdAt = 1, appliedAt = 2,
            ))
            f.reply("RUN_IN_BACKGROUND: ignore", "", "RUN_IN_BACKGROUND: allow")
            assertEquals(ActionResult.Reverted, f.repo.undo(1))
            assertEquals(listOf(
                PrivilegedCommand.GetBackgroundOp(pkg, BackgroundOp.RUN_IN_BACKGROUND),
                PrivilegedCommand.SetBackgroundOp(pkg, BackgroundOp.RUN_IN_BACKGROUND, AppOpMode.ALLOW),
                PrivilegedCommand.GetBackgroundOp(pkg, BackgroundOp.RUN_IN_BACKGROUND),
            ), f.commands)
            assertEquals(REVERTED, f.row().status)
        }
    }

    @Test fun legacyBackgroundJournalReconcilesOnApi26WithoutWriting() = runTest {
        val f = Fixture(); f.inspector.sdkInt = 26
        f.dao.insertAction(InsightActionEntity(
            findingKey = "finding", type = ActionType.RESTRICT_BACKGROUND.name,
            packageName = pkg, uid = uid, userId = 0, status = PREPARED,
            priorStateVersion = 1, priorState = "RUN_IN_BACKGROUND:ALLOW",
            targetState = "RUN_IN_BACKGROUND:IGNORE", createdAt = 1,
        ))
        f.reply("RUN_IN_BACKGROUND: ignore")
        f.repo.reconcile()
        assertEquals(APPLIED, f.row().status)
        assertEquals(listOf(PrivilegedCommand.GetBackgroundOp(pkg, BackgroundOp.RUN_IN_BACKGROUND)), f.commands)
    }

    @Test fun preflightNoAccessWritesNoRow() = runTest {
        val f = Fixture(); f.replies += denied()
        assertEquals(ActionResult.Refused(RefusalCode.NOT_PRIVILEGED), f.apply())
        assertTrue(f.dao.rows.value.isEmpty())
    }

    @Test fun executeNoAccessFailsWithoutUndoOrReadback() = runTest {
        val f = Fixture(); f.replies.addAll(listOf(ok("No operations."), denied()))
        assertEquals(ActionResult.Refused(RefusalCode.NOT_PRIVILEGED), f.apply())
        assertEquals(FAILED, f.row().status)
        assertEquals(RefusalCode.NOT_PRIVILEGED.name, f.row().message)
        assertEquals(2, f.commands.size)
        f.repo.reconcile()
        assertEquals(ActionResult.Failed(FailureCode.NOT_UNDOABLE), f.repo.undo(1))
        assertEquals(2, f.commands.size)
    }

    @Test fun uncertainWriteAtPriorRetainsUnknownUntilLateApplicationAndUndo() = runTest {
        val f = Fixture()
        f.replies.addAll(listOf(ok("No operations."),
            ShellRunner.Outcome.Failure(ShellRunner.Mode.ROOT, "timeout", ExecutionCertainty.UNKNOWN),
            ok("No operations.")))
        assertEquals(ActionResult.Unknown, f.apply())
        assertEquals(UNKNOWN, f.row().status)
        assertNull(f.row().message)
        assertNull(f.row().appliedAt)
        assertEquals(3, f.commands.size)
        // The timed-out write lands after the immediate readback.
        f.reply("RUN_ANY_IN_BACKGROUND: ignore")
        f.repo.reconcile()
        assertEquals(APPLIED, f.row().status)
        assertEquals(1, f.commands.count { it is PrivilegedCommand.SetBackgroundOp })
        f.reply("RUN_ANY_IN_BACKGROUND: ignore", "", "No operations.")
        assertEquals(ActionResult.Reverted, f.repo.undo(1))
        assertEquals(REVERTED, f.row().status)
    }

    @Test fun undoNoAccessPreservesAppliedAuthorityAndRefusesWithoutReadback() = runTest {
        val f = Fixture()
        f.reply("No operations.", "", "RUN_ANY_IN_BACKGROUND: ignore")
        assertEquals(ActionResult.Applied(1), f.apply())
        val appliedAt = f.row().appliedAt
        f.replies.addAll(listOf(ok("RUN_ANY_IN_BACKGROUND: ignore"), denied()))
        assertEquals(ActionResult.Refused(RefusalCode.NOT_PRIVILEGED), f.repo.undo(1))
        assertEquals(APPLIED, f.row().status)
        assertEquals(appliedAt, f.row().appliedAt)
        assertEquals(RefusalCode.NOT_PRIVILEGED.name, f.row().message)
        assertEquals(5, f.commands.size)
        f.reply("RUN_ANY_IN_BACKGROUND: ignore", "", "No operations.")
        assertEquals(ActionResult.Reverted, f.repo.undo(1))
    }

    @Test fun uncertainUndoAtTargetRemainsUnknownUntilLateRestoration() = runTest {
        val f = Fixture()
        f.reply("No operations.", "", "RUN_ANY_IN_BACKGROUND: ignore")
        assertEquals(ActionResult.Applied(1), f.apply())
        val appliedAt = f.row().appliedAt
        f.replies.addAll(listOf(ok("RUN_ANY_IN_BACKGROUND: ignore"),
            ShellRunner.Outcome.Failure(ShellRunner.Mode.ROOT, "timeout", ExecutionCertainty.UNKNOWN),
            ok("RUN_ANY_IN_BACKGROUND: ignore")))
        assertEquals(ActionResult.Unknown, f.repo.undo(1))
        assertEquals(UNKNOWN, f.row().status)
        assertEquals(appliedAt, f.row().appliedAt)
        assertNull(f.row().message)
        f.reply("No operations.")
        f.repo.reconcile()
        assertEquals(REVERTED, f.row().status)
        assertEquals(2, f.commands.count { it is PrivilegedCommand.SetBackgroundOp })
    }

    @Test fun confirmationNoAccessRetainsUnknownUndoAuthority() = runTest {
        val f = Fixture(); f.replies.addAll(listOf(ok("No operations."), ok(), denied()))
        assertEquals(ActionResult.Unknown, f.apply()); assertEquals(UNKNOWN, f.row().status)
        f.reply("RUN_ANY_IN_BACKGROUND: ignore", "", "No operations.")
        assertEquals(ActionResult.Reverted, f.repo.undo(1))
    }

    @Test fun uncertainApplyReconciledAtTargetUsesCreatedAtNotRecoveryTime() = runTest {
        val f = Fixture()
        f.replies.addAll(listOf(ok("active"), ok(), failure()))
        assertEquals(ActionResult.Unknown, f.apply(ActionType.STANDBY_BUCKET_RARE))
        assertEquals(UNKNOWN, f.row().status)
        assertNull(f.row().appliedAt)
        val createdAt = f.row().createdAt

        f.now += 3 * 24 * 60 * 60 * 1_000L
        f.reply("rare")
        f.repo.reconcile()

        assertEquals(APPLIED, f.row().status)
        assertEquals(createdAt, f.row().appliedAt)
        assertEquals(1, f.commands.count { it is PrivilegedCommand.SetStandbyBucket })
    }

    @Test fun uncertainApplySuccessfulUndoAtTargetPersistsCreatedAtBeforeRestore() = runTest {
        val f = Fixture()
        f.replies.addAll(listOf(ok("active"), ok(), failure()))
        assertEquals(ActionResult.Unknown, f.apply(ActionType.STANDBY_BUCKET_RARE))
        assertNull(f.row().appliedAt)
        val createdAt = f.row().createdAt
        f.now += 1_000L
        f.intercept = {
            if (it is PrivilegedCommand.SetStandbyBucket) {
                assertEquals(UNKNOWN, f.row().status)
                assertEquals(createdAt, f.row().appliedAt)
            }
        }
        f.reply("rare", "", "active")
        assertEquals(ActionResult.Reverted, f.repo.undo(1))
        assertEquals(REVERTED, f.row().status)
        assertEquals(createdAt, f.row().appliedAt)
        assertNotNull(f.row().revertedAt)
        assertNull(f.row().message)
    }

    @Test fun uncertainApplyInterruptedUndoReconcilesPriorAsRevertedWithCreatedAt() = runTest {
        for (crash in listOf(false, true)) {
            val f = Fixture()
            f.replies.addAll(listOf(ok("active"), ok(), failure()))
            assertEquals(ActionResult.Unknown, f.apply(ActionType.STANDBY_BUCKET_RARE))
            assertNull(f.row().appliedAt)
            val createdAt = f.row().createdAt
            f.now += 1_000L
            f.reply("rare", "")
            if (crash) {
                f.intercept = {
                    if (it is PrivilegedCommand.GetStandbyBucket && f.commands.size == 6) {
                        throw IllegalStateException("simulated crash after restore")
                    }
                }
                try { f.repo.undo(1); fail("crash required") } catch (_: IllegalStateException) { }
                f.intercept = {}
            } else {
                f.replies += failure()
                assertEquals(ActionResult.Unknown, f.repo.undo(1))
            }
            assertEquals(UNKNOWN, f.row().status)
            f.reply("active")
            f.repo.reconcile()
            assertEquals(REVERTED, f.row().status)
            assertEquals(createdAt, f.row().appliedAt)
            assertNotNull(f.row().revertedAt)
            assertEquals(2, f.commands.count { it is PrivilegedCommand.SetStandbyBucket })
        }
    }

    @Test fun uncertainApplyUndoKeepsCreatedAtWhenWriteRefusedOrReadbackUnknown() = runTest {
        for (readback in listOf(null, "garbage", "frequent")) {
            val f = Fixture()
            f.replies.addAll(listOf(ok("active"), ok(), failure()))
            assertEquals(ActionResult.Unknown, f.apply(ActionType.STANDBY_BUCKET_RARE))
            val createdAt = f.row().createdAt
            f.reply("rare")
            if (readback == null) f.replies += denied() else f.reply("", readback)
            val expected = if (readback == null) ActionResult.Refused(RefusalCode.NOT_PRIVILEGED) else ActionResult.Unknown
            assertEquals(expected, f.repo.undo(1))
            assertEquals(if (readback == null) APPLIED else UNKNOWN, f.row().status)
            assertEquals(createdAt, f.row().appliedAt)
        }
    }

    @Test fun uncertainApplyFailedUndoAtTargetUsesCreatedAt() = runTest {
        val f = Fixture()
        f.replies.addAll(listOf(ok("active"), ok(), failure()))
        assertEquals(ActionResult.Unknown, f.apply(ActionType.STANDBY_BUCKET_RARE))
        assertEquals(UNKNOWN, f.row().status)
        assertNull(f.row().appliedAt)
        val createdAt = f.row().createdAt

        f.now += 3 * 24 * 60 * 60 * 1_000L
        f.reply("rare", "", "rare")
        assertEquals(ActionResult.Failed(FailureCode.STATE_MISMATCH), f.repo.undo(1))

        assertEquals(APPLIED, f.row().status)
        assertEquals(createdAt, f.row().appliedAt)
        assertEquals("STATE_MISMATCH", f.row().message)
        assertEquals(PrivilegedCommand.SetStandbyBucket(pkg, StandbyBucket.ACTIVE), f.commands[4])
    }

    @Test fun confirmedApplyKeepsConfirmationTimestampAfterFailedUndoAndReconcile() = runTest {
        val f = Fixture()
        f.reply("active", "", "rare")
        assertEquals(ActionResult.Applied(1), f.apply(ActionType.STANDBY_BUCKET_RARE))
        val appliedAt = f.row().appliedAt
        assertEquals(101L, appliedAt)
        assertEquals(100L, f.row().createdAt)

        f.now += 3 * 24 * 60 * 60 * 1_000L
        f.reply("rare", "", "rare")
        assertEquals(ActionResult.Failed(FailureCode.STATE_MISMATCH), f.repo.undo(1))
        assertEquals(APPLIED, f.row().status)
        assertEquals(appliedAt, f.row().appliedAt)

        f.replies.addAll(listOf(ok("rare"), uncertainFailure(), failure()))
        assertEquals(ActionResult.Unknown, f.repo.undo(1))
        f.reply("rare")
        f.repo.reconcile()
        assertEquals(APPLIED, f.row().status)
        assertEquals(appliedAt, f.row().appliedAt)
    }

    @Test fun crashAfterExecuteLeavesPreparedAndReconcileNeverReplays() = runTest {
        for ((output, status) in listOf("rare" to APPLIED, "active" to FAILED, "frequent" to UNKNOWN, "garbage" to UNKNOWN)) {
            val f = Fixture(); f.reply("active", "")
            var reads = 0
            f.intercept = { if (it is PrivilegedCommand.GetStandbyBucket && ++reads == 2) throw IllegalStateException("simulated crash") }
            try { f.apply(ActionType.STANDBY_BUCKET_RARE); fail("crash required") } catch (_: IllegalStateException) { }
            assertEquals(PREPARED, f.row().status)
            f.intercept = {}; f.reply(output)
            val writes = f.commands.count { it is PrivilegedCommand.SetStandbyBucket }
            f.repo.reconcile()
            assertEquals(status, f.row().status)
            assertEquals(writes, f.commands.count { it is PrivilegedCommand.SetStandbyBucket })
        }
    }

    @Test fun unknownAndFailedReadbacksNeverImplyApplied() = runTest {
        for (confirmation in listOf(ok("garbage"), failure())) {
            val f = Fixture(); f.replies.addAll(listOf(ok("No operations."), ok(), confirmation))
            assertEquals(ActionResult.Unknown, f.apply()); assertEquals(UNKNOWN, f.row().status)
        }
        val f = Fixture(); f.reply("No operations.", "", "No operations.")
        assertEquals(ActionResult.Failed(FailureCode.NOT_APPLIED), f.apply())
        assertEquals(FAILED, f.row().status)
        val failed = Fixture(); failed.replies += failure()
        assertEquals(ActionResult.Failed(FailureCode.READ_FAILED), failed.apply()); assertTrue(failed.dao.rows.value.isEmpty())
    }

    @Test fun externalChangeSettlesRevertedWithoutMutation() = runTest {
        for (current in listOf(StandbyBucket.ACTIVE, StandbyBucket.FREQUENT)) {
            val f = Fixture(); f.reply("active", "", "rare")
            assertEquals(ActionResult.Applied(1), f.apply(ActionType.STANDBY_BUCKET_RARE))
            val appliedAt = f.row().appliedAt
            f.reply(current.token)
            assertEquals(ActionResult.ChangedExternally(current.name), f.repo.undo(1))
            assertEquals(REVERTED, f.row().status)
            assertEquals("CHANGED_EXTERNALLY", f.row().message)
            assertEquals(appliedAt, f.row().appliedAt)
            assertNotNull(f.row().revertedAt)
            assertEquals(4, f.commands.size)
            assertEquals(1, f.commands.count { it is PrivilegedCommand.SetStandbyBucket })
            assertEquals(ActionResult.Failed(FailureCode.NOT_UNDOABLE), f.repo.undo(1))
            assertEquals(4, f.commands.size)
        }
    }

    @Test fun undoRevalidatesIdentityAndRejectsInvalidJournal() = runTest {
        val f = Fixture(); f.reply("active", "", "rare"); f.apply(ActionType.STANDBY_BUCKET_RARE)
        f.dao.updateAction(f.row().copy(priorStateVersion = 2))
        assertEquals(ActionResult.Failed(FailureCode.INVALID_JOURNAL), f.repo.undo(1))
        f.repo.reconcile(); assertEquals(3, f.commands.size)
    }

    @Test fun forceStopInsertFailurePreventsDispatch() = runTest {
        val f = Fixture()
        f.replies += ok()
        f.dao.beforeInsert = { throw IllegalStateException("journal unavailable") }

        try { f.apply(ActionType.FORCE_STOP); fail("insert failure required") } catch (_: IllegalStateException) { }

        assertTrue(f.commands.isEmpty())
        assertTrue(f.dao.actionsOnce().isEmpty())
    }

    @Test fun forceStopPersistsUnknownBeforeDispatchAndSettlesTheSameRow() = runTest {
        val f = Fixture()
        f.replies += ok()
        var attempt: InsightActionEntity? = null
        f.intercept = {
            assertEquals(PrivilegedCommand.ForceStop(pkg), it)
            attempt = f.dao.actionsOnce().single()
            assertEquals(UNKNOWN, attempt!!.status)
            assertEquals(ActionType.FORCE_STOP.name, attempt!!.type)
            assertEquals(pkg, attempt!!.packageName)
            assertEquals(uid, attempt!!.uid)
            assertNull(attempt!!.appliedAt)
            assertNull(attempt!!.priorState)
            assertNull(attempt!!.targetState)
        }

        assertEquals(ActionResult.OneShot(1), f.apply(ActionType.FORCE_STOP))

        assertEquals(attempt!!.id, f.dao.actionsOnce().single().id)
        assertEquals(attempt!!.createdAt, f.row().createdAt)
        assertEquals(ONE_SHOT, f.row().status)
        assertEquals(101L, f.row().appliedAt)
        assertNull(f.row().message)
    }

    @Test fun forceStopExecutorExceptionRetainsUnknownWithoutRecoveryOrUndoCommands() = runTest {
        val f = Fixture()
        f.intercept = { throw IllegalStateException("response lost after dispatch") }

        try { f.apply(ActionType.FORCE_STOP); fail("executor failure required") } catch (_: IllegalStateException) { }

        f.assertUnknownForceStopIsNotRecoverable()
    }

    @Test fun forceStopExecutorCancellationRetainsUnknownWithoutRecoveryOrUndoCommands() = runTest {
        val f = Fixture()
        val dispatched = CompletableDeferred<Unit>()
        f.intercept = { dispatched.complete(Unit); awaitCancellation() }
        val apply = launch { f.apply(ActionType.FORCE_STOP) }
        dispatched.await()
        apply.cancelAndJoin()

        f.assertUnknownForceStopIsNotRecoverable()
    }

    @Test fun forceStopSettlementFailureRetainsUnknownWithoutRecoveryOrUndoCommands() = runTest {
        for (outcome in listOf(ok(), failure(), denied())) {
            val f = Fixture()
            f.replies += outcome
            f.dao.beforeUpdate = { throw IllegalStateException("journal unavailable after dispatch") }

            try { f.apply(ActionType.FORCE_STOP); fail("settlement failure required") } catch (_: IllegalStateException) { }

            f.dao.beforeUpdate = {}
            f.assertUnknownForceStopIsNotRecoverable()
        }
    }

    @Test fun forceStopSettlementCancellationRetainsUnknownWithoutRecoveryOrUndoCommands() = runTest {
        val f = Fixture()
        f.replies += ok()
        val settling = CompletableDeferred<Unit>()
        f.dao.beforeUpdate = { settling.complete(Unit); awaitCancellation() }
        val apply = launch { f.apply(ActionType.FORCE_STOP) }
        runCurrent()
        try {
            assertTrue("known outcome must update the durable attempt", settling.isCompleted)
        } finally {
            apply.cancelAndJoin()
        }

        f.dao.beforeUpdate = {}
        f.assertUnknownForceStopIsNotRecoverable()
    }

    @Test fun forceStopNoAccessSettlesFailedAndPreservesPrivilegeRefusal() = runTest {
        val f = Fixture()
        f.replies += denied()

        assertEquals(ActionResult.Refused(RefusalCode.NOT_PRIVILEGED), f.apply(ActionType.FORCE_STOP))

        assertEquals(FAILED, f.row().status)
        assertEquals("NOT_PRIVILEGED", f.row().message)
        assertNull(f.row().appliedAt)
        assertNull(f.row().revertedAt)
        assertEquals(listOf(PrivilegedCommand.ForceStop(pkg)), f.commands)
        f.repo.reconcile()
        assertEquals(ActionResult.Failed(FailureCode.NOT_UNDOABLE), f.repo.undo(f.row().id))
        assertEquals(1, f.commands.size)
    }

    private suspend fun Fixture.assertUnknownForceStopIsNotRecoverable() {
        val saved = dao.actionsOnce().single()
        assertEquals(UNKNOWN, saved.status)
        assertNull(saved.appliedAt)
        assertNull(saved.revertedAt)
        assertNull(saved.priorState)
        assertNull(saved.targetState)
        assertNull(saved.message)
        assertEquals(listOf(PrivilegedCommand.ForceStop(pkg)), commands)
        repo.reconcile()
        assertEquals(ActionResult.Failed(FailureCode.NOT_UNDOABLE), repo.undo(saved.id))
        assertEquals(saved, dao.actionsOnce().single())
        assertEquals(listOf(PrivilegedCommand.ForceStop(pkg)), commands)
    }

    @Test fun ambiguousForceStopIsUnknownWithoutUndoOrReconciliationReplayAndCanBeDeliberatelyRetried() = runTest {
        val f = Fixture()
        f.replies += ShellRunner.Outcome.Failure(ShellRunner.Mode.SHIZUKU, "response lost", ExecutionCertainty.UNKNOWN)
        assertEquals(ActionResult.Unknown, f.apply(ActionType.FORCE_STOP))
        val old = f.row()
        assertEquals(UNKNOWN, old.status)
        assertNull(old.appliedAt)
        assertEquals(ActionResult.Failed(FailureCode.NOT_UNDOABLE), f.repo.undo(old.id))
        f.repo.reconcile()
        assertEquals(1, f.commands.size)
        assertEquals(old, f.row())
        f.replies += ok()
        assertEquals(ActionResult.OneShot(2), f.apply(ActionType.FORCE_STOP))
        assertEquals(2, f.commands.size)
        assertEquals(ONE_SHOT, f.dao.rows.value.last().status)
    }

    @Test fun reversibleMutationUncertaintyIsResolvedByApplyAndUndoReadbacks() = runTest {
        val f = Fixture()
        val uncertain = ShellRunner.Outcome.Failure(ShellRunner.Mode.SHIZUKU, "response lost", ExecutionCertainty.UNKNOWN)
        f.replies.addAll(listOf(ok("working_set"), uncertain, ok("restricted")))
        assertEquals(ActionResult.Applied(1), f.apply(ActionType.STANDBY_BUCKET_RESTRICTED))
        f.replies.addAll(listOf(ok("restricted"), uncertain, ok("working_set")))
        assertEquals(ActionResult.Reverted, f.repo.undo(1))
        assertEquals(6, f.commands.size)
    }

    @Test fun forceStopIsOneShotAndFailuresUseFixedCodes() = runTest {
        val f = Fixture(); f.reply()
        f.replies += ok()
        assertEquals(ActionResult.OneShot(1), f.apply(ActionType.FORCE_STOP))
        assertEquals(ONE_SHOT, f.row().status); assertEquals(1, f.commands.size)
        assertEquals(ActionResult.Failed(FailureCode.NOT_UNDOABLE), f.repo.undo(1))
        val failed = Fixture(); failed.replies += failure()
        assertEquals(ActionResult.Failed(FailureCode.EXECUTION_FAILED), failed.apply(ActionType.FORCE_STOP))
        assertEquals(FAILED, failed.row().status)
        assertNull(failed.row().appliedAt)
        assertEquals("EXECUTION_FAILED", failed.row().message)
    }

    @Test fun failedAlertEnablerPropagatesBeforeAnyJournalInsert() = runTest {
        val dao = Dao()
        val failure = java.io.IOException("settings write failed")
        val repo = InsightActionRepository(dao, { error("alert must not execute a shell action") }, Inspector(),
            { 100L }, { throw failure })
        try {
            repo.apply(finding(Subject.Device), rec(ActionType.ENABLE_HIGH_BATTERY_ALERT))
            fail("The enabler IOException must propagate")
        } catch (actual: java.io.IOException) {
            assertSame(failure, actual)
        }
        assertTrue("A failed enabler must not write a successful one-shot journal row", dao.rows.value.isEmpty())
    }

    @Test fun manualSettingsAreNotJournaledAndAlertNeedsNoPrivilege() = runTest {
        val f = Fixture()
        assertEquals(ActionResult.OpenSettings(IntentSpec("android.settings.APPLICATION_DETAILS_SETTINGS", pkg)), f.apply(ActionType.OPEN_APP_SETTINGS))
        assertEquals(ActionResult.OpenSettings(IntentSpec("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS")), f.apply(ActionType.OPEN_BATTERY_OPTIMIZATION_SETTINGS))
        assertTrue(f.dao.rows.value.isEmpty())
        assertEquals(ActionResult.OneShot(1), f.repo.apply(finding(Subject.Device), rec(ActionType.ENABLE_HIGH_BATTERY_ALERT)))
        assertEquals(1, f.alerts); assertEquals(ONE_SHOT, f.row().status); assertTrue(f.commands.isEmpty())
    }

    @Test fun settingsIntentsReturnWhilePrivilegedApplyIsSuspended() = runTest {
        for ((type, spec) in listOf(
            ActionType.OPEN_BATTERY_OPTIMIZATION_SETTINGS to IntentSpec("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"),
            ActionType.OPEN_APP_SETTINGS to IntentSpec("android.settings.APPLICATION_DETAILS_SETTINGS", pkg),
        )) {
            val f = Fixture()
            f.reply("active", "", "rare")
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            f.intercept = {
                if (it is PrivilegedCommand.SetStandbyBucket) {
                    entered.complete(Unit)
                    resume.await()
                }
            }
            val apply = async { f.apply(ActionType.STANDBY_BUCKET_RARE) }
            entered.await()
            val settings = async { f.apply(type) }
            runCurrent()
            try {
                assertTrue("$type must return before the privileged write resumes", settings.isCompleted)
                assertEquals(ActionResult.OpenSettings(spec), settings.await())
                assertFalse(apply.isCompleted)
                assertFalse(resume.isCompleted)
                assertEquals(2, f.commands.size)
                assertEquals(PREPARED, f.row().status)
                assertEquals(0, f.alerts)
            } finally {
                resume.complete(Unit)
            }
            assertEquals(ActionResult.Applied(1), apply.await())
        }
    }

    @Test fun appSettingsStillRejectInvalidSubjectAndPackageWithoutSideEffects() = runTest {
        val f = Fixture()
        assertEquals(ActionResult.Refused(RefusalCode.INVALID_SUBJECT), f.repo.apply(finding(Subject.Device), rec(ActionType.OPEN_APP_SETTINGS)))
        assertEquals(ActionResult.Refused(RefusalCode.INVALID_PACKAGE), f.repo.apply(finding(Subject.App(uid, "bad;pkg")), rec(ActionType.OPEN_APP_SETTINGS)))
        assertTrue(f.commands.isEmpty())
        assertTrue(f.dao.rows.value.isEmpty())
        assertEquals(0, f.alerts)
    }

    @Test fun privilegedApplyAndJournaledAlertStillWaitForInFlightApply() = runTest {
        for (type in listOf(ActionType.FORCE_STOP, ActionType.ENABLE_HIGH_BATTERY_ALERT)) {
            val f = Fixture()
            f.reply("active", "", "rare", "")
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            f.intercept = {
                if (it is PrivilegedCommand.SetStandbyBucket) {
                    entered.complete(Unit)
                    resume.await()
                }
            }
            val first = async { f.apply(ActionType.STANDBY_BUCKET_RARE) }
            entered.await()
            val second = async { f.apply(type) }
            runCurrent()
            try {
                assertFalse("$type must wait for the privileged write and readback", second.isCompleted)
                assertEquals(2, f.commands.size)
                assertEquals(PREPARED, f.row().status)
                assertEquals(0, f.alerts)
            } finally {
                resume.complete(Unit)
            }
            assertEquals(ActionResult.Applied(1), first.await())
            assertEquals(ActionResult.OneShot(2), second.await())
            assertEquals(listOf(APPLIED, ONE_SHOT), f.dao.rows.value.map { it.status })
            assertEquals(if (type == ActionType.FORCE_STOP) 4 else 3, f.commands.size)
            assertEquals(if (type == ActionType.ENABLE_HIGH_BATTERY_ALERT) 1 else 0, f.alerts)
        }
    }

    @Test fun undoRefusesNeverSentRestoreButKeepsUnknownWhenReadbackLosesAccess() = runTest {
        for (atWrite in listOf(true, false)) {
            val f = Fixture(); f.reply("active", "", "rare"); f.apply(ActionType.STANDBY_BUCKET_RARE)
            f.replies += ok("rare")
            if (!atWrite) f.replies += ok()
            f.replies += denied()
            val expected = if (atWrite) ActionResult.Refused(RefusalCode.NOT_PRIVILEGED) else ActionResult.Unknown
            assertEquals(expected, f.repo.undo(1))
            assertEquals(if (atWrite) APPLIED else UNKNOWN, f.row().status)
            assertEquals(if (atWrite) 5 else 6, f.commands.size)
        }
    }

    private suspend fun Fixture.interruptUndoAfterRestore(crash: Boolean) {
        reply("active", "", "rare")
        assertEquals(ActionResult.Applied(1), apply(ActionType.STANDBY_BUCKET_RARE))
        reply("rare", "")
        if (crash) {
            intercept = {
                if (it is PrivilegedCommand.GetStandbyBucket && commands.count { command -> command is PrivilegedCommand.GetStandbyBucket } == 4) {
                    throw IllegalStateException("simulated crash after restore")
                }
            }
            try { repo.undo(1); fail("crash required") } catch (_: IllegalStateException) { }
            intercept = {}
        } else {
            replies += denied()
            assertEquals(ActionResult.Unknown, repo.undo(1))
        }
        assertEquals(UNKNOWN, row().status)
        assertNotNull(row().appliedAt)
        assertNull(row().revertedAt)
        assertEquals(PrivilegedCommand.SetStandbyBucket(pkg, StandbyBucket.ACTIVE), commands[4])
    }

    @Test fun interruptedUndoAtPriorSettlesRevertedOnRetryWithoutMutation() = runTest {
        for (crash in listOf(false, true)) {
            val f = Fixture(); f.interruptUndoAfterRestore(crash)
            val appliedAt = f.row().appliedAt
            f.reply("active")
            assertEquals(ActionResult.Reverted, f.repo.undo(1))
            assertEquals(REVERTED, f.row().status)
            assertEquals(appliedAt, f.row().appliedAt)
            assertNotNull(f.row().revertedAt)
            assertNull(f.row().message)
            assertEquals(7, f.commands.size)
            assertEquals(2, f.commands.count { it is PrivilegedCommand.SetStandbyBucket })
        }
    }

    @Test fun interruptedUndoAtPriorReconcilesRevertedWithoutMutation() = runTest {
        for (crash in listOf(false, true)) {
            val f = Fixture(); f.interruptUndoAfterRestore(crash)
            val appliedAt = f.row().appliedAt
            f.reply("active")
            f.repo.reconcile()
            assertEquals(REVERTED, f.row().status)
            assertEquals(appliedAt, f.row().appliedAt)
            assertNotNull(f.row().revertedAt)
            assertNull(f.row().message)
            assertEquals(7, f.commands.size)
            assertEquals(2, f.commands.count { it is PrivilegedCommand.SetStandbyBucket })
            f.repo.reconcile()
            assertEquals(ActionResult.Failed(FailureCode.NOT_UNDOABLE), f.repo.undo(1))
            assertEquals(7, f.commands.size)
        }
    }

    @Test fun applyPhaseUnknownAtPriorReconcilesFailedWithoutMutation() = runTest {
        val f = Fixture(); f.reply("active", "", "garbage")
        assertEquals(ActionResult.Unknown, f.apply(ActionType.STANDBY_BUCKET_RARE))
        assertNull(f.row().appliedAt)
        f.reply("active")
        f.repo.reconcile()
        assertEquals(FAILED, f.row().status)
        assertNull(f.row().appliedAt)
        assertNull(f.row().revertedAt)
        assertEquals(4, f.commands.size)
        assertEquals(1, f.commands.count { it is PrivilegedCommand.SetStandbyBucket })
    }

    @Test fun undoReadbackMustProveRestoration() = runTest {
        for ((output, result, status) in listOf(
            Triple("garbage", ActionResult.Unknown, UNKNOWN),
            Triple("frequent", ActionResult.Unknown, UNKNOWN),
        )) {
            val f = Fixture(); f.reply("active", "", "rare"); f.apply(ActionType.STANDBY_BUCKET_RARE)
            f.reply("rare", "", output)
            assertEquals(result, f.repo.undo(1)); assertEquals(status, f.row().status)
        }
    }

    @Test fun reconcileWillNotReadFutureJournal() = runTest {
        val f = Fixture(); f.reply("active", "", "garbage"); f.apply(ActionType.STANDBY_BUCKET_RARE)
        f.dao.updateAction(f.row().copy(type = "FUTURE_ACTION"))
        f.repo.reconcile()
        assertEquals(UNKNOWN, f.row().status); assertEquals(3, f.commands.size)
    }


    @Test fun undoTargetReadbackKeepsAuthorityAndSecondUndoSucceeds() = runTest {
        val f = Fixture(); f.reply("active", "", "rare")
        assertEquals(ActionResult.Applied(1), f.apply(ActionType.STANDBY_BUCKET_RARE))
        val appliedAt = f.row().appliedAt
        f.replies.addAll(listOf(ok("rare"), failure(), ok("rare")))
        assertEquals(ActionResult.Failed(FailureCode.STATE_MISMATCH), f.repo.undo(1))
        assertEquals(APPLIED, f.row().status)
        assertEquals("STATE_MISMATCH", f.row().message)
        assertEquals("ACTIVE", f.row().priorState)
        assertEquals(appliedAt, f.row().appliedAt)
        f.reply("rare", "", "active")
        assertEquals(ActionResult.Reverted, f.repo.undo(1))
        assertEquals(REVERTED, f.row().status)
        assertNull(f.row().message)
        assertEquals(PrivilegedCommand.SetStandbyBucket(pkg, StandbyBucket.ACTIVE), f.commands[7])
    }

    @Test fun applyThirdStateRetainsUnknownUndoAuthority() = runTest {
        val f = Fixture(); f.reply("active", "", "frequent")
        assertEquals(ActionResult.Unknown, f.apply(ActionType.STANDBY_BUCKET_RARE))
        assertEquals(UNKNOWN, f.row().status)
        assertEquals("ACTIVE", f.row().priorState)
        f.reply("rare", "", "active")
        assertEquals(ActionResult.Reverted, f.repo.undo(1))
    }

    @Test fun restoreSafeRefusalsSettleReinstallAndAllowCriticalApps() = runTest {
        for (installed in listOf(uid + 1, null)) {
            val f = Fixture(); f.reply("active", "", "rare")
            f.apply(ActionType.STANDBY_BUCKET_RARE)
            f.inspector.installed = installed
            assertEquals(ActionResult.ChangedExternally(null), f.repo.undo(1))
            assertEquals(REVERTED, f.row().status)
            assertNotNull(f.row().revertedAt)
            assertEquals("CHANGED_EXTERNALLY", f.row().message)
            assertEquals(3, f.commands.size)
            for (status in listOf(PREPARED, UNKNOWN)) {
                f.dao.updateAction(f.row().copy(status = status, revertedAt = null, message = null))
                f.repo.reconcile()
                assertEquals(REVERTED, f.row().status)
                assertNotNull(f.row().revertedAt)
                assertEquals("CHANGED_EXTERNALLY", f.row().message)
                assertEquals(3, f.commands.size)
            }
        }
        for (shared in listOf(false, true)) {
            val f = Fixture(); f.reply("active", "", "rare")
            f.apply(ActionType.STANDBY_BUCKET_RARE)
            if (shared) f.inspector.packages = listOf(pkg, "com.example.other")
            else f.inspector.roles = setOf(pkg)
            f.dao.updateAction(f.row().copy(status = UNKNOWN))
            f.reply("rare")
            f.repo.reconcile()
            assertEquals(APPLIED, f.row().status)
            f.reply("rare", "", "active")
            assertEquals(ActionResult.Reverted, f.repo.undo(1))
            assertEquals(REVERTED, f.row().status)
            assertEquals(PrivilegedCommand.SetStandbyBucket(pkg, StandbyBucket.ACTIVE), f.commands[5])
        }
    }

    @Test fun undoKeepsHardSafetyRefusals() = runTest {
        for (code in listOf(RefusalCode.PROTECTED, RefusalCode.INVALID_PACKAGE, RefusalCode.INSPECTION_FAILED)) {
            val f = Fixture(); f.reply("active", "", "rare")
            f.apply(ActionType.STANDBY_BUCKET_RARE)
            when (code) {
                RefusalCode.PROTECTED -> f.dao.updateAction(f.row().copy(packageName = "com.android.systemui"))
                RefusalCode.INVALID_PACKAGE -> f.dao.updateAction(f.row().copy(packageName = "bad;pkg"))
                else -> f.inspector.inspectionFails = true
            }
            assertEquals(ActionResult.Refused(code), f.repo.undo(1))
            assertEquals(APPLIED, f.row().status)
            assertEquals(3, f.commands.size)
        }
        val f = Fixture(); f.inspector.inspectionFails = true
        assertEquals(ActionResult.Refused(RefusalCode.INSPECTION_FAILED), f.apply())
        assertTrue(f.commands.isEmpty())
        assertTrue(f.dao.rows.value.isEmpty())
    }

    @Test fun unrecognizedInitialReadFailsWithoutJournalOrMutation() = runTest {
        val f = Fixture(); f.reply("garbage")
        assertEquals(ActionResult.Failed(FailureCode.READ_FAILED), f.apply())
        assertTrue(f.dao.rows.value.isEmpty())
        assertEquals(listOf(PrivilegedCommand.GetBackgroundOp(pkg, BackgroundOp.RUN_ANY_IN_BACKGROUND)), f.commands)
    }

    @Test fun noOpAndLooseningApplyAreRefusedWithoutJournalOrMutation() = runTest {
        for ((type, output) in listOf(
            ActionType.RESTRICT_BACKGROUND to "RUN_ANY_IN_BACKGROUND: ignore",
            ActionType.STANDBY_BUCKET_RARE to "restricted",
            ActionType.STANDBY_BUCKET_RARE to "rare",
            ActionType.STANDBY_BUCKET_RESTRICTED to "restricted",
            ActionType.REMOVE_DOZE_WHITELIST to "system,$pkg,$uid",
        )) {
            val f = Fixture(); f.reply(output, "", output)
            val result = f.apply(type)
            assertTrue("Expected refusal for $type", result is ActionResult.Refused)
            assertEquals("ALREADY_AT_TARGET", (result as ActionResult.Refused).reason.name)
            assertTrue(f.dao.rows.value.isEmpty())
            assertEquals(1, f.commands.size)
        }
    }

    @Test fun unknownUndoAtPriorSettlesFailedWithoutMutation() = runTest {
        val f = Fixture(); f.reply("active", "", "garbage")
        assertEquals(ActionResult.Unknown, f.apply(ActionType.STANDBY_BUCKET_RARE))
        f.reply("active")
        assertEquals(ActionResult.Failed(FailureCode.NOT_APPLIED), f.repo.undo(1))
        assertEquals(FAILED, f.row().status)
        assertEquals("STATE_MISMATCH", f.row().message)
        assertEquals(4, f.commands.size)
        assertEquals(1, f.commands.count { it is PrivilegedCommand.SetStandbyBucket })
    }

    /** Only a restore that left the target keeps Undo, so only it may promise "Try Undo again". */
    @Test fun nonUndoableFailuresReportNotAppliedWhileRestoreLeftTargetKeepsStateMismatch() = runTest {
        val applyAtPrior = Fixture(); applyAtPrior.reply("active", "", "active")
        assertEquals(ActionResult.Failed(FailureCode.NOT_APPLIED), applyAtPrior.apply(ActionType.STANDBY_BUCKET_RARE))
        assertEquals(FAILED, applyAtPrior.row().status)
        assertEquals(ActionResult.Failed(FailureCode.NOT_UNDOABLE), applyAtPrior.repo.undo(1))

        val unknownUndoAtPrior = Fixture(); unknownUndoAtPrior.replies.addAll(listOf(ok("active"), ok(), failure()))
        assertEquals(ActionResult.Unknown, unknownUndoAtPrior.apply(ActionType.STANDBY_BUCKET_RARE))
        unknownUndoAtPrior.reply("active")
        assertEquals(ActionResult.Failed(FailureCode.NOT_APPLIED), unknownUndoAtPrior.repo.undo(1))
        assertEquals(FAILED, unknownUndoAtPrior.row().status)
        assertEquals(ActionResult.Failed(FailureCode.NOT_UNDOABLE), unknownUndoAtPrior.repo.undo(1))

        val restoreLeftTarget = Fixture(); restoreLeftTarget.reply("active", "", "rare")
        assertEquals(ActionResult.Applied(1), restoreLeftTarget.apply(ActionType.STANDBY_BUCKET_RARE))
        restoreLeftTarget.reply("rare", "", "rare")
        assertEquals(ActionResult.Failed(FailureCode.STATE_MISMATCH), restoreLeftTarget.repo.undo(1))
        assertEquals(APPLIED, restoreLeftTarget.row().status)
    }

    @Test fun concurrentApplyAndUndoAreSerializedAcrossPreparationAndReadback() = runTest {
        val f = Fixture(); f.reply("active", "", "rare", "rare", "", "active")
        val entered = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        f.intercept = { if (it is PrivilegedCommand.SetStandbyBucket && it.bucket == StandbyBucket.RARE) { entered.complete(Unit); resume.await() } }
        val apply = async { f.apply(ActionType.STANDBY_BUCKET_RARE) }
        entered.await()
        val undo = async { f.repo.undo(1) }; runCurrent()
        assertFalse(undo.isCompleted); assertEquals(2, f.commands.size); assertEquals(PREPARED, f.row().status)
        resume.complete(Unit)
        assertEquals(ActionResult.Applied(1), apply.await())
        assertEquals(ActionResult.Reverted, undo.await())
        assertEquals(REVERTED, f.row().status)
    }
}
