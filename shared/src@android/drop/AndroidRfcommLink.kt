package ink.lipoly.app.sunrise.drop

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

internal class AndroidRfcommLink(
    private val device: BluetoothDevice,
    private val scope: CoroutineScope,
    private val onGaia: (ByteArray) -> Unit,
    private val onLost: () -> Unit,
) {
    companion object {
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }
    private val writer = Mutex()
    private val guard = Any()
    private val framer = GaiaRfcommFramer()
    private val probeReplies = Channel<GaiaPacket>(Channel.UNLIMITED)
    private var socket: BluetoothSocket? = null
    private var reader: Job? = null
    @Volatile private var closed = false
    @Volatile private var ready = false
    @Volatile private var frameMode = 4 // 4, 3 or 0 (bare)

    suspend fun open() {
        try {
            val s = withContext(Dispatchers.IO) {
                val created = device.createRfcommSocketToServiceRecord(SPP_UUID)
                synchronized(guard) {
                    if (closed) {
                        created.close()
                        throw DropException.Disconnected()
                    }
                    socket = created
                }
                created.connect()
                if (closed) throw DropException.Disconnected()
                created
            }
            reader = scope.launch(Dispatchers.IO) { readLoop(s) }
            var confirmed = false
            for (mode in listOf(4, 3, 0)) {
                frameMode = mode
                write(GaiaCodec.encode(GaiaCommand(GaiaIds.BASIC, GaiaIds.Basic.VERSION)))
                val reply = withTimeoutOrNull(3_500) {
                    while (true) {
                        val packet = probeReplies.receive()
                        if (packet.vendor == 0x001D && packet.feature == GaiaIds.BASIC &&
                            packet.command == GaiaIds.Basic.VERSION) break
                    }
                    true
                }
                if (reply == true) { confirmed = true; break }
            }
            if (!confirmed) throw DropException.Transport("RFCOMM connected but GAIA did not answer")
            ready = true
        } catch (e: Throwable) {
            close()
            throw when (e) {
                is DropException -> e
                is CancellationException -> e
                is SecurityException -> DropException.MissingPermission(setOf("BLUETOOTH_CONNECT"))
                else -> DropException.Transport("RFCOMM connection failed", e)
            }
        }
    }

    suspend fun write(pdu: ByteArray) = writer.withLock {
        val s = socket ?: throw DropException.Disconnected()
        val bytes = if (frameMode == 0) pdu else wrap(pdu, frameMode)
        try {
            withContext(Dispatchers.IO) {
                s.outputStream.write(bytes)
                s.outputStream.flush()
            }
        } catch (e: Exception) { throw DropException.Transport("RFCOMM write failed", e) }
    }

    private fun wrap(pdu: ByteArray, version: Int): ByteArray {
        val length = (pdu.size - 4).coerceAtLeast(0)
        if (length > 65535 || (version < 4 && length > 255))
            throw DropException.Protocol("GAIA RFCOMM frame is too large")
        val wide = version >= 4 && length > 255
        val header = if (wide)
            byteArrayOf(0xff.toByte(), version.toByte(), 2, (length shr 8).toByte(), length.toByte())
        else byteArrayOf(0xff.toByte(), version.toByte(), 0, length.toByte())
        return header + pdu
    }

    private suspend fun readLoop(s: BluetoothSocket) {
        val buffer = ByteArray(512)
        try {
            val input = s.inputStream
            while (!closed) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                deliver(framer.feed(buffer.copyOf(count)))
                while (input.available() > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size, input.available()))
                    if (n <= 0) break
                    deliver(framer.feed(buffer.copyOf(n)))
                }
                delay(45)
                deliver(framer.feed(byteArrayOf(), endOfBurst = true))
            }
        } catch (_: Exception) {
            // The owner receives the disconnect below.
        } finally {
            if (!closed) onLost()
        }
    }

    private fun deliver(pdus: List<ByteArray>) {
        for (pdu in pdus) {
            val packet = GaiaCodec.decode(pdu) ?: continue
            if (!ready) probeReplies.trySend(packet)
            else onGaia(pdu)
        }
    }

    fun close() {
        val previous = synchronized(guard) {
            if (closed) return
            closed = true
            socket.also { socket = null }
        }
        ready = false
        reader?.cancel()
        runCatching { previous?.close() }
        probeReplies.close()
    }
}
