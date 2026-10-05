package ink.lipoly.app.sunrise.catalog

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

internal interface CatalogByteFetcher {
    suspend fun fetch(url: String, maxBytes: Int): ByteArray
    fun close()
}

/** Owns one lazy client. Only an explicit repository pull calls fetch. */
internal class KtorCatalogFetcher : CatalogByteFetcher {
    private val lifecycleLock = Any()
    private val requests = Semaphore(4)
    private var client: HttpClient? = null
    private var closed = false

    private fun clientForRequest(): HttpClient = catalogFetcherSynchronized(lifecycleLock) {
        check(!closed) { "Catalog fetcher is closed" }
        client ?: HttpClient(CIO) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
            }
            engine {
                maxConnectionsCount = 4
                endpoint {
                    maxConnectionsPerRoute = 4
                    connectTimeout = 10_000
                    connectAttempts = 1
                }
            }
        }.also { client = it }
    }

    override suspend fun fetch(url: String, maxBytes: Int): ByteArray {
        require(maxBytes > 0) { "Catalog download byte limit must be positive" }
        return requests.withPermit {
            currentCoroutineContext().ensureActive()
            // execute's streaming scope avoids Ktor's default whole-body response buffering.
            clientForRequest().prepareGet(url).execute { response ->
                val channel = response.bodyAsChannel()
                try {
                    require(response.status == HttpStatusCode.OK) {
                        "Catalog download requires HTTP 200; received ${response.status.value}"
                    }
                    val declaredLength = response.contentLength()
                    require(declaredLength == null || declaredLength in 0..maxBytes.toLong()) {
                        "Catalog download exceeds $maxBytes bytes"
                    }
                    val result = Buffer()
                    val chunk = ByteArray(minOf(8192, maxBytes))
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        // Read at most one byte beyond the limit, even without Content-Length.
                        val remaining = maxBytes.toLong() - result.size
                        val count = channel.readAvailable(chunk, length = minOf(chunk.size.toLong(), remaining + 1).toInt())
                        if (count == -1) break
                        require(count.toLong() <= remaining) { "Catalog download exceeds $maxBytes bytes" }
                        result.write(chunk, 0, count)
                    }
                    channel.closedCause?.let { throw it }
                    currentCoroutineContext().ensureActive()
                    result.readByteArray()
                } finally {
                    channel.cancel(null)
                }
            }
        }
    }

    override fun close() {
        val toClose = catalogFetcherSynchronized(lifecycleLock) {
            closed = true
            client.also { client = null }
        }
        toClose?.close()
    }
}

/** Raw segments are encoded exactly once; literal percent escapes remain literal path text. */
internal fun catalogResponseUrl(cdn: CatalogCdn, path: String): String {
    validateCatalogResponsePath(path)
    val base = when (cdn) {
        CatalogCdn.CHINA -> CATALOG_CHINA_CDN_URL
        CatalogCdn.OVERSEAS -> CATALOG_OVERSEAS_CDN_URL
    }
    return URLBuilder(base).apply {
        pathSegments = listOf("") + path.split('/')
    }.buildString()
}

// Both supported platforms have JVM monitors; keep platform APIs out of common code.
internal expect fun <T> catalogFetcherSynchronized(lock: Any, block: () -> T): T
