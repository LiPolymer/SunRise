package ink.lipoly.app.sunrise.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow

/** Owns a host's controllers, never application services or the document picker itself. */
internal class PresentationSession(
    val headset: HeadsetPresentationController,
    val catalog: CatalogOperations,
    val eq: EqSessionOwner,
    private val scope: CoroutineScope,
) {
    private val closed = MutableStateFlow(false)

    fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        var failure: Throwable? = null
        try { eq.close() } catch (error: Throwable) { failure = error }
        try { catalog.close() } catch (error: Throwable) {
            val previous = failure
            if (previous == null) failure = error else previous.addSuppressed(error)
        }
        try { headset.close() } catch (error: Throwable) {
            val previous = failure
            if (previous == null) failure = error else previous.addSuppressed(error)
        }
        scope.cancel()
        failure?.let { throw it }
    }
}
