package ink.lipoly.app.sunrise.drop

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class AncVerificationTest {
    @Test
    fun persistentMismatchReportsRequestedAndObservedModes() = runBlocking {
        val failure = assertFailsWith<DropException.AncModeMismatch> {
            writeAndVerifyAncMode(
                requested = AncMode.WIND,
                write = {},
                read = { AncMode.OFF },
                wait = {},
            )
        }

        assertEquals(AncMode.WIND, failure.requested)
        assertEquals(AncMode.OFF, failure.observed)
    }

    @Test
    fun unreadableModeIsDistinctFromWriteFailure() = runBlocking {
        val readFailure = DropException.Timeout("GAIA 8/3")
        val unverified = assertFailsWith<DropException.Unverified> {
            writeAndVerifyAncMode(
                requested = AncMode.TRANSPARENCY,
                write = {},
                read = { throw readFailure },
                wait = {},
            )
        }
        assertSame(readFailure, unverified.cause)

        val writeFailure = DropException.Transport("GATT write failed")
        val propagated = assertFailsWith<DropException.Transport> {
            writeAndVerifyAncMode(
                requested = AncMode.TRANSPARENCY,
                write = { throw writeFailure },
                read = { error("read must not run") },
                wait = {},
            )
        }
        assertSame(writeFailure, propagated)
    }

    @Test
    fun disconnectDuringReadbackPropagates() = runBlocking {
        val disconnect = DropException.Disconnected()
        val propagated = assertFailsWith<DropException.Disconnected> {
            writeAndVerifyAncMode(
                requested = AncMode.OFF,
                write = {},
                read = { throw disconnect },
                wait = {},
            )
        }
        assertSame(disconnect, propagated)
    }
}
