package ink.lipoly.app.sunrise.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

internal const val CATALOG_EXPORT_NAME = "sunrise-moondrop-catalog.json"

/** Offline catalogue instance. Pages own their operation jobs, never this lifetime. */
internal class Catalog {
    private val mutableState = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = mutableState.asStateFlow()
    private val lifecycleMutex = Mutex()
    private var runtime: Runtime? = null
    private var hasInitialized = false

    private class Runtime(
        val repository: Repository,
        val scope: CoroutineScope,
        val initialLoadJob: Job,
    )

    /** First initializer owns dependencies until close has completely drained them. */
    suspend fun init(
        storage: () -> CatalogStorage,
        loadBundled: suspend () -> ByteArray,
        fetcher: () -> CatalogByteFetcher = ::KtorCatalogFetcher,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) {
        lifecycleMutex.withLock {
            if (runtime != null) return
            val storageInstance = storage()
            val fetcherInstance = fetcher()
            var scope: CoroutineScope? = null
            try {
                val repository = Repository(storageInstance, loadBundled, fetcherInstance, mutableState)
                val initialScope = CoroutineScope(SupervisorJob() + dispatcher)
                scope = initialScope
                val loading = initialScope.launch(start = CoroutineStart.LAZY) { repository.loadLocal() }
                val next = Runtime(repository, initialScope, loading)
                mutableState.value = CatalogState()
                runtime = next
                hasInitialized = true
                loading.start()
            } catch (failure: Throwable) {
                scope?.cancel()
                try { fetcherInstance.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
                throw failure
            }
        }
    }

    private suspend fun requireRepository(): Repository = lifecycleMutex.withLock {
        runtime?.repository ?: if (hasInitialized) {
            throw CancellationException("Catalog repository is closed")
        } else {
            throw IllegalStateException("Catalog is not initialized")
        }
    }

    suspend fun loadLocal() { requireRepository().loadLocal() }
    suspend fun pull(cdn: CatalogCdn) { requireRepository().pull(cdn) }
    suspend fun prepareImport(bytes: ByteArray): CatalogSnapshot = requireRepository().prepareImport(bytes)
    suspend fun importSnapshot(snapshot: CatalogSnapshot) { requireRepository().importSnapshot(snapshot) }

    fun exportBytes(): ByteArray = currentSnapshot()?.documentBytes?.copyOf()
        ?: throw IllegalStateException("No valid catalog snapshot is available to export")

    /** Cancellation cannot leave an old commit racing a new runtime. */
    suspend fun close() = withContext(NonCancellable) {
        lifecycleMutex.withLock {
            val closing = runtime ?: return@withLock
            try {
                closing.repository.close()
            } finally {
                try {
                    closing.scope.cancel()
                    closing.initialLoadJob.join()
                    closing.scope.coroutineContext[Job]!!.join()
                    closing.repository.awaitClosed()
                } finally {
                    runtime = null
                    mutableState.update {
                        if (it.loading || it.busy) it.copy(loading = false, busy = false) else it
                    }
                }
            }
        }
    }

    private fun currentSnapshot(): CatalogSnapshot? = mutableState.value.snapshot
    fun getAll(): List<CatalogProduct> = currentSnapshot()?.products ?: emptyList()
    fun getHaveResponse(): List<CatalogProduct> {
        val snapshot = currentSnapshot() ?: return emptyList()
        return snapshot.products.filter { snapshot.responsesByPath[it.freqResponse] is CatalogResponse.Ready }
    }
    fun getBluetooth(): List<CatalogProduct> {
        val snapshot = currentSnapshot() ?: return emptyList()
        return snapshot.products.filter { it.type == "BT" }
    }
    fun getProduct(uuid: String): CatalogProduct? = currentSnapshot()?.productsByUuid?.get(uuid)
    fun getResponse(uuid: String): CatalogResponse? {
        val snapshot = currentSnapshot() ?: return null
        val product = snapshot.productsByUuid[uuid] ?: return null
        return product.freqResponse?.let { snapshot.responsesByPath[it] }
    }


    private class Repository(
        private val storage: CatalogStorage,
        private val loadBundled: suspend () -> ByteArray,
        private val fetcher: CatalogByteFetcher,
        private val mutableState: MutableStateFlow<CatalogState>,
    ) {
        private val updateMutex = Mutex()
        private val lifetime = Job()
        private val operationLock = Any()
        private val activeOperationJobs = mutableSetOf<Job>()

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
            val catalogueProducts = withContext(Dispatchers.IO) { parseCatalogProducts(catalogue) }
            val responseLibrary = fetcher.fetch(CATALOG_RESPONSE_LIBRARY_URL, MAX_CATALOGUE_BYTES)
            val products = withContext(Dispatchers.IO) {
                mergeCatalogProducts(catalogueProducts, parseCatalogResponseLibrary(responseLibrary))
            }
            val paths = products.mapNotNullTo(LinkedHashSet()) { it.freqResponse }.toList()
            mutableState.update { it.copy(totalFiles = paths.size) }
            val files = downloadResponses(cdn, paths, catalogue.size + responseLibrary.size)
            val snapshot = withContext(Dispatchers.IO) {
                val document = encodeCatalogSnapshot(
                    catalogue,
                    responseLibrary,
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

        fun close() {
            val firstClose = catalogSynchronized(operationLock) {
                if (!lifetime.isActive) false else {
                    lifetime.cancel(CancellationException("Catalog repository is closed"))
                    true
                }
            }
            if (firstClose) fetcher.close()
        }

        suspend fun awaitClosed() {
            check(!lifetime.isActive)
            val operations = catalogSynchronized(operationLock) { activeOperationJobs.toList() }
            for (operation in operations) operation.join()
            updateMutex.withLock { }
        }

        private suspend fun downloadResponses(
            cdn: CatalogCdn,
            paths: List<String>,
            metadataSize: Int,
        ): Map<String, ByteArray> {
            val progressMutex = Mutex()
            val files = HashMap<String, ByteArray>()
            var nextPath = 0
            var assetBytes = metadataSize
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
            val operation = currentCoroutineContext()[Job]!!
            val registration = catalogSynchronized(operationLock) {
                lifetime.ensureActive()
                activeOperationJobs.add(operation)
                lifetime.invokeOnCompletion { operation.cancel(CancellationException("Catalog repository is closed")) }
            }
            try {
                currentCoroutineContext().ensureActive()
                block()
            } finally {
                registration.dispose()
                catalogSynchronized(operationLock) { activeOperationJobs.remove(operation) }
            }
        }

        private suspend fun update(
            block: suspend (commit: suspend (CatalogSnapshot, CatalogOrigin) -> Unit) -> Unit,
        ) {
            var committed = false
            try {
                ownedOperation {
                    check(updateMutex.tryLock()) { "A catalog operation is already in progress" }
                    try {
                        lifetime.ensureActive()
                        mutableState.update { it.copy(busy = true, completedFiles = 0, totalFiles = 0, error = null) }
                        block { snapshot, origin ->
                            currentCoroutineContext().ensureActive()
                            lifetime.ensureActive()
                            // Disk and publication finish together once the atomic replacement starts.
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
                    } catch (cancelled: CancellationException) {
                        if (!committed) {
                            mutableState.update { it.copy(error = "Catalog operation was cancelled") }
                            throw cancelled
                        }
                    } catch (error: Exception) {
                        mutableState.update { it.copy(error = error.description()) }
                    } finally {
                        mutableState.update { it.copy(loading = false, busy = false) }
                        updateMutex.unlock()
                    }
                }
            } catch (cancelled: CancellationException) {
                // coroutineScope can report caller cancellation after disk and publication won.
                if (!committed) throw cancelled
            }
        }

        private fun Exception.description(): String = message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: "Catalog operation failed"
    }
}
