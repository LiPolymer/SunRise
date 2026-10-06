package ink.lipoly.app.sunrise.di

import ink.lipoly.app.sunrise.blueConnector.BtManager
import ink.lipoly.app.sunrise.headset.HeadsetClient
import kotlinx.coroutines.flow.MutableStateFlow

/** The only graph owner of the facade and its transport, closed in that order. */
internal class ApplicationBluetoothResources(val bt: BtManager, val client: HeadsetClient) {
    private val closed = MutableStateFlow(false)

    fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        var failure: Throwable? = null
        try {
            client.close()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                bt.close()
            } catch (error: Throwable) {
                if (failure == null) throw error
                failure.addSuppressed(error)
            }
        }
    }
}
