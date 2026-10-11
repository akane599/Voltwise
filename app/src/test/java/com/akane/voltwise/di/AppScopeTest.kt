package com.akane.voltwise.di

import com.akane.voltwise.battery.diagnostics.DiagnosticCode
import com.akane.voltwise.battery.diagnostics.DiagnosticLog
import com.akane.voltwise.viewmodel.StatusViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppScopeTest {
    @Test fun uncaughtFailureRecordsOnlyFixedCodeAndKeepsScopeAlive() = runBlocking {
        val log = DiagnosticLog()
        val scope = createAppScope { code -> log.record(code, 123L) }
        try {
            withTimeout(30_000) {
                scope.launch { throw IllegalStateException("private exception message") }.join()
                assertEquals(DiagnosticCode.APP_SCOPE_FAILED, log.snapshot().single().code)
                val encoded = DiagnosticLog.encode(log.snapshot())
                assertFalse(encoded.contains("private exception message"))
                assertFalse(encoded.contains("IllegalStateException"))
                assertEquals(log.snapshot(), DiagnosticLog.decode(encoded))
                assertTrue(StatusViewModel.issues(log.snapshot()).isEmpty())
                assertTrue(scope.isActive)
                var siblingRan = false
                scope.launch { siblingRan = true }.join()
                assertTrue(siblingRan)
            }
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test fun cancellationDoesNotRecordFailure() = runBlocking {
        val codes = mutableListOf<DiagnosticCode>()
        val scope = createAppScope(codes::add)
        try {
            withTimeout(30_000) {
                scope.launch { throw CancellationException("normal cancellation") }.join()
                assertTrue(codes.isEmpty())
                assertTrue(scope.isActive)
            }
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }
}
