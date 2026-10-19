package ink.lipoly.app.sunrise.bluetooth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Blocking socket operations; platform adapters own permission checks and error classification. */
internal interface RfcommSocketIo {
    fun connect()
    fun available(): Int
    fun read(buffer: ByteArray): Int
    fun write(bytes: ByteArray)
    fun flush()
    fun close()
}

/**
 * One explicitly configured RFCOMM socket, shared by platform I/O and deterministic byte-stream tests.
 * Open/write share a gate; reads only hold their socket's own mutex while waiting for incoming bytes.
 */
internal class RfcommChannel(
    override val address: String,
    private val ioDispatcher: CoroutineDispatcher,
    private val createSocket: (normalizedUuid: String) -> RfcommSocketIo,
) : RfcommStream {
    private class Entry(
        val socket: RfcommSocketIo,
        val serviceUuid: String,
        val inputPolicy: RfcommInputPolicy,
    ) {
        val readGate = Mutex()
    }

    /** A revision also invalidates opens that have not finished creating their socket yet. */
    private data class Lifecycle(
        val revision: Long = 0,
        val entry: Entry? = null,
        val connected: Boolean = false,
    )

    private val gate = Mutex()
    private val lifecycle = MutableStateFlow(Lifecycle())

    override suspend fun open(serviceUuid: String, inputPolicy: RfcommInputPolicy) {
        val normalizedUuid = normalizeUuid(serviceUuid)
        gate.withLock {
            currentCoroutineContext().ensureActive()
            val previous = lifecycle.value
            previous.entry?.let { existing ->
                check(previous.connected && existing.serviceUuid == normalizedUuid && existing.inputPolicy == inputPolicy) {
                    "RFCOMM service or input policy differs; close the channel before reopening"
                }
                return
            }

            // Assign inside the IO block: prompt cancellation on its return must not lose the resource.
            var created: Entry? = null
            var pending: Lifecycle? = null
            try {
                withContext(ioDispatcher) {
                    val entry = Entry(createSocket(normalizedUuid), normalizedUuid, inputPolicy)
                    created = entry
                    val opening = Lifecycle(previous.revision, entry)
                    if (!lifecycle.compareAndSet(previous, opening)) throw BtException.Disconnected()
                    pending = opening
                    currentCoroutineContext().ensureActive()
                    entry.socket.connect()
                }
                currentCoroutineContext().ensureActive()
                val opening = checkNotNull(pending)
                if (!lifecycle.compareAndSet(opening, opening.copy(connected = true))) {
                    throw BtException.Disconnected()
                }
                currentCoroutineContext().ensureActive()
            } catch (error: Throwable) {
                created?.let { entry ->
                    // An uninstalled socket is still ours; an installed one may already belong to close.
                    if (pending == null || retire(entry)) closeOnIo(entry)
                }
                throw error
            }
        }
    }

    override suspend fun write(bytes: ByteArray) {
        gate.withLock {
            val target = openEntry()
            try {
                withContext(ioDispatcher) {
                    requireCurrent(target)
                    if (target.inputPolicy == RfcommInputPolicy.DRAIN_BEFORE_WRITE && target.readGate.tryLock()) {
                        try {
                            drainAvailable(target)
                        } finally {
                            target.readGate.unlock()
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    requireCurrent(target)
                    target.socket.write(bytes)
                    target.socket.flush()
                }
                // Covers cancellation delivered while dispatching the successful IO result back.
                currentCoroutineContext().ensureActive()
                requireCurrent(target)
            } catch (error: Throwable) {
                if (retire(target)) closeOnIo(target)
                throw error
            }
        }
    }

    override suspend fun read(): ByteArray {
        val target = openEntry()
        target.readGate.lock()
        try {
            while (true) {
                requireCurrent(target)
                val chunk = try {
                    withContext(ioDispatcher) {
                        requireCurrent(target)
                        readAvailable(target)
                    }
                } catch (error: CancellationException) {
                    // Cancelling a reader only releases its place in the input queue.
                    throw error
                } catch (error: Throwable) {
                    // A late failure on a retired socket must not clear a replacement connection.
                    if (!retire(target)) throw BtException.Disconnected()
                    closeOnIo(target)
                    throw error
                }
                requireCurrent(target)
                if (chunk != null) return chunk
                delay(READ_POLL_MILLIS)
            }
        } finally {
            target.readGate.unlock()
        }
    }

    private fun openEntry(): Entry {
        val current = lifecycle.value
        if (!current.connected) throw BtException.UnsupportedOperation("RFCOMM channel is not open")
        return checkNotNull(current.entry)
    }

    private fun requireCurrent(target: Entry) {
        val current = lifecycle.value
        if (!current.connected || current.entry !== target) throw BtException.Disconnected()
    }

    /** Retire only this socket, never a later connection, and claim responsibility for closing it. */
    private fun retire(target: Entry): Boolean {
        while (true) {
            val previous = lifecycle.value
            if (previous.entry !== target) return false
            if (lifecycle.compareAndSet(previous, Lifecycle(previous.revision + 1))) return true
        }
    }

    /** Detaching an empty state still invalidates a socket currently being created on the IO dispatcher. */
    private fun detach(): Entry? {
        while (true) {
            val previous = lifecycle.value
            if (lifecycle.compareAndSet(previous, Lifecycle(previous.revision + 1))) return previous.entry
        }
    }

    private fun readAvailable(target: Entry): ByteArray? {
        val available = target.socket.available()
        if (available <= 0) return null
        val buffer = ByteArray(minOf(available, MAX_READ))
        val count = target.socket.read(buffer)
        return when {
            count <= 0 -> null
            count == buffer.size -> buffer
            else -> buffer.copyOf(count)
        }
    }

    /** Best effort, bounded by the initial buffered count; never blocks a reader waiting on readGate. */
    private suspend fun drainAvailable(target: Entry) {
        try {
            var remaining = target.socket.available()
            if (remaining <= 0) return
            val buffer = ByteArray(minOf(remaining, MAX_READ))
            while (remaining > 0) {
                currentCoroutineContext().ensureActive()
                // closeBlocking may run from another thread despite the open/write gate.
                requireCurrent(target)
                val count = target.socket.read(buffer)
                if (count <= 0) return
                remaining -= count
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Discarding stale input is optional; its failure is not a failed command write.
        }
    }

    override suspend fun close() {
        val target = detach() ?: return
        closeOnIo(target)
    }

    /** Manager shutdown cannot suspend or wait for an in-flight connect/write to leave the gate. */
    internal fun closeBlocking() {
        val target = detach() ?: return
        runCatching { target.socket.close() }
    }

    private suspend fun closeOnIo(target: Entry) {
        withContext(NonCancellable + ioDispatcher) {
            runCatching { target.socket.close() }
        }
    }

    private companion object {
        const val MAX_READ = 1024
        const val READ_POLL_MILLIS = 10L

        fun normalizeUuid(value: String): String {
            require(value.length == 36) { "RFCOMM service UUID must use the full 8-4-4-4-12 hexadecimal form" }
            for (index in value.indices) {
                val character = value[index]
                val valid = if (index == 8 || index == 13 || index == 18 || index == 23) {
                    character == '-'
                } else {
                    character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'
                }
                require(valid) { "RFCOMM service UUID must use the full 8-4-4-4-12 hexadecimal form" }
            }
            return value.lowercase()
        }
    }
}
