package ink.lipoly.app.sunrise.catalog

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Exercises the production CIO client over loopback HTTP, including streaming boundaries. */
class KtorCatalogFetcherTest {
    @Test fun acceptsExactLimitForFixedLengthAndChunkedBodiesWithoutSendingCredentialsOrCookies() = runBlocking {
        LoopbackCatalogServer().use { server ->
            val bytes = byteArrayOf(0, 1, 2, 3, -1)
            val credentials = CompletableDeferred<List<String?>>()
            server.route("/fixed") { exchange ->
                exchange.responseHeaders.add("Set-Cookie", "account=private; Path=/")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
            server.route("/chunked") { exchange ->
                credentials.complete(listOf(
                    exchange.requestHeaders.getFirst("Authorization"),
                    exchange.requestHeaders.getFirst("Cookie"),
                ))
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write(bytes)
            }
            val fetcher = KtorCatalogFetcher()
            try {
                withTimeout(5_000) {
                    assertContentEquals(bytes, fetcher.fetch(server.url("/fixed"), bytes.size))
                    assertContentEquals(bytes, fetcher.fetch(server.url("/chunked"), bytes.size))
                    assertEquals(listOf(null, null), credentials.await())
                }
            } finally {
                fetcher.close()
            }
        }
    }

    @Test fun rejectsNon200AndDoesNotRetryOrFollowRedirects() = runBlocking {
        LoopbackCatalogServer().use { server ->
            val requests = AtomicInteger()
            val redirects = AtomicInteger()
            server.route("/target") { exchange ->
                redirects.incrementAndGet()
                exchange.sendResponseHeaders(200, -1)
            }
            for (status in listOf(204, 206, 301, 302, 307, 308, 404, 500)) {
                server.route("/status/$status") { exchange ->
                    requests.incrementAndGet()
                    exchange.responseHeaders.add("Location", server.url("/target"))
                    exchange.sendResponseHeaders(status, -1)
                }
            }
            val fetcher = KtorCatalogFetcher()
            try {
                withTimeout(5_000) {
                    for (status in listOf(204, 206, 301, 302, 307, 308, 404, 500)) {
                        assertFailsWith<IllegalArgumentException> { fetcher.fetch(server.url("/status/$status"), 16) }
                    }
                    assertEquals(8, requests.get())
                    assertEquals(0, redirects.get())
                }
            } finally {
                fetcher.close()
            }
        }
    }

    @Test fun oversizedContentLengthIsRejectedWithoutWaitingForBody(): Unit = runBlocking {
        LoopbackCatalogServer().use { server ->
            val release = CountDownLatch(1)
            server.route("/large") { exchange ->
                exchange.sendResponseHeaders(200, 1024)
                exchange.responseBody.flush()
                release.await(10, TimeUnit.SECONDS)
            }
            val fetcher = KtorCatalogFetcher()
            try {
                withTimeout(5_000) {
                    assertFailsWith<IllegalArgumentException> { fetcher.fetch(server.url("/large"), 8) }
                }
            } finally {
                release.countDown()
                fetcher.close()
            }
        }
    }

    @Test fun chunkedOverflowIsRejectedAtFirstExtraByteWithoutWaitingForEndOfStream(): Unit = runBlocking {
        LoopbackCatalogServer().use { server ->
            val release = CountDownLatch(1)
            server.route("/large") { exchange ->
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write(ByteArray(9) { it.toByte() })
                exchange.responseBody.flush()
                release.await(10, TimeUnit.SECONDS)
            }
            val fetcher = KtorCatalogFetcher()
            try {
                withTimeout(5_000) {
                    assertFailsWith<IllegalArgumentException> { fetcher.fetch(server.url("/large"), 8) }
                }
            } finally {
                release.countDown()
                fetcher.close()
            }
        }
    }

    @Test fun coroutineCancellationInterruptsBlockedBodyReadAndLeavesClientUsable() = runBlocking {
        LoopbackCatalogServer().use { server ->
            val started = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            server.route("/stalled") { exchange ->
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write(1)
                exchange.responseBody.flush()
                started.complete(Unit)
                release.await(10, TimeUnit.SECONDS)
            }
            val bytes = "complete".encodeToByteArray()
            server.route("/ready") { exchange ->
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
            val fetcher = KtorCatalogFetcher()
            try {
                withTimeout(5_000) {
                    val pending = async { fetcher.fetch(server.url("/stalled"), 16) }
                    started.await()
                    pending.cancelAndJoin()
                    assertFailsWith<CancellationException> { pending.await() }
                    assertContentEquals(bytes, fetcher.fetch(server.url("/ready"), 16))
                }
            } finally {
                release.countDown()
                fetcher.close()
            }
        }
    }

    @Test fun closeBeforeFirstRequestIsIdempotentAndNeverStartsNetwork() = runBlocking {
        LoopbackCatalogServer().use { server ->
            val requests = AtomicInteger()
            server.route("/ready") { exchange ->
                requests.incrementAndGet()
                exchange.sendResponseHeaders(200, -1)
            }
            val fetcher = KtorCatalogFetcher()
            fetcher.close()
            fetcher.close()
            assertFailsWith<IllegalStateException> { fetcher.fetch(server.url("/ready"), 16) }
            assertEquals(0, requests.get())
        }
    }

    @Test fun invalidLimitsNeverStartNetwork() = runBlocking {
        LoopbackCatalogServer().use { server ->
            val requests = AtomicInteger()
            server.route("/ready") { exchange ->
                requests.incrementAndGet()
                exchange.sendResponseHeaders(200, -1)
            }
            val fetcher = KtorCatalogFetcher()
            try {
                for (limit in listOf(0, -1)) {
                    assertFailsWith<IllegalArgumentException> { fetcher.fetch(server.url("/ready"), limit) }
                }
                assertEquals(0, requests.get())
            } finally {
                fetcher.close()
            }
        }
    }

    @Test fun noMoreThanFourRequestsCanReachServerAtOnce() = runBlocking {
        LoopbackCatalogServer().use { server ->
            val firstFour = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            val arrived = AtomicInteger()
            server.route("/parallel") { exchange ->
                val current = active.incrementAndGet()
                maximum.accumulateAndGet(current, ::maxOf)
                if (arrived.incrementAndGet() == 4) firstFour.complete(Unit)
                try {
                    release.await(10, TimeUnit.SECONDS)
                } finally {
                    active.decrementAndGet()
                }
                exchange.sendResponseHeaders(200, 1)
                exchange.responseBody.write(1)
            }
            val fetcher = KtorCatalogFetcher()
            try {
                withTimeout(5_000) {
                    val pending = List(8) { async { fetcher.fetch(server.url("/parallel"), 1) } }
                    firstFour.await()
                    assertEquals(4, arrived.get())
                    release.countDown()
                    for (request in pending) assertContentEquals(byteArrayOf(1), request.await())
                    assertEquals(8, arrived.get())
                    assertTrue(maximum.get() <= 4)
                }
            } finally {
                release.countDown()
                fetcher.close()
            }
        }
    }

    @Test fun concurrentCloseAndFirstFetchNeverAllowLaterRequests() = runBlocking {
        LoopbackCatalogServer().use { server ->
            server.route("/ready") { exchange ->
                exchange.sendResponseHeaders(200, 1)
                exchange.responseBody.write(1)
            }
            withTimeout(5_000) {
                repeat(12) {
                    val fetcher = KtorCatalogFetcher()
                    val start = CompletableDeferred<Unit>()
                    val pending = async(kotlinx.coroutines.Dispatchers.Default) {
                        start.await()
                        try {
                            fetcher.fetch(server.url("/ready"), 1)
                        } catch (_: Exception) {
                            // Either the request wins, or close rejects/cancels it.
                        }
                    }
                    val closing = launch(kotlinx.coroutines.Dispatchers.Default) {
                        start.await()
                        fetcher.close()
                    }
                    start.complete(Unit)
                    closing.join()
                    pending.await()
                    assertFailsWith<IllegalStateException> { fetcher.fetch(server.url("/ready"), 1) }
                    fetcher.close()
                }
            }
        }
    }
}

private class LoopbackCatalogServer : AutoCloseable {
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        this.executor = this@LoopbackCatalogServer.executor
        start()
    }

    fun url(path: String): String = "http://127.0.0.1:${server.address.port}$path"

    fun route(path: String, handle: (HttpExchange) -> Unit) {
        server.createContext(path) { exchange ->
            try {
                handle(exchange)
            } finally {
                exchange.close()
            }
        }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}
