package ink.lipoly.app.sunrise.bluetooth

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RfcommChannelTest {
    private class BlockingPoint {
        val entered = CompletableDeferred<Unit>()
        private val release = CountDownLatch(1)
        fun block() {
            entered.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS)) { "Test did not release socket operation" }
        }
        fun release() = release.countDown()
    }

    private class Rig {
        val sockets = mutableListOf<LoopbackRfcommSocket>()
        val requests = mutableListOf<String>()
        var configure: (LoopbackRfcommSocket) -> Unit = {}
        val channel = RfcommChannel("00:11:22:33:44:55", Dispatchers.IO) { uuid ->
            requests.add(uuid)
            if (uuid !in SERVICES) throw IOException("Service not advertised: $uuid")
            LoopbackRfcommSocket(uuid).also { configure(it); sockets.add(it) }
        }
        val socket: LoopbackRfcommSocket get() = sockets.last()
        suspend fun open(policy: RfcommInputPolicy = RfcommInputPolicy.PRESERVE) = channel.open(SPP, policy)
    }

    private fun ioTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(10_000) { supervisorScope(block) }
    }

    @Test fun preserveKeepsBytesReceivedBetweenReadsAcrossWrites() = ioTest {
        val rig = Rig()
        try {
            rig.open()
            rig.socket.receive(byteArrayOf(1, 2))
            assertContentEquals(byteArrayOf(1, 2), rig.channel.read())
            val next = byteArrayOf(0, 127, -128, -1)
            rig.socket.receive(next)
            rig.channel.write(byteArrayOf(9))
            assertContentEquals(next, rig.channel.read())
            assertContentEquals(byteArrayOf(9), rig.socket.writes.single())
            assertEquals(1, rig.socket.flushCount.get())
        } finally { rig.channel.close() }
    }

    @Test fun drainDiscardsBufferedBytesBeforeWriting() = ioTest {
        val rig = Rig()
        try {
            rig.open(RfcommInputPolicy.DRAIN_BEFORE_WRITE)
            rig.socket.receive(byteArrayOf(1, 2, 3))
            rig.channel.write(byteArrayOf(9))
            assertEquals(0, rig.socket.bufferedBytes)
            rig.socket.receive(byteArrayOf(4, 5))
            assertContentEquals(byteArrayOf(4, 5), rig.channel.read())
        } finally { rig.channel.close() }
    }

    @Test fun drainDoesNotConsumeBytesOwnedByAnActiveReadOrBlockWrites() = ioTest {
        val rig = Rig()
        val gate = BlockingPoint()
        try {
            rig.open(RfcommInputPolicy.DRAIN_BEFORE_WRITE)
            rig.socket.receive(byteArrayOf(7, 8))
            rig.socket.beforeRead = gate::block
            val reader = async { rig.channel.read() }
            try {
                gate.entered.await()
                withTimeout(1_000) { rig.channel.write(byteArrayOf(9)) }
                assertEquals(2, rig.socket.bufferedBytes)
                gate.release()
                assertContentEquals(byteArrayOf(7, 8), reader.await())
                assertEquals(1, rig.socket.readCount.get())
            } finally { gate.release(); reader.cancelAndJoin() }
        } finally { gate.release(); rig.channel.close() }
    }

    @Test fun drainFailureDoesNotBecomeWriteFailure() = ioTest {
        val rig = Rig()
        try {
            rig.open(RfcommInputPolicy.DRAIN_BEFORE_WRITE)
            rig.socket.receive(byteArrayOf(1))
            rig.socket.readFailure = IOException("Drain failed")
            rig.channel.write(byteArrayOf(42))
            assertContentEquals(byteArrayOf(42), rig.socket.writes.single())
            assertFalse(rig.socket.isClosed)
        } finally { rig.channel.close() }
    }

    @Test fun cancelledWaitingReadKeepsSocketWritable() = ioTest {
        val rig = Rig()
        val polling = CompletableDeferred<Unit>()
        try {
            rig.open()
            rig.socket.beforeAvailable = { polling.complete(Unit) }
            val reader = async { rig.channel.read() }
            try {
                polling.await()
                reader.cancelAndJoin()
                rig.channel.write(byteArrayOf(3))
                assertFalse(rig.socket.isClosed)
                assertContentEquals(byteArrayOf(3), rig.socket.writes.single())
                rig.socket.receive(byteArrayOf(4))
                assertContentEquals(byteArrayOf(4), rig.channel.read())
            } finally { reader.cancelAndJoin() }
        } finally { rig.channel.close() }
    }

    @Test fun closeBreaksWaitingReadAndIsIdempotent() = ioTest {
        val rig = Rig()
        val polling = CompletableDeferred<Unit>()
        try {
            rig.open()
            rig.socket.beforeAvailable = { polling.complete(Unit) }
            val reader = async { runCatching { rig.channel.read() } }
            try {
                polling.await()
                rig.channel.close()
                assertIs<BtException.Disconnected>(reader.await().exceptionOrNull())
                rig.channel.close()
                assertTrue(rig.socket.isClosed)
                assertEquals(1, rig.socket.closeCount.get())
            } finally { reader.cancelAndJoin() }
        } finally { rig.channel.close() }
    }

    @Test fun staleReadFailureCannotRetireReplacementSocket() = ioTest {
        val rig = Rig()
        val gate = BlockingPoint()
        try {
            rig.open()
            val old = rig.socket
            old.receive(byteArrayOf(1))
            old.beforeRead = gate::block
            old.readFailure = IOException("Late old socket failure")
            val reader = async { runCatching { rig.channel.read() } }
            try {
                gate.entered.await()
                rig.channel.close()
                rig.channel.open(U31, RfcommInputPolicy.PRESERVE)
                val replacement = rig.socket
                gate.release()
                assertIs<BtException.Disconnected>(reader.await().exceptionOrNull())
                rig.channel.write(byteArrayOf(11))
                replacement.receive(byteArrayOf(12))
                assertContentEquals(byteArrayOf(12), rig.channel.read())
                assertContentEquals(byteArrayOf(11), replacement.writes.single())
                assertFalse(replacement.isClosed)
            } finally { gate.release(); reader.cancelAndJoin() }
        } finally { gate.release(); rig.channel.close() }
    }

    @Test fun matchingConfigurationIsIdempotentAndUuidIsNormalized() = ioTest {
        val rig = Rig()
        try {
            rig.channel.open(U31.uppercase(), RfcommInputPolicy.PRESERVE)
            val socket = rig.socket
            rig.channel.open(U31, RfcommInputPolicy.PRESERVE)
            assertSame(socket, rig.socket)
            assertEquals(listOf(U31), rig.requests)
            assertEquals(1, socket.connectCount.get())
        } finally { rig.channel.close() }
    }

    @Test fun changingUuidOrPolicyRequiresCloseAndPreservesCurrentConnection() = ioTest {
        val rig = Rig()
        try {
            rig.open()
            val first = rig.socket
            assertFailsWith<IllegalStateException> { rig.channel.open(U31, RfcommInputPolicy.PRESERVE) }
            assertFailsWith<IllegalStateException> { rig.open(RfcommInputPolicy.DRAIN_BEFORE_WRITE) }
            rig.channel.write(byteArrayOf(5))
            assertContentEquals(byteArrayOf(5), first.writes.single())
            assertFalse(first.isClosed)
            assertEquals(1, rig.sockets.size)
            rig.channel.close()
            rig.channel.open(U31, RfcommInputPolicy.DRAIN_BEFORE_WRITE)
            assertEquals(U31, rig.socket.serviceUuid)
            assertTrue(first.isClosed)
        } finally { rig.channel.close() }
    }

    @Test fun explicitStandardU31AndU32ServicesDeliverTheirOwnBytes() = ioTest {
        val rig = Rig()
        try {
            SERVICES.forEachIndexed { index, uuid ->
                rig.channel.open(uuid, RfcommInputPolicy.PRESERVE)
                assertEquals(uuid, rig.socket.serviceUuid)
                rig.channel.write(byteArrayOf(index.toByte()))
                rig.socket.receive(byteArrayOf(index.toByte(), 10))
                assertContentEquals(byteArrayOf(index.toByte(), 10), rig.channel.read())
                rig.channel.close()
            }
            assertEquals(SERVICES, rig.requests)
            assertTrue(rig.sockets.all { it.isClosed })
        } finally { rig.channel.close() }
    }

    @Test fun malformedAndUnknownServicesNeverFallBack() = ioTest {
        val rig = Rig()
        try {
            listOf("1101", "", " $SPP", "$SPP ", "00001101-0000-1000-8000-00805f9b34fg", "0000110100001000800000805f9b34fb", "00001101-0000-1000-8000-00805f9b34f", "００００1101-0000-1000-8000-00805f9b34fb").forEach {
                assertFailsWith<IllegalArgumentException> { rig.channel.open(it, RfcommInputPolicy.PRESERVE) }
            }
            assertTrue(rig.requests.isEmpty())
            assertTrue(rig.sockets.isEmpty())
            val unknown = "0cf12d33-fac3-4553-bd80-d6832e7b3931"
            assertFailsWith<IOException> { rig.channel.open(unknown, RfcommInputPolicy.PRESERVE) }
            assertEquals(listOf(unknown), rig.requests)
            assertTrue(rig.sockets.isEmpty())
            rig.open()
            rig.channel.write(byteArrayOf(1))
        } finally { rig.channel.close() }
    }

    @Test fun connectFailureClosesSocketAndDoesNotCommitConfiguration() = ioTest {
        val rig = Rig()
        val failure = IOException("Connect failed")
        rig.configure = { it.connectFailure = failure }
        try {
            assertFailsWith<IOException> { rig.open() }
            assertTrue(rig.socket.isClosed)
            rig.configure = {}
            rig.channel.open(U31, RfcommInputPolicy.DRAIN_BEFORE_WRITE)
            rig.channel.write(byteArrayOf(2))
            assertEquals(U31, rig.socket.serviceUuid)
        } finally { rig.channel.close() }
    }

    @Test fun writeFailureRetiresSocketAndAllowsNewConfiguration() = ioTest {
        val rig = Rig()
        try {
            rig.open()
            val old = rig.socket
            val failure = IOException("Write failed")
            old.writeFailure = failure
            assertFailsWith<IOException> { rig.channel.write(byteArrayOf(1)) }
            assertTrue(old.isClosed)
            rig.channel.open(U32, RfcommInputPolicy.PRESERVE)
            rig.channel.write(byteArrayOf(2))
            assertContentEquals(byteArrayOf(2), rig.socket.writes.single())
        } finally { rig.channel.close() }
    }

    @Test fun cancelledConnectClosesUncertainSocketAndAllowsReopen() = ioTest {
        val rig = Rig()
        val gate = BlockingPoint()
        rig.configure = { it.beforeConnect = gate::block }
        val opener = async { rig.open() }
        try {
            gate.entered.await()
            opener.cancel()
            gate.release()
            opener.join()
            assertTrue(rig.socket.isClosed)
            rig.configure = {}
            rig.channel.open(U31, RfcommInputPolicy.PRESERVE)
            rig.channel.write(byteArrayOf(3))
        } finally { gate.release(); opener.cancelAndJoin(); rig.channel.close() }
    }

    @Test fun cancelledWriteClosesUncertainSocketAndAllowsReopen() = ioTest {
        val rig = Rig()
        val gate = BlockingPoint()
        try {
            rig.open()
            val old = rig.socket
            old.beforeWrite = gate::block
            val writer = async { rig.channel.write(byteArrayOf(1)) }
            try {
                gate.entered.await()
                writer.cancel()
                gate.release()
                writer.join()
                assertTrue(old.isClosed)
                rig.channel.open(U31, RfcommInputPolicy.PRESERVE)
                rig.channel.write(byteArrayOf(2))
            } finally { gate.release(); writer.cancelAndJoin() }
        } finally { gate.release(); rig.channel.close() }
    }

    @Test fun queuedReadsSerializeAndNeverHoldTheWriteGate() = ioTest {
        val rig = Rig()
        val gate = BlockingPoint()
        try {
            rig.open(RfcommInputPolicy.DRAIN_BEFORE_WRITE)
            rig.socket.receive(byteArrayOf(1))
            rig.socket.beforeRead = gate::block
            val first = async { rig.channel.read() }
            try {
                gate.entered.await()
                val second = async(start = CoroutineStart.UNDISPATCHED) { rig.channel.read() }
                try {
                    withTimeout(1_000) { rig.channel.write(byteArrayOf(9)) }
                    assertEquals(1, rig.socket.readCount.get())
                    assertFalse(second.isCompleted)
                    rig.socket.beforeRead = null
                    gate.release()
                    assertContentEquals(byteArrayOf(1), first.await())
                    rig.socket.receive(byteArrayOf(2))
                    assertContentEquals(byteArrayOf(2), second.await())
                    assertEquals(2, rig.socket.readCount.get())
                } finally { gate.release(); second.cancelAndJoin() }
            } finally { gate.release(); first.cancelAndJoin() }
        } finally { gate.release(); rig.channel.close() }
    }

    @Test fun closeBlockingInvalidatesAnInProgressOpen() = ioTest {
        val rig = Rig()
        val gate = BlockingPoint()
        rig.configure = { it.beforeConnect = gate::block }
        val opener = async { runCatching { rig.open() } }
        try {
            gate.entered.await()
            rig.channel.closeBlocking()
            gate.release()
            assertIs<IOException>(opener.await().exceptionOrNull())
            assertTrue(rig.socket.isClosed)
            rig.configure = {}
            rig.channel.open(U31, RfcommInputPolicy.PRESERVE)
            rig.channel.write(byteArrayOf(3))
        } finally { gate.release(); opener.cancelAndJoin(); rig.channel.close() }
    }

    @Test fun currentReadFailureRetiresOnlyItsSocketAndAllowsReopen() = ioTest {
        val rig = Rig()
        try {
            rig.open()
            val old = rig.socket
            val failure = IOException("Read failed")
            old.receive(byteArrayOf(1))
            old.readFailure = failure
            assertFailsWith<IOException> { rig.channel.read() }
            assertTrue(old.isClosed)
            rig.channel.open(U31, RfcommInputPolicy.PRESERVE)
            rig.socket.receive(byteArrayOf(2))
            assertContentEquals(byteArrayOf(2), rig.channel.read())
        } finally { rig.channel.close() }
    }

    @Test fun readReturnsArbitraryChunksBoundedBy1024Bytes() = ioTest {
        val rig = Rig()
        try {
            rig.open()
            val bytes = ByteArray(1_500) { it.toByte() }
            rig.socket.receive(bytes)
            val first = rig.channel.read()
            val second = rig.channel.read()
            assertEquals(1_024, first.size)
            assertEquals(476, second.size)
            assertContentEquals(bytes, first + second)
        } finally { rig.channel.close() }
    }

    companion object {
        private const val SPP = "00001101-0000-1000-8000-00805f9b34fb"
        private const val U31 = "0cf12d31-fac3-4553-bd80-d6832e7b3931"
        private const val U32 = "0cf12d32-fac3-4553-bd80-d6832e7b3931"
        private val SERVICES = listOf(SPP, U31, U32)
    }
}
