package com.akane.voltwise.battery.shizuku

import android.os.Binder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import com.akane.voltwise.battery.actions.CommandPolicy
import com.akane.voltwise.battery.util.CommandOutput
import com.akane.voltwise.battery.util.CommandProtocol
import java.util.concurrent.Semaphore
import java.util.concurrent.ConcurrentHashMap

/** Only CommandPolicy's diagnostic and action templates are exposed by the privileged helper. */
class ShellUserService : Binder() {
    companion object {
        const val TRANSACTION_RUN_PIPE = 2
        const val TRANSACTION_CANCEL = 3
        // Fixed helper-owned errors carried by CommandProtocol: neither refusal starts a process.
        internal const val NOT_STARTED_UNSUPPORTED = "NOT_STARTED:UNSUPPORTED_COMMAND"
        internal const val NOT_STARTED_BUSY = "NOT_STARTED:HELPER_BUSY"
        // Shizuku's USER_SERVICE_TRANSACTION_destroy (restricted to its library group; ShellUserServiceTest pins it).
        const val TRANSACTION_DESTROY = 16777115
        internal fun allows(command: String): Boolean = CommandPolicy.allows(command.split(' '))
    }

    private val permits = Semaphore(1)
    private data class Request(val uid: Int, val worker: Thread)
    private val requests = ConcurrentHashMap<Long, Request>()

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            TRANSACTION_RUN_PIPE -> {
                val command = data.readString().orEmpty()
                val timeout = data.readLong().coerceIn(1_000L, 30_000L)
                val requestId = data.readLong()
                val caller = Binder.getCallingUid()
                val descriptor = ParcelFileDescriptor.CREATOR.createFromParcel(data)
                reply?.writeInt(1)
                val worker = Thread {
                    ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                        val acquired = permits.tryAcquire()
                        try {
                            val result = when {
                                !allows(command) -> CommandOutput.Result(error = NOT_STARTED_UNSUPPORTED)
                                !acquired -> CommandOutput.Result(error = NOT_STARTED_BUSY)
                                else -> CommandOutput.run(command.split(' '), timeout)
                            }
                            CommandProtocol.write(output, result)
                        } catch (_: Exception) {
                            // A closed client pipe cancels delivery; no partial result is valid.
                        } finally {
                            if (acquired) permits.release()
                            requests.remove(requestId)
                        }
                    }
                }.apply { isDaemon = true; name = "batstats-shell" }
                if (requests.putIfAbsent(requestId, Request(caller, worker)) == null) worker.start()
                else descriptor.close()
                return true
            }
            TRANSACTION_CANCEL -> {
                val requestId = data.readLong()
                requests[requestId]?.takeIf { it.uid == Binder.getCallingUid() }?.worker?.interrupt()
                return true
            }
            TRANSACTION_DESTROY -> {
                requests.values.forEach { it.worker.interrupt() }
                Runtime.getRuntime().halt(0)
                return true
            }
            else -> return super.onTransact(code, data, reply, flags)
        }
    }
}
