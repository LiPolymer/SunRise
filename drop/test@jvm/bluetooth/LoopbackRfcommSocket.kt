package ink.lipoly.app.sunrise.bluetooth

import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

/** In-memory socket boundary for the production RFCOMM lifecycle, not a stream implementation. */
internal class LoopbackRfcommSocket(
    val serviceUuid: String,
    private val onWrite: (ByteArray) -> Unit = {},
) : RfcommSocketIo {
    private val monitor = Any()
    private val inbound = ArrayDeque<Byte>()
    private val outbound = mutableListOf<ByteArray>()
    private var connected = false
    private var closed = false

    val connectCount = AtomicInteger()
    val closeCount = AtomicInteger()
    val readCount = AtomicInteger()
    val flushCount = AtomicInteger()
    @Volatile var beforeConnect: (() -> Unit)? = null
    @Volatile var beforeAvailable: (() -> Unit)? = null
    @Volatile var beforeRead: (() -> Unit)? = null
    @Volatile var beforeWrite: (() -> Unit)? = null
    @Volatile var connectFailure: IOException? = null
    @Volatile var readFailure: IOException? = null
    @Volatile var writeFailure: IOException? = null

    val isClosed: Boolean get() = synchronized(monitor) { closed }
    val bufferedBytes: Int get() = synchronized(monitor) { inbound.size }
    val writes: List<ByteArray> get() = synchronized(monitor) { outbound.map { it.copyOf() } }

    /** Enqueue one raw segment; consecutive calls do not impose frame boundaries. */
    fun receive(bytes: ByteArray) = synchronized(monitor) {
        check(!closed) { "Socket is closed" }
        bytes.forEach { inbound.addLast(it) }
    }

    override fun connect() {
        connectCount.incrementAndGet()
        beforeConnect?.invoke()
        connectFailure?.let { throw it }
        synchronized(monitor) {
            if (closed) throw IOException("Socket is closed")
            connected = true
        }
    }

    override fun available(): Int {
        beforeAvailable?.invoke()
        return synchronized(monitor) { requireConnected(); inbound.size }
    }

    override fun read(buffer: ByteArray): Int {
        readCount.incrementAndGet()
        beforeRead?.invoke()
        readFailure?.let { throw it }
        return synchronized(monitor) {
            requireConnected()
            val count = minOf(buffer.size, inbound.size)
            repeat(count) { buffer[it] = inbound.removeFirst() }
            count
        }
    }

    override fun write(bytes: ByteArray) {
        beforeWrite?.invoke()
        writeFailure?.let { throw it }
        synchronized(monitor) {
            requireConnected()
            outbound.add(bytes.copyOf())
        }
        onWrite(bytes.copyOf())
    }

    override fun flush() {
        synchronized(monitor) { requireConnected() }
        flushCount.incrementAndGet()
    }

    override fun close() {
        closeCount.incrementAndGet()
        synchronized(monitor) { closed = true; connected = false }
    }

    private fun requireConnected() {
        if (!connected || closed) throw IOException("Socket is not connected")
    }
}
