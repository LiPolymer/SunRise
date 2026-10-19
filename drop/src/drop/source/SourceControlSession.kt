package ink.lipoly.app.sunrise.drop.source

import ink.lipoly.app.sunrise.drop.DropException
import ink.lipoly.app.sunrise.drop.DropState
import kotlinx.coroutines.flow.StateFlow

/** Controls are bound to one transport epoch; retired controls never target a replacement session. */
internal interface SourceControlSession {
    val state: StateFlow<DropState>
    fun mutate(block: (DropState) -> DropState)
    suspend fun requestSource(commandId: Int, payload: ByteArray): ByteArray
    suspend fun readSourceCapability(): ByteArray
    suspend fun readSourceInfo(): ByteArray
    fun normalizeTransport(error: Exception): DropException
}
