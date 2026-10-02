package ink.lipoly.app.sunrise.drop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * Some headsets apply an ANC SET without sending a matching GAIA response.
 * The transport write and a separate GET are the two observable steps.
 */
internal suspend fun writeAndVerifyAncMode(
    requested: AncMode,
    write: suspend () -> Unit,
    read: suspend () -> AncMode,
    wait: suspend (Long) -> Unit = { delay(it.milliseconds) },
): AncMode {
    write()
    wait(300)

    repeat(4) { attempt ->
        val observed = try {
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: DropException.Disconnected) {
            throw e
        } catch (e: DropException.NotReady) {
            throw e
        } catch (e: Exception) {
            throw DropException.Unverified("ANC mode", e)
        }
        if (observed == requested) return observed
        if (attempt < 3) wait(500)
        else throw DropException.AncModeMismatch(requested, observed)
    }
    error("Unreachable ANC verification state")
}
