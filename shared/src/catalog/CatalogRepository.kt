package ink.lipoly.app.sunrise.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** writeActive must replace the entire file atomically, preserving the old file on failure. */
internal interface CatalogStorage {
    suspend fun readActive(): ByteArray?
    suspend fun writeActive(bytes: ByteArray)
}

internal enum class CatalogCdn { CHINA, OVERSEAS }
internal enum class CatalogOrigin { BUNDLED, LOCAL, PULL, IMPORT }

internal data class CatalogState(
    val snapshot: CatalogSnapshot? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val completedFiles: Int = 0,
    val totalFiles: Int = 0,
    val error: String? = null,
    val warning: String? = null,
    val origin: CatalogOrigin? = null,
)

internal class CatalogRepository(
    private val storage: CatalogStorage,
    private val loadBundled: suspend () -> ByteArray,
    private val fetcher: CatalogByteFetcher = KtorCatalogFetcher(),
) {
    private val mutableState = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = mutableState.asStateFlow()
    private val updateMutex = Mutex()
    private val lifetime = Job()

    /** No network, no repair write: a broken active file remains available for diagnosis. */
    suspend fun loadLocal() = update {
        mutableState.update { it.copy(loading = true, warning = null) }
        var warning: String? = null
        val active = try {
            withContext(Dispatchers.IO) {
                storage.readActive()?.let(::decodeCatalogSnapshot)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            warning = "Local database is damaged; using the bundled snapshot. ${error.description()}"
            mutableState.update { it.copy(warning = warning) }
            null
        }
        val snapshot = active ?: withContext(Dispatchers.IO) {
            decodeCatalogSnapshot(loadBundled())
        }
        currentCoroutineContext().ensureActive()
        lifetime.ensureActive()
        mutableState.value = CatalogState(
            snapshot = snapshot,
            loading = false,
            busy = true,
            warning = warning,
            origin = if (active == null) CatalogOrigin.BUNDLED else CatalogOrigin.LOCAL,
        )
    }

    suspend fun pull(cdn: CatalogCdn) = update { commit ->
        val catalogue = fetcher.fetch(CATALOGUE_URL, MAX_CATALOGUE_BYTES)
        val products = withContext(Dispatchers.IO) { parseCatalogProducts(catalogue) }
        val paths = products.mapNotNullTo(LinkedHashSet()) { it.freqResponse }.toList()
        mutableState.update { it.copy(totalFiles = paths.size) }
        val files = downloadResponses(cdn, paths, catalogue.size)
        val snapshot = withContext(Dispatchers.IO) {
            val document = encodeCatalogSnapshot(
                catalogue,
                files,
                cdnBaseUrl = when (cdn) {
                    CatalogCdn.CHINA -> CATALOG_CHINA_CDN_URL
                    CatalogCdn.OVERSEAS -> CATALOG_OVERSEAS_CDN_URL
                },
            )
            decodeCatalogSnapshot(document)
        }
        commit(snapshot, CatalogOrigin.PULL)
    }

    suspend fun prepareImport(bytes: ByteArray): CatalogSnapshot = ownedOperation {
        require(bytes.size <= MAX_CATALOG_SNAPSHOT_BYTES) { "Snapshot exceeds 64 MiB" }
        withContext(Dispatchers.IO) { decodeCatalogSnapshot(bytes.copyOf()) }
    }

    suspend fun importSnapshot(snapshot: CatalogSnapshot) = update { commit ->
        // Validate the actual document to be persisted, rather than trusting caller-built projections.
        require(snapshot.documentBytes.size <= MAX_CATALOG_SNAPSHOT_BYTES) { "Snapshot exceeds 64 MiB" }
        val validated = withContext(Dispatchers.IO) { decodeCatalogSnapshot(snapshot.documentBytes.copyOf()) }
        commit(validated, CatalogOrigin.IMPORT)
    }

    /** Captures only the last committed document, never partially downloaded assets. */
    fun exportBytes(): ByteArray = mutableState.value.snapshot?.documentBytes?.copyOf()
        ?: throw IllegalStateException("No valid catalog snapshot is available to export")

    fun close() {
        if (!lifetime.isActive) return
        lifetime.cancel(CancellationException("Catalog repository is closed"))
        fetcher.close()
    }

    private suspend fun downloadResponses(
        cdn: CatalogCdn,
        paths: List<String>,
        catalogueSize: Int,
    ): Map<String, ByteArray> {
        val progressMutex = Mutex()
        val files = HashMap<String, ByteArray>()
        var nextPath = 0
        var assetBytes = catalogueSize
        coroutineScope {
            repeat(minOf(4, paths.size)) {
                launch {
                    while (true) {
                        val path = progressMutex.withLock {
                            if (nextPath == paths.size) null else paths[nextPath++]
                        } ?: break
                        val bytes = fetcher.fetch(catalogResponseUrl(cdn, path), MAX_RESPONSE_FILE_BYTES)
                        require(bytes.size <= MAX_RESPONSE_FILE_BYTES) { "Frequency response $path exceeds 2 MiB" }
                        progressMutex.withLock {
                            require(bytes.size <= MAX_CATALOG_ASSET_BYTES - assetBytes) { "Decoded assets exceed 48 MiB" }
                            assetBytes += bytes.size
                            files[path] = bytes
                            mutableState.update { it.copy(completedFiles = files.size) }
                        }
                    }
                }
            }
        }
        return paths.associateWith { files.getValue(it) }
    }

    private suspend fun <T> ownedOperation(block: suspend () -> T): T = coroutineScope {
        lifetime.ensureActive()
        val operation = currentCoroutineContext()[Job]!!
        val registration = lifetime.invokeOnCompletion { operation.cancel(CancellationException("Catalog repository is closed")) }
        try {
            currentCoroutineContext().ensureActive()
            block()
        } finally {
            registration.dispose()
        }
    }

    private suspend fun update(
        block: suspend (commit: suspend (CatalogSnapshot, CatalogOrigin) -> Unit) -> Unit,
    ) {
        lifetime.ensureActive()
        check(updateMutex.tryLock()) { "A catalog operation is already in progress" }
        var committed = false
        mutableState.update { it.copy(busy = true, completedFiles = 0, totalFiles = 0, error = null) }
        try {
            ownedOperation {
                block { snapshot, origin ->
                    currentCoroutineContext().ensureActive()
                    lifetime.ensureActive()
                    // Once the atomic replacement starts, disk and publication are one commit.
                    withContext(NonCancellable + Dispatchers.IO) {
                        storage.writeActive(snapshot.documentBytes)
                        mutableState.value = CatalogState(
                            snapshot = snapshot,
                            loading = false,
                            busy = true,
                            completedFiles = snapshot.responsesByPath.size,
                            totalFiles = snapshot.responsesByPath.size,
                            origin = origin,
                        )
                        committed = true
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            if (!committed) {
                mutableState.update { it.copy(error = "Catalog operation was cancelled") }
                throw cancelled
            }
            // withContext/coroutineScope can report caller cancellation after a successful commit.
            // Do not report a cancellation when the active file and published snapshot changed.
        } catch (error: Exception) {
            mutableState.update { it.copy(error = error.description()) }
        } finally {
            mutableState.update { it.copy(loading = false, busy = false) }
            updateMutex.unlock()
        }
    }

    private fun Exception.description(): String = message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: "Catalog operation failed"
}
