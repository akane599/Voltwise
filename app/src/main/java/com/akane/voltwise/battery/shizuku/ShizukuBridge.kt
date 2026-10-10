package com.akane.voltwise.battery.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs
import rikka.shizuku.ShizukuProvider
import com.akane.voltwise.battery.util.CommandProtocol
import com.akane.voltwise.battery.util.CommandOutput
import com.akane.voltwise.battery.util.ExecutionCertainty
import com.akane.voltwise.battery.util.ExecutionPolicy
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.suspendCancellableCoroutine

internal fun shizukuPermissionBlocked(
    running: Boolean,
    preV11: Boolean,
    granted: Boolean,
    rationale: Boolean,
): Boolean = running && !preV11 && !granted && rationale

class ShizukuBridge(private val context: Context) {

    companion object {
        private const val TAG = "ShizukuBridge"

        const val PERMISSION_REQUEST_CODE = 1001

        private const val SERVICE_VERSION = 10

        private const val BIND_TIMEOUT_MS = 10_000L
        private const val DEFAULT_CMD_TIMEOUT_MS = 25_000L

        private const val READ_GRACE_MS = 5_000L


        private const val PING_RETRIES = 4
        private const val PING_RETRY_DELAY_MS = 120L

        /** The helper process is stopped after this long with no command in flight; the next command rebinds. */
        const val IDLE_UNBIND_MS = 60_000L
    }

    enum class Failure { NOT_RUNNING, NO_PERMISSION, BIND_FAILED, TRANSPORT, COMMAND }

    sealed class RunResult {
        data class Success(val output: String) : RunResult()
        data class Error(
            val message: String,
            val reason: Failure,
            val certainty: ExecutionCertainty = ExecutionCertainty.CONFIRMED,
        ) : RunResult()
    }

    private val requestIds = AtomicLong(SystemClock.elapsedRealtimeNanos())
    private val binding = HelperBinding<IBinder> { it.isBinderAlive }
    private val bindMutex = Mutex()
    private val listenersRegistered = AtomicBoolean(false)

    @Volatile
    private var everSeen = false

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _granted = MutableStateFlow(false)
    val granted: StateFlow<Boolean> = _granted.asStateFlow()

    private val _blocked = MutableStateFlow(false)
    val blocked: StateFlow<Boolean> = _blocked.asStateFlow()

