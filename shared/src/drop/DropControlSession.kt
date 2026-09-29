package ink.lipoly.app.sunrise.drop

import kotlinx.coroutines.flow.StateFlow

/** Transport and state operations needed by platform-independent control implementations. */
internal interface DropControlSession {
    val state: StateFlow<DropState>

    fun profile(): DropProfile
    fun ancPath(): AncPath
    fun mutate(block: (DropState) -> DropState)

    suspend fun requestGaia(command: GaiaCommand): GaiaPacket
    suspend fun sendGaia(command: GaiaCommand)
    suspend fun requestSource(commandId: Int, payload: ByteArray): ByteArray
    suspend fun readSourceCapability(): ByteArray
    suspend fun readSourceInfo(): ByteArray
}
