package ink.lipoly.app.sunrise.blueConnector

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 单设备连接所有者；已连接和正在连接的任务分别复用，不存在管理器级连接单槽。
 *
 * 生命周期 monitor 仅保护创建、分离及条件发布，无线等待始终在锁外。连接结果 deferred
 * 不绑定等待者 Job，取消单个 connect 等待者不取消无线任务；显式 disconnect 才分离任务。
 */
internal class AndroidBtGatt internal constructor(
    private val device: AndroidBtDevice,
) : BtGatt {
    // 同步关闭可以立即失效会话；生命周期锁不得覆盖连接、发现或 join 等无线等待。
    private val lifecycle = Any()
    private val mutableState = MutableStateFlow(GattState())
    override val state = mutableState.asStateFlow()
    private var current: AndroidGattSession? = null
    private var connecting: ConnectionAttempt? = null
    private var closed = false

    private class ConnectionAttempt(
        val session: AndroidGattSession,
        val result: CompletableDeferred<GattSession>,
        val job: Job,
    )

    /** 复用有效当前会话或共同的连接尝试；仅服务发现成功后发布 CONNECTED，不检测应用协议。 */
    override suspend fun connect(): GattSession {
        val attempt = synchronized(lifecycle) {
            if (closed || device.owner.isClosed) throw BtException.Disconnected()
            device.owner.enforceBluetoothPermission()
            current?.takeIf { mutableState.value.phase == GattPhase.CONNECTED && it.isActive }
                ?.let { return it }
            connecting ?: run {
                val session = AndroidGattSession(device, ::sessionTerminated)
                val result = CompletableDeferred<GattSession>()
                val job = device.owner.scope.launch(start = CoroutineStart.LAZY) {
                    establish(session, result)
                }
                ConnectionAttempt(session, result, job).also {
                    current = session
                    connecting = it
                    publish(GattState(GattPhase.CONNECTING))
                }
            }
        }
        attempt.job.start()
        // This deferred is not parented to callers: cancelling one waiter cannot cancel the radio job.
        return attempt.result.await()
    }

    /** 在自有连接任务中建立会话；发布前同时检查管理器、当前对象和会话活动性，阻止旧结果回写。 */
    private suspend fun establish(
        session: AndroidGattSession,
        result: CompletableDeferred<GattSession>,
    ) {
        try {
            session.open()
            synchronized(lifecycle) {
                if (closed || device.owner.isClosed || current !== session || !session.isActive)
                    throw BtException.Disconnected()
                if (connecting?.result === result) connecting = null
                publish(GattState(GattPhase.CONNECTED, session))
                result.complete(session)
            }
        } catch (_: CancellationException) {
            val failure = BtException.Disconnected()
            session.invalidate(failure)
            result.completeExceptionally(failure)
        } catch (error: Throwable) {
            val failure = error as? BtException ?: BtException.Transport("GATT connection failed", error)
            session.invalidate(failure)
            result.completeExceptionally(failure)
        }
    }

    /** 锁内先分离并失败化共享结果，锁外取消、关闭及 join；替代连接可在旧任务结束前建立。 */
    override suspend fun disconnect() {
        val previous = detach(BtException.Disconnected(), closed = false)
        previous.attempt?.job?.cancel()
        previous.session?.invalidate(BtException.Disconnected())
        // A replacement connect may proceed while the previous job finishes cancelling.
        previous.attempt?.job?.join()
    }

    /** 管理器/无线状态的同步失效入口；closed 只用于永久关闭，普通无线关闭仍允许后续重连。 */
    internal fun invalidate(
        cause: BtException = BtException.Disconnected(),
        closed: Boolean = false,
    ) {
        val previous = detach(cause, closed)
        previous.attempt?.job?.cancel()
        previous.session?.invalidate(cause)
    }

    private class Detached(val session: AndroidGattSession?, val attempt: ConnectionAttempt?)

    private fun detach(cause: BtException, closed: Boolean): Detached = synchronized(lifecycle) {
        if (closed || device.owner.isClosed) this.closed = true
        val previous = Detached(current, connecting)
        current = null
        connecting = null
        previous.attempt?.result?.completeExceptionally(cause)
        publish(terminalState(cause))
        previous
    }

    /** 只接受当前会话的终止；旧 callback 或旧 session.close 不能清除新会话。 */
    private fun sessionTerminated(session: AndroidGattSession, cause: BtException) {
        synchronized(lifecycle) {
            // An old native callback/session.close cannot change a replacement connection.
            if (current !== session) return
            current = null
            connecting?.takeIf { it.session === session }?.result?.completeExceptionally(cause)
            connecting = null
            publish(terminalState(cause))
        }
    }

    private fun terminalState(cause: BtException): GattState = when {
        closed || device.owner.isClosed -> GattState(GattPhase.CLOSED)
        cause is BtException.Disconnected -> GattState()
        else -> GattState(GattPhase.ERROR, error = cause)
    }

    private fun publish(value: GattState) {
        if (mutableState.value == value) return
        mutableState.value = value
        device.owner.publishGatt(device, value)
    }
}