    private val args by lazy {
        UserServiceArgs(ComponentName(context.packageName, ShellUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("shz")
            .tag("ShellSvc")
            .version(SERVICE_VERSION)
    }

    // On Main, like bindUserService: an idle unbind cannot interleave with a bind in progress.
    private val idle = IdleCountdown(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), IDLE_UNBIND_MS) {
        // Runs under the countdown's lock: a command that starts after this finds no binder and rebinds.
        if (binding.forget()) {
            Log.d(TAG, "UserService idle for ${IDLE_UNBIND_MS / 1000} s; unbinding")
            unbindService()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "UserService connected (alive=${service?.isBinderAlive})")
            if (service == null) {
                binding.failAttempt()
                return
            }
            binding.connected(service)
            // The notice names its binder: a helper that dies after an idle unbind cannot fail the next bind.
            runCatching { service.linkToDeath({ binding.died(service) }, 0) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "UserService disconnected")
            binding.disconnected()
        }

        override fun onBindingDied(name: ComponentName?) = onServiceDisconnected(name)
        override fun onNullBinding(name: ComponentName?) = binding.failAttempt()
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
        everSeen = true
        _running.value = true
        binding.forget()
        refreshPermissionState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder died")
        _running.value = false
        _granted.value = false
        _blocked.value = false
        binding.reset()
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == PERMISSION_REQUEST_CODE) {
                refreshPermissionState(grantResult == PackageManager.PERMISSION_GRANTED)
                Log.d(TAG, "Permission result: ${_granted.value}")
            }
        }

    fun warmUp() {
        if (!listenersRegistered.compareAndSet(false, true)) return
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not register Shizuku listeners: ${t.message}")
            listenersRegistered.set(false)
        }
    }

    fun ping(): Boolean {
        val alive = try {
            Shizuku.pingBinder()
        } catch (t: Throwable) {
            Log.d(TAG, "pingBinder threw: ${t.message}")
            false
        }
        _running.value = alive
        if (alive) {
            everSeen = true
        } else {
            _granted.value = false
            _blocked.value = false
            binding.reset()
        }
        return alive
    }

    suspend fun isRunning(): Boolean {
        if (ping()) return true
        if (!everSeen) return false
        repeat(PING_RETRIES) {
            delay(PING_RETRY_DELAY_MS)
            if (ping()) return true
        }
        Log.w(TAG, "Shizuku stopped responding")
        _running.value = false
        return false
    }

    private fun checkPermissionNow(): Boolean = try {
        if (Shizuku.isPreV11()) {
            ContextCompat.checkSelfPermission(context, ShizukuProvider.PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }
    } catch (t: Throwable) {
        Log.d(TAG, "checkSelfPermission failed: ${t.message}")
        false
    }

    fun hasPermission(): Boolean {
        if (!ping()) return false
        return refreshPermissionState()
    }

    suspend fun hasPermissionResilient(): Boolean {
        if (!isRunning()) return false
        return refreshPermissionState()
    }

    private fun refreshPermissionState(granted: Boolean = checkPermissionNow()): Boolean {
        _granted.value = granted
        _blocked.value = try {
            _running.value && !Shizuku.isPreV11() && !granted &&
                shizukuPermissionBlocked(true, false, false, Shizuku.shouldShowRequestPermissionRationale())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
        return granted
    }

    fun isPermanentlyDenied(): Boolean {
        hasPermission()
        return _blocked.value
    }

    fun requestPermission(requestCode: Int = PERMISSION_REQUEST_CODE) {
        if (!ping()) {
            Log.w(TAG, "requestPermission: Shizuku not running, ignoring")
            return
        }
        refreshPermissionState()
        if (_granted.value || _blocked.value) return
        try {
            Shizuku.requestPermission(requestCode)
            refreshPermissionState()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "requestPermission failed: ${t.message}")
        }
    }

    suspend fun run(
        cmd: String,
        timeoutMs: Long = DEFAULT_CMD_TIMEOUT_MS,
        policy: ExecutionPolicy = ExecutionPolicy.READ_ONLY,
    ): RunResult =
        withContext(Dispatchers.IO) {
            idle.begin()
            try { runCommand(cmd, timeoutMs, policy) } finally { idle.end() }
        }

    private suspend fun runCommand(cmd: String, timeoutMs: Long, policy: ExecutionPolicy): RunResult {
        if (!isRunning()) {
            return RunResult.Error("Shizuku is not running", Failure.NOT_RUNNING)
        }
        if (!hasPermissionResilient()) {
            return RunResult.Error(
                "Shizuku permission not granted",
                Failure.NO_PERMISSION
            )
        }

        val binder = ensureBound()
            ?: return RunResult.Error(
                "Could not start the Shizuku helper service",
                Failure.BIND_FAILED
            )

        return retryAfterTransportFailure({ execute(binder, cmd, timeoutMs, policy) }, policy) { failure ->
            Log.d(TAG, "Retrying after transport failure: ${failure.message}")
            binding.forget(binder)
            val fresh = ensureBound() ?: return@retryAfterTransportFailure null
            execute(fresh, cmd, timeoutMs, policy)
        }
    }

    suspend fun runOrNull(cmd: String): String? =
        (run(cmd) as? RunResult.Success)?.output

    private suspend fun execute(binder: IBinder, cmd: String, timeoutMs: Long, policy: ExecutionPolicy): RunResult {
        if (!binder.isBinderAlive) {
            return RunResult.Error("Helper service is no longer alive", Failure.TRANSPORT)
        }
        return try {
            val result = runViaPipe(binder, cmd, timeoutMs)
            currentCoroutineContext().ensureActive()
            val running = ping()
            classifyAfterRead(running, running && hasPermission(), result, policy)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "Command transport failed: ${t.message}")
            RunResult.Error(t.message ?: t.javaClass.simpleName, Failure.TRANSPORT, ExecutionCertainty.UNKNOWN)
        }
    }

    private suspend fun runViaPipe(binder: IBinder, cmd: String, timeoutMs: Long): CommandOutput.Result? {
        val pipe = ParcelFileDescriptor.createPipe()
        val requestId = requestIds.incrementAndGet()
        fun cancelRemote() {
            val data = Parcel.obtain()
            try {
                data.writeLong(requestId)
                binder.transact(ShellUserService.TRANSACTION_CANCEL, data, null, IBinder.FLAG_ONEWAY)
            } catch (_: Exception) { } finally { data.recycle() }
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
        }
        return withTimeoutOrNull(timeoutMs + READ_GRACE_MS) {
            suspendCancellableCoroutine { continuation ->
                val worker = Thread({
                    try {
                        val data = Parcel.obtain()
                        val accepted = try {
                            data.writeString(cmd); data.writeLong(timeoutMs); data.writeLong(requestId)
                            pipe[1].writeToParcel(data, 0)
                            binder.transact(ShellUserService.TRANSACTION_RUN_PIPE, data, null, IBinder.FLAG_ONEWAY)
                        } finally { data.recycle(); runCatching { pipe[1].close() } }
                        if (!continuation.isActive) { cancelRemote(); return@Thread }
                        val result = readPipeResult(accepted) {
                            ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use(CommandProtocol::read)
                        }
                        continuation.resumeWith(Result.success(result))
                    } catch (e: Exception) {
                        continuation.resumeWith(Result.failure(e))
                    } finally { runCatching { pipe[0].close() }; runCatching { pipe[1].close() } }
                }, "batstats-shizuku-pipe").apply { isDaemon = true }
                continuation.invokeOnCancellation { cancelRemote(); worker.interrupt() }
                worker.start()
            }
        } ?: CommandOutput.Result(error = "Privileged read timed out", certainty = ExecutionCertainty.UNKNOWN)
    }

    private suspend fun ensureBound(): IBinder? {
        binding.current()?.let { return it }

        return bindMutex.withLock {
            binding.current()?.let { return@withLock it }

            val attempt = binding.begin()

            val started = withContext(Dispatchers.Main) {
                try {
                    Shizuku.bindUserService(args, connection)
                    true
                } catch (t: Throwable) {
                    Log.e(TAG, "bindUserService failed", t)
                    false
                }
            }
            if (!started) {
                binding.end(attempt)
                return@withLock null
            }

            val startedAt = SystemClock.elapsedRealtime()
            val binder = try {
                withTimeoutOrNull(BIND_TIMEOUT_MS) { attempt.result.await() }
            } finally {
                binding.end(attempt)
            }

            if (binder == null || !binder.isBinderAlive) {
                Log.e(TAG, "UserService bind failed after ${SystemClock.elapsedRealtime() - startedAt} ms")
                binder?.let { binding.forget(it) }
                null
            } else {
                Log.d(TAG, "UserService bound in ${SystemClock.elapsedRealtime() - startedAt} ms")
                binder
            }
        }
    }

    suspend fun unbind() = bindMutex.withLock {
        withContext(Dispatchers.Main) {
            unbindService()
            binding.reset()
        }
    }

    /** Stops the helper and detaches its cached client connection before another bind can reuse it. */
    private fun unbindService() {
        try {
            removeHelperService { remove -> Shizuku.unbindUserService(args, connection, remove) }
        } catch (t: Throwable) {
            Log.e(TAG, "unbind failed", t)
        }
    }
}

