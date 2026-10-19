package ink.lipoly.app.sunrise.drop.gaia

import ink.lipoly.app.sunrise.drop.DropException
import ink.lipoly.app.sunrise.drop.DropProfile
import ink.lipoly.app.sunrise.drop.DropState
import kotlinx.coroutines.flow.StateFlow

/** Lock-owned, coroutine-confined transaction; never retain or use outside its block. */
internal interface GaiaTransaction {
    val maxWriteSize: Int
    suspend fun request(command: GaiaCommand, expectedPeqRange: IntRange? = null): GaiaPacket
    suspend fun send(command: GaiaCommand)
}

/** GAIA controls remain bound to one epoch; retired controls never target a new connection. */
internal interface GaiaControlSession {
    val state: StateFlow<DropState>
    fun profile(): DropProfile
    fun ancPath(): AncPath
    fun mutate(block: (DropState) -> DropState)
    suspend fun requestGaia(command: GaiaCommand): GaiaPacket
    suspend fun sendGaia(command: GaiaCommand)
    suspend fun <T> withGaiaTransaction(block: suspend GaiaTransaction.() -> T): T
    fun normalizeTransport(error: Exception): DropException
}
