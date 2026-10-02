package ink.lipoly.app.sunrise.blueConnector

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Caller cancellation never releases a native operation's slot before its callback has drained. */
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