internal fun removeHelperService(unbind: (remove: Boolean) -> Unit) {
    try {
        unbind(true)
    } finally {
        // api 13.1.5 leaves its connection cached for remove=true until a posted death callback.
        // Detach it now: that callback must not clear a subsequent bind's callbacks.
        unbind(false)
    }
}

internal fun readPipeResult(
    accepted: Boolean,
    read: () -> CommandOutput.Result,
): CommandOutput.Result =
    if (accepted) {
        val result = read()
        // Only helper-owned non-dispatch errors prove that an accepted mutation never started.
        when (result.error) {
            null, ShellUserService.NOT_STARTED_UNSUPPORTED, ShellUserService.NOT_STARTED_BUSY -> result
            else -> result.copy(certainty = ExecutionCertainty.UNKNOWN)
        }
    } else CommandOutput.Result(error = "Helper command refused")

internal fun classifyAfterRead(
    running: Boolean,
    permitted: Boolean,
    result: CommandOutput.Result?,
    policy: ExecutionPolicy = ExecutionPolicy.READ_ONLY,
): ShizukuBridge.RunResult = when {
    // A complete successful mutation response is proof even when authorization disappears afterward.
    policy == ExecutionPolicy.MUTATION && result != null && result.error == null ->
        ShizukuBridge.RunResult.Success(result.output)
    policy == ExecutionPolicy.MUTATION -> ShizukuBridge.RunResult.Error(
        result?.error ?: "Mutation response unavailable",
        when {
            !running -> ShizukuBridge.Failure.NOT_RUNNING
            !permitted -> ShizukuBridge.Failure.NO_PERMISSION
            result == null -> ShizukuBridge.Failure.TRANSPORT
            else -> ShizukuBridge.Failure.COMMAND
        },
        result?.certainty ?: ExecutionCertainty.UNKNOWN,
    )
    !running -> ShizukuBridge.RunResult.Error("Shizuku is not running", ShizukuBridge.Failure.NOT_RUNNING)
    !permitted -> ShizukuBridge.RunResult.Error("Shizuku permission not granted", ShizukuBridge.Failure.NO_PERMISSION)
    result == null -> ShizukuBridge.RunResult.Error("Helper protocol unavailable", ShizukuBridge.Failure.TRANSPORT)
    result.error != null -> ShizukuBridge.RunResult.Error(result.error, ShizukuBridge.Failure.COMMAND)
    else -> ShizukuBridge.RunResult.Success(result.output)
}

