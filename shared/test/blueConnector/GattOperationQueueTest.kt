package ink.lipoly.app.sunrise.blueConnector

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class GattOperationQueueTest {
    @Test fun cancelledCallerKeepsNativeSlotUntilCallback() = runBlocking {
        withTimeout(5_000) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val queue = GattOperationQueue(scope)
            val started = CompletableDeferred<Unit>()
            val callback = CompletableDeferred<Unit>()
            var secondStarted = false
            try {
                val first = async {
                    queue.run { started.complete(Unit); callback.await(); "A" }
                }
                started.await()
                first.cancel()
                first.join()
                val second = async(start = CoroutineStart.UNDISPATCHED) {
                    queue.run { secondStarted = true; "B" }
                }
                assertFalse(secondStarted)
                callback.complete(Unit)
                assertEquals("B", second.await())
            } finally { queue.close(); scope.cancel() }
        }
    }

    @Test fun cancelledQueuedRequestNeverStartsNativeOperation() = runBlocking {
        withTimeout(5_000) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val queue = GattOperationQueue(scope)
            val started = CompletableDeferred<Unit>()
            val callback = CompletableDeferred<Unit>()
            var cancelledStarted = false
            try {
                val first = async { queue.run { started.complete(Unit); callback.await(); 1 } }
                started.await()
                val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
                    queue.run { cancelledStarted = true; 2 }
                }
                cancelled.cancel()
                cancelled.join()
                val final = async(start = CoroutineStart.UNDISPATCHED) { queue.run { 3 } }
                callback.complete(Unit)
                assertEquals(1, first.await())
                assertEquals(3, final.await())
                assertFalse(cancelledStarted)
            } finally { queue.close(); scope.cancel() }
        }
    }

    @Test fun closeFailsActiveAndQueuedRequestsWithoutStartingNext() = runBlocking {
        withTimeout(5_000) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val queue = GattOperationQueue(scope)
            val started = CompletableDeferred<Unit>()
            val callback = CompletableDeferred<Unit>()
            var nextStarted = false
            try {
                val first = async { runCatching { queue.run { started.complete(Unit); callback.await() } }.exceptionOrNull() }
                started.await()
                val next = async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { queue.run { nextStarted = true } }.exceptionOrNull()
                }
                queue.close()
                queue.close()
                assertIs<BtException.Disconnected>(first.await())
                assertIs<BtException.Disconnected>(next.await())
                assertFalse(nextStarted)
            } finally { queue.close(); scope.cancel() }
        }
    }
}
