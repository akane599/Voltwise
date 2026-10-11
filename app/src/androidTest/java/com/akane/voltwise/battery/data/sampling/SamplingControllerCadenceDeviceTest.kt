package com.akane.voltwise.battery.data.sampling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akane.voltwise.battery.data.db.BatterySample
import com.akane.voltwise.battery.diagnostics.DiagnosticStore
import com.akane.voltwise.battery.measurement.SamplingPolicy
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real HandlerThread/BatteryManager/PowerManager; only unsolicited event registration is isolated. */
@RunWith(AndroidJUnit4::class)
class SamplingControllerCadenceDeviceTest {
    private class ReadingContext(base: Context) : ContextWrapper(base) {
        val directory = File(base.cacheDir, "cadence-${UUID.randomUUID()}").apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = directory
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?,
            broadcastPermission: String?, scheduler: Handler?, flags: Int): Intent? = null
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?,
            broadcastPermission: String?, scheduler: Handler?): Intent? = null
        override fun unregisterReceiver(receiver: BroadcastReceiver?) = Unit
    }

    @Test fun screenOnLastReleaseAfterStampProducesImmediateHandoff() = releaseAfterStamp(screenOn = true)
    @Test fun screenOffLastReleaseAfterStampProducesImmediateHandoff() = releaseAfterStamp(screenOn = false)

    private fun releaseAfterStamp(screenOn: Boolean): Unit = runBlocking {
        val base: Context = ApplicationProvider.getApplicationContext()
        val power = base.getSystemService(PowerManager::class.java)
        assumeTrue("Run this case with the physical screen ${if (screenOn) "on" else "off"}", power.isInteractive == screenOn)
        val context = ReadingContext(base)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sampler = SamplingController(context, DiagnosticStore(context, scope))
        val captures = LinkedBlockingQueue<SamplerCapture>()
        val release = AtomicBoolean(true)
        val token = sampler.acquire("cadence-device-test")
        sampler.attach(object : SamplerSink {
            override fun onReading(sample: BatterySample, expectedIntervalMs: Long) = Unit
            override fun onObserved(capture: SamplerCapture): Boolean {
                assertEquals(SamplingController.THREAD_NAME, Thread.currentThread().name)
                captures.add(capture)
                // The point is already stamped; the caller has not scheduled its next poll yet.
                if (release.compareAndSet(true, false)) { token.close(); token.close() }
                return true
            }
            override fun onBatteryIssue(message: String?) = Unit
            override fun onStateEventsIssue(message: String?) = Unit
        })
        try {
            // A sampler-thread barrier drains acquire; this is realtime-only, not observed.
            assertNotNull(withTimeout(5_000) { sampler.readOnce() })
            assertTrue(captures.isEmpty())
            sampler.startMonitoring("cadence-generation")
            val first = captures.poll(5, TimeUnit.SECONDS)
            assertNotNull("start capture must arrive", first)
            val handoff = captures.poll(1, TimeUnit.SECONDS)
            assertNotNull("queued release must hand off before the 2s poll", handoff)
            val expected = SamplingPolicy.expectedIntervalMs(false, screenOn)
            assertEquals(2_000L, first!!.point.expectedIntervalMs)
            assertEquals(expected, handoff!!.point.expectedIntervalMs)
            assertEquals(screenOn, first.point.interactive)
            assertEquals(screenOn, handoff.point.interactive)
            assertEquals("cadence-generation", handoff.point.generation)
            assertTrue(handoff.poll)
            sampler.stopMonitoring()
            assertNotNull(withTimeout(5_000) { sampler.readOnce() })
            captures.clear()
            val nextToken = sampler.acquire("monitoring-off")
            try {
                assertNotNull(withTimeout(5_000) { sampler.refresh() })
                assertTrue("monitoring-off captures never reach observation", captures.isEmpty())
            } finally { nextToken.close() }
        } finally {
            token.close()
            sampler.shutdown()
            scope.coroutineContext[Job]!!.cancelAndJoin()
            context.directory.deleteRecursively()
        }
    }
}