internal suspend fun retryAfterTransportFailure(
    firstExecution: suspend () -> ShizukuBridge.RunResult,
    policy: ExecutionPolicy = ExecutionPolicy.READ_ONLY,
    retry: suspend (ShizukuBridge.RunResult.Error) -> ShizukuBridge.RunResult?,
): ShizukuBridge.RunResult {
    val first = firstExecution()
    if (policy == ExecutionPolicy.MUTATION || first !is ShizukuBridge.RunResult.Error ||
        first.reason != ShizukuBridge.Failure.TRANSPORT
    ) {
        return first
    }
    return retry(first) ?: first
}

/**
 * Calls [onIdle] once [idleMs] pass with no command between [begin] and [end]; a [begin] cancels the countdown.
 * [onIdle] runs on [scope] while holding the lock [begin] takes, so a command either starts before the idle
 * check (and the countdown is skipped) or after [onIdle] finished. Keep [onIdle] short.
 */
internal class IdleCountdown(
    private val scope: CoroutineScope,
    private val idleMs: Long,
    private val onIdle: () -> Unit,
) {
    private val lock = Any()
    private var inFlight = 0
    private var countdown: Job? = null

    fun begin() = synchronized(lock) {
        inFlight++
        countdown?.cancel()
        countdown = null
    }

    fun end() = synchronized(lock) {
        inFlight = (inFlight - 1).coerceAtLeast(0)
        if (inFlight == 0) {
            countdown?.cancel()
            countdown = scope.launch {
                delay(idleMs)
                synchronized(lock) {
                    if (inFlight == 0 && countdown === coroutineContext[Job]) {
                        countdown = null
                        onIdle()
                    }
                }
            }
        }
    }
}

/**
 * The helper's binder and the bind attempt waiting for one. A death notice names its binder and only drops that
 * binder while it is still current; a disconnect notice names none, so it only drops a current binder that is
 * dead. Neither completes a waiting attempt: an attempt is answered only by the binder that connects for it
 * (or fails on [failAttempt], [reset] or its own timeout). So the late death of a helper stopped by the idle
 * unbind cannot fail, or drop the binder of, the bind that follows it. Thread-safe.
 */
internal class HelperBinding<B : Any>(private val isAlive: (B) -> Boolean) {
    /** One bind attempt; [result] completes with the binder that connects for it, or null. */
    class Attempt<B> internal constructor() {
        val result = CompletableDeferred<B?>()
    }

    private val lock = Any()
    private var binder: B? = null
    private var pending: Attempt<B>? = null

    /** The connected binder while it is alive. */
    fun current(): B? = synchronized(lock) { binder?.takeIf(isAlive) }

    fun begin(): Attempt<B> = synchronized(lock) {
        pending?.result?.complete(null)
        Attempt<B>().also { pending = it }
    }

    /** [attempt] stopped waiting (answered, timed out or never started); a newer attempt is untouched. */
    fun end(attempt: Attempt<B>) = synchronized(lock) {
        if (pending === attempt) pending = null
    }

    /** onServiceConnected: [service] becomes current and answers the waiting attempt. */
    fun connected(service: B) = synchronized(lock) {
        binder = service
        pending?.result?.complete(service)
        pending = null
    }

    /** onNullBinding or a null binder: the waiting attempt gets nothing. */
    fun failAttempt() = synchronized(lock) {
        pending?.result?.complete(null)
        pending = null
    }

    /** [service]'s death notice. */
    fun died(service: B) = synchronized(lock) {
        if (binder === service) binder = null
    }

    fun disconnected() = synchronized(lock) {
        if (binder?.let(isAlive) == false) binder = null
    }

    /** Drops the current binder ([service] only if that is still the current one); true if one was dropped. */
    fun forget(service: B? = null): Boolean = synchronized(lock) {
        val held = binder
        (held != null && (service == null || held === service)).also { if (it) binder = null }
    }

    /** Shizuku itself went away, or an explicit unbind: no binder, and the waiting attempt fails now. */
    fun reset() = synchronized(lock) {
        binder = null
        pending?.result?.complete(null)
        pending = null
    }
}
