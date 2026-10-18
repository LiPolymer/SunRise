package ink.lipoly.app.sunrise.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android RFCOMM 通道：按 SPP 服务记录（UUID 0x1101）解析通道并连接，SDP、RFCOMM 组帧、
 * 流控与校验由系统栈完成，本类只负责 socket 生命周期与字节搬运。
 *
 * 同一实例的打开/写入/关闭串行；打开与写入在 IO 分发器执行。取消打开或写入会关闭已创建的
 * socket，避免留下半开连接；[close] 幂等且不抛平台异常。持有本类不等于对端提供 GAIA。
 * 入站字节由 [read] 读取；没有活跃读取者时，写入前仍尽力排空缓冲以兼容只写用法。
 */
internal class AndroidBtRfcomm(
    private val owner: AndroidBtManager,
    private val nativeDevice: BluetoothDevice,
    override val address: String,
) : RfcommStream {
    private val gate = Mutex()
    @Volatile
    private var socket: BluetoothSocket? = null
    /** 活跃读取者数量；大于 0 时写入不再排空入站，避免吞掉协议回复。 */
    private val readers = AtomicInteger()

    override suspend fun open() {
        gate.withLock {
            if (socket != null) return
            owner.enforceBluetoothPermission()
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
                // 有人读取时保留入站字节；仅写通道仍尽力排空，避免缓冲堆积。
                if (readers.get() == 0) runCatching {
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

    override suspend fun read(): ByteArray {
        val target = socket ?: throw BtException.UnsupportedOperation("RFCOMM channel is not open")
        readers.incrementAndGet()
        try {
            while (true) {
                if (socket !== target) throw BtException.Disconnected()
                val chunk = readAvailable(target)
                if (chunk != null) {
                    trace("rx", chunk.size)
                    return chunk
                }
                // 阻塞 read 无法随协程取消返回；轮询让取消、关闭与超时都能及时生效。
                delay(READ_POLL_MILLIS)
            }
        } finally {
            readers.decrementAndGet()
        }
    }

    /** 返回当前可用的一段字节，没有数据返回 null；失败时释放可疑通道并映射为平台异常。 */
    private suspend fun readAvailable(target: BluetoothSocket): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val available = target.inputStream.available()
            if (available <= 0) return@withContext null
            val buffer = ByteArray(minOf(available, MAX_READ))
            val count = target.inputStream.read(buffer)
            if (count <= 0) null else buffer.copyOf(count)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 关闭竞态以 Disconnected 表达；真实读取失败按传输错误保留原因。
            if (socket !== target) throw BtException.Disconnected()
            socket = null
            target.closeQuietly()
            throw transportFailure("read RFCOMM channel", e)
        }
    }

    /** 复用管理器的平台错误映射（权限/传输分类），不在本层重复实现。 */
    private fun transportFailure(operation: String, error: Exception): Nothing =
        throw owner.platformOperation(operation) { throw error }

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

        /** 单次读取上限；RFCOMM 是字节流，调用方仍需自行组帧。 */
        const val MAX_READ = 1024

        /** 无数据时的轮询间隔；不持有事务锁，也不影响写入。 */
        const val READ_POLL_MILLIS = 10L
    }
}
