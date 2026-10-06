package ink.lipoly.app.sunrise.blueConnector

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 单会话容量 64 的原生操作队列，以一个资源自有 worker 保证 ATT 操作不重叠。
 *
 * 等待者的 deferred 不属于调用方 Job：尚未开始的取消请求被跳过；已开始的请求仅取消
 * 等待结果，worker 继续排空 block 的原生回调或期限，才运行下一项。队列不负责原生超时，
 * 由会话的 block 在超时时关闭会话。活动请求/关闭原因通过 CAS 发布，避免关闭与领取竞态。
 * @param scope 会话拥有的协程作用域；结束时队列关闭，而非绑定到单个调用方作用域。
 */
internal class GattOperationQueue(scope: CoroutineScope) {
    private class Request(val block: suspend () -> Any?, val result: CompletableDeferred<Any?>)
    private sealed interface Lifecycle {
        data class Open(val active: Request? = null) : Lifecycle
        data class Closed(val cause: BtException) : Lifecycle
    }

    private val lifecycle = MutableStateFlow<Lifecycle>(Lifecycle.Open())
    private val requests = Channel<Request>(64)
    private val worker = scope.launch(start = CoroutineStart.LAZY) {
        try {
            for (request in requests) {
                if (!request.result.isActive) continue
                if (!claim(request)) break
                try {
                    if (request.result.isActive) request.result.complete(request.block())
                } catch (e: CancellationException) {
                    request.result.completeExceptionally((lifecycle.value as? Lifecycle.Closed)?.cause ?: BtException.Disconnected())
                    throw e
                } catch (e: Throwable) {
                    request.result.completeExceptionally(e)
                } finally {
                    lifecycle.compareAndSet(Lifecycle.Open(request), Lifecycle.Open())
                }
            }
        } catch (e: BtException) {
            close(e)
        }
    }

    init {
        worker.invokeOnCompletion { close() }
        worker.start()
    }

    private fun claim(request: Request): Boolean {
        while (true) {
            val previous = lifecycle.value
            if (previous is Lifecycle.Closed) {
                request.result.completeExceptionally(previous.cause)
                return false
            }
            if (lifecycle.compareAndSet(previous, Lifecycle.Open(request))) return true
        }
    }

    /**
     * 入队并等待结果；队列满时背压挂起，不增加无界待执行槽。
     * @param block 在自有 worker 中执行的单个原生操作及其回调等待。
     * @return block 的实际结果；调用者取消原样传播，不能提前释放活动原生槽。
     * @throws BtException 队列已关闭时抛保存的关闭原因，或传播 block 的蓝牙失败。
     */
    suspend fun <T> run(block: suspend () -> T): T {
        (lifecycle.value as? Lifecycle.Closed)?.let { throw it.cause }
        val result = CompletableDeferred<Any?>()
        val request = Request(block, result)
        try {
            requests.send(request)
            @Suppress("UNCHECKED_CAST")
            return result.await() as T
        } catch (e: CancellationException) {
            result.cancel(e)
            throw e
        }
    }

    /**
     * 幂等发布终态，使活动/排队等待者失败并停止 worker。
     * @param cause 后续入队和现有等待者收到的原因；首次关闭原因保留。
     */
    fun close(cause: BtException = BtException.Disconnected()) {
        while (true) {
            val previous = lifecycle.value
            if (previous is Lifecycle.Closed) return
            if (!lifecycle.compareAndSet(previous, Lifecycle.Closed(cause))) continue
            (previous as Lifecycle.Open).active?.result?.completeExceptionally(cause)
            requests.close(cause)
            while (true) {
                val request = requests.tryReceive().getOrNull() ?: break
                request.result.completeExceptionally(cause)
            }
            worker.cancel()
            return
        }
    }
}
