package ink.lipoly.app.sunrise.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.Dispatchers
import java.util.UUID

/**
 * Android RFCOMM 字节通道，按调用方指定的完整 UUID 查询服务记录。
 * 系统负责 SDP、RFCOMM 组帧与流控；共享 [RfcommChannel] 负责单 socket 生命周期与输入策略。
 */
internal class AndroidBtRfcomm(
    private val owner: AndroidBtManager,
    private val nativeDevice: BluetoothDevice,
    override val address: String,
) : RfcommStream {
    private val channel = RfcommChannel(address, Dispatchers.IO) { normalizedUuid ->
        owner.enforceBluetoothPermission()
        val socket = owner.platformOperation("create RFCOMM socket") {
            nativeDevice.createRfcommSocketToServiceRecord(UUID.fromString(normalizedUuid))
        }
        AndroidSocketIo(socket)
    }

    override suspend fun open(serviceUuid: String, inputPolicy: RfcommInputPolicy) =
        channel.open(serviceUuid, inputPolicy)

    override suspend fun write(bytes: ByteArray) = channel.write(bytes)

    override suspend fun read(): ByteArray = channel.read().also { trace("rx", it.size) }

    override suspend fun close() = channel.close()

    /** 管理器同步关闭时使用，不等待连接/写入 gate。 */
    internal fun closeBlocking() = channel.closeBlocking()

    /** 平台分类留在实际 I/O 边界；共享生命周期不猜测权限或传输错误。 */
    private inner class AndroidSocketIo(private val socket: BluetoothSocket) : RfcommSocketIo {
        private var writeByteCount = 0

        override fun connect() {
            owner.platformOperation("connect RFCOMM channel") { socket.connect() }
            trace("connect")
        }

        override fun available(): Int =
            owner.platformOperation("read RFCOMM channel") { socket.inputStream.available() }

        override fun read(buffer: ByteArray): Int =
            owner.platformOperation("read RFCOMM channel") { socket.inputStream.read(buffer) }

        override fun write(bytes: ByteArray) {
            writeByteCount = bytes.size
            try {
                owner.platformOperation("write RFCOMM channel") { socket.outputStream.write(bytes) }
            } catch (error: Exception) {
                traceFailure(writeByteCount, error)
                throw error
            }
        }

        override fun flush() {
            try {
                owner.platformOperation("write RFCOMM channel") { socket.outputStream.flush() }
                trace("tx", writeByteCount)
            } catch (error: Exception) {
                traceFailure(writeByteCount, error)
                throw error
            }
        }

        override fun close() = socket.close()
    }

    /** 写失败默认不输出；`adb shell setprop log.tag.SunRiseRfcomm DEBUG` 打开，记录完整原因链。 */
    private fun traceFailure(bytes: Int, error: Exception) {
        if (!Log.isLoggable(TRACE_TAG, Log.DEBUG)) return
        val chain = generateSequence(error as Throwable?) { it.cause }.take(4)
            .joinToString(" <- ") { "${it.javaClass.simpleName}(${it.message})" }
        Log.d(TRACE_TAG, "event=tx-fail peer=$address bytes=$bytes error=$chain")
    }

    /** 诊断只记录事件、长度与失败原因，不记录 payload。 */
    private fun trace(event: String, bytes: Int? = null) {
        if (!Log.isLoggable(TRACE_TAG, Log.DEBUG)) return
        Log.d(TRACE_TAG, "event=$event peer=$address" + (bytes?.let { " bytes=$it" } ?: ""))
    }

    private companion object {
        const val TRACE_TAG = "SunRiseRfcomm"
    }
}
