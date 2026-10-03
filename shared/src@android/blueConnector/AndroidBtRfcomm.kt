package ink.lipoly.app.sunrise.blueConnector

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Android RFCOMM 通道：按 SPP 服务记录（UUID 0x1101）解析通道并连接，SDP、RFCOMM 组帧、
 * 流控与校验由系统栈完成，本类只负责 socket 生命周期与字节搬运。
 *
 * 同一实例的打开/写入/关闭串行；打开与写入在 IO 分发器执行。取消打开或写入会关闭已创建的
 * socket，避免留下半开连接；[close] 幂等且不抛平台异常。持有本类不等于对端提供 GAIA。
 * 写入前丢弃已缓冲的入站字节：本层只做写通道，不解析设备回复，也不能据此报告已应用。
 */
internal class AndroidBtRfcomm(
    private val owner: AndroidBtManager,
    private val nativeDevice: BluetoothDevice,
    override val address: String,
) : BtRfcomm {
    private val gate = Mutex()
    private var socket: BluetoothSocket? = null

    override suspend fun open() {
        gate.withLock {
            if (socket != null) return
            owner.requireBluetooth()
            val created = owner.platformOperation("create RFCOMM socket") {
                nativeDevice.createRfcommSocketToServiceRecord(SPP)
            }
            try {
                withContext(Dispatchers.IO) {
                    owner.platformOperation("connect RFCOMM channel") { created.connect() }
                }
            } catch (e: Exception) {
                created.closeQuietly()
                throw e
            }
            socket = created
            trace("connect")
        }
    }

    override suspend fun write(bytes: ByteArray) {
        gate.withLock {
            val target = socket ?: throw BtException.UnsupportedOperation("RFCOMM channel is not open")
            withContext(Dispatchers.IO) {
                // 入站字节只是设备回复，本层不解析；丢弃尽力而为，读取失败不改变写入判定。
                runCatching {
                    val buffered = target.inputStream.available()
                    if (buffered > 0) target.inputStream.read(ByteArray(buffered))
                }
                try {
                    owner.platformOperation("write RFCOMM channel") {
                        target.outputStream.write(bytes)
                        target.outputStream.flush()
                    }
                    trace("tx", bytes.size)
                } catch (e: Exception) {
                    // 失败后不保留可疑 socket：下一次写入重新连接，而不是持续返回同一种失败。
                    if (socket === target) socket = null
                    target.closeQuietly()
                    traceFailure(bytes, e)
                    throw e
                }
            }
        }
    }

    override suspend fun close() {
        val target = gate.withLock { socket.also { socket = null } } ?: return
        withContext(Dispatchers.IO) { target.closeQuietly() }
    }

    /** 管理器同步关闭时使用：不发挂起、不等待写入，直接释放 socket。 */
    internal fun closeBlocking() {
        val target = socket ?: return
        socket = null
        target.closeQuietly()
    }

    private fun BluetoothSocket.closeQuietly() {
        runCatching { close() }
    }

    /** 写失败默认不输出；`adb shell setprop log.tag.SunRiseRfcomm DEBUG` 打开，记录完整原因链。 */
    private fun traceFailure(bytes: ByteArray, error: Exception) {
        if (!Log.isLoggable(TRACE_TAG, Log.DEBUG)) return
        val chain = generateSequence(error as Throwable?) { it.cause }.take(4)
            .joinToString(" <- ") { "${it.javaClass.simpleName}(${it.message})" }
        Log.d(TRACE_TAG, "event=tx-fail peer=$address bytes=${bytes.size} error=$chain")
    }

    /** 连接与写入成功也只在标签开启时输出，用于分辨是首帧失败、重连失败还是写入失败。 */
    private fun trace(event: String, bytes: Int? = null) {
        if (!Log.isLoggable(TRACE_TAG, Log.DEBUG)) return
        Log.d(TRACE_TAG, "event=$event peer=$address" + (bytes?.let { " bytes=$it" } ?: ""))
    }

    private companion object {
        /** 经典通道诊断标签；消息不含完整 payload，只含长度与失败原因。 */
        const val TRACE_TAG = "SunRiseRfcomm"

        /** 经典 SPP 服务类；对端 SDP 记录用该 UUID 注册并给出 RFCOMM 通道号。 */
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
