package ink.lipoly.app.sunrise.drop

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class AncVerificationTest {
    @Test
    fun setWithoutAckSucceedsWhenReadbackMatches() = runBlocking {
        val steps = mutableListOf<String>()
        val mode = writeAndVerifyAncMode(
            requested = AncMode.TRANSPARENCY,
            write = { steps += "write" },
            read = { steps += "read"; AncMode.TRANSPARENCY },
            wait = { steps += "wait:$it" },
        )

        assertEquals(AncMode.TRANSPARENCY, mode)
        assertEquals(listOf("write", "wait:300", "read"), steps)
    }

    @Test
    fun staleReadbackIsRetriedWithoutResendingSet() = runBlocking {
        var writes = 0
        var reads = 0
        val waits = mutableListOf<Long>()
        val mode = writeAndVerifyAncMode(
            requested = AncMode.NOISE_CANCELLING,
            write = { writes++ },
            read = { if (++reads < 3) AncMode.OFF else AncMode.NOISE_CANCELLING },
            wait = { waits += it },
        )

        assertEquals(AncMode.NOISE_CANCELLING, mode)
        assertEquals(1, writes)
        assertEquals(3, reads)
        assertEquals(listOf(300L, 500L, 500L), waits)
    }

    @Test
    fun persistentMismatchReportsRequestedAndObservedModes() = runBlocking {
        var reads = 0
        val failure = assertFailsWith<DropException.AncModeMismatch> {
            writeAndVerifyAncMode(
                requested = AncMode.WIND,
                write = {},
                read = { reads++; AncMode.OFF },
                wait = {},
            )
        }

        assertEquals(4, reads)
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
