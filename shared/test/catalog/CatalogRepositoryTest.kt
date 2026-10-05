package ink.lipoly.app.sunrise.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

class CatalogRepositoryTest {
    private val fixtures = CatalogTestFixtures

    @Test fun activeLoadImportPreparationAndOriginalExportAreEntirelyOffline() = runTest {
        val original = " \n".encodeToByteArray() + fixtures.document() + "\r\n".encodeToByteArray()
        val storage = MemoryStorage(original)
        val fetcher = Fetcher { _, _ -> error("Offline operation made a request") }
        var bundledReads = 0
        val repository = CatalogRepository(storage, { bundledReads++; fixtures.document() }, fetcher)
        try {
            repository.loadLocal()
            assertEquals(CatalogOrigin.LOCAL, repository.state.value.origin)
            assertContentEquals(original, repository.exportBytes())
            assertEquals(0, bundledReads)
            assertEquals(0, storage.writes)
            val exported = repository.exportBytes()
            exported[0] = '!'.code.toByte()
            assertContentEquals(original, repository.exportBytes())
            val prepared = repository.prepareImport(original)
            assertContentEquals(original, prepared.documentBytes)
            assertContentEquals(original, repository.exportBytes())
            repository.importSnapshot(prepared)
            assertEquals(CatalogOrigin.IMPORT, repository.state.value.origin)
            assertContentEquals(original, storage.active)
            assertContentEquals(original, repository.exportBytes())
            assertEquals(1, storage.writes)
            assertTrue(fetcher.requests.isEmpty())
            val restarted = CatalogRepository(storage, { error("Should not load bundled") }, fetcher)
            restarted.loadLocal()
            assertEquals(CatalogOrigin.LOCAL, restarted.state.value.origin)
            assertContentEquals(original, restarted.exportBytes())
            restarted.close()
        } finally { repository.close() }
    }

    @Test fun firstInstallationUsesBundledWithoutCopyingItToStorage() = runTest {
        val storage = MemoryStorage()
        val fetcher = Fetcher { _, _ -> error("Unexpected network") }
        val bundled = fixtures.document()
        val repository = CatalogRepository(storage, { bundled }, fetcher)
        try {
            repository.loadLocal()
            assertEquals(CatalogOrigin.BUNDLED, repository.state.value.origin)
            assertFalse(repository.state.value.loading)
            assertFalse(repository.state.value.busy)
            assertNull(repository.state.value.error)
            assertNull(repository.state.value.warning)
            assertNull(storage.active)
            assertEquals(0, storage.writes)
            assertContentEquals(bundled, repository.exportBytes())
            assertTrue(fetcher.requests.isEmpty())
        } finally { repository.close() }
    }

    @Test fun corruptActiveAndReadFailureFallBackWithoutRepairingTheActiveFile() = runTest {
        for (readFails in listOf(false, true)) {
            val broken = "broken active".encodeToByteArray()
            val storage = MemoryStorage(broken).apply {
                if (readFails) readFailure = IllegalStateException("Storage read denied")
            }
            val fetcher = Fetcher { _, _ -> error("Unexpected network") }
            val bundled = fixtures.document()
            val repository = CatalogRepository(storage, { bundled }, fetcher)
            try {
                repository.loadLocal()
                assertEquals(CatalogOrigin.BUNDLED, repository.state.value.origin)
                assertNotNull(repository.state.value.warning)
                assertNull(repository.state.value.error)
                assertContentEquals(broken, storage.active)
                assertContentEquals(bundled, repository.exportBytes())
                assertEquals(0, storage.writes)
                assertTrue(fetcher.requests.isEmpty())
            } finally { repository.close() }
        }
    }

    @Test fun brokenBundledIsAnExplicitFailureAndImportCanRecoverWithoutNetwork() = runTest {
        val storage = MemoryStorage("broken active".encodeToByteArray())
        val fetcher = Fetcher { _, _ -> error("Unexpected network") }
        val repository = CatalogRepository(storage, { "broken bundled".encodeToByteArray() }, fetcher)
        try {
            assertFailsWith<IllegalStateException> { repository.exportBytes() }
            repository.loadLocal()
            assertNull(repository.state.value.snapshot)
            assertNull(repository.state.value.origin)
            assertNotNull(repository.state.value.error)
            assertNotNull(repository.state.value.warning)
            assertFalse(repository.state.value.loading)
            assertFalse(repository.state.value.busy)
            assertEquals(0, storage.writes)
            assertFailsWith<IllegalStateException> { repository.exportBytes() }
            repository.importSnapshot(repository.prepareImport(fixtures.document()))
            assertNotNull(repository.state.value.snapshot)
            assertNull(repository.state.value.error)
            assertNull(repository.state.value.warning)
            assertEquals(CatalogOrigin.IMPORT, repository.state.value.origin)
            assertTrue(fetcher.requests.isEmpty())
        } finally { repository.close() }
    }

    @Test fun reloadFailureRetainsAnAlreadyUsableSnapshot() = runTest {
        val storage = MemoryStorage(fixtures.document())
        val repository = CatalogRepository(storage, { error("Bundled unavailable") }, Fetcher { _, _ -> error("Unexpected network") })
        try {
            repository.loadLocal()
            val previous = repository.state.value.snapshot!!
            storage.readFailure = IllegalStateException("Active unreadable")
            repository.loadLocal()
            assertSame(previous, repository.state.value.snapshot)
            assertContentEquals(previous.documentBytes, repository.exportBytes())
            assertNotNull(repository.state.value.error)
            assertNotNull(repository.state.value.warning)
            assertEquals(0, storage.writes)
        } finally { repository.close() }
    }

    @Test fun explicitPullDownloadsEveryDistinctPathOnceAndReplacesRatherThanMerges() = runTest {
        val newPath = "BT/新 型号%.txt"
        val catalogue = fixtures.catalogue(listOf(
            fixtures.product(uuid = fixtures.SECOND_UUID, name = "New model", path = newPath),
            fixtures.product(uuid = uuid(3), name = "New model", path = newPath, language = "zh-CN"),
            fixtures.product(uuid = uuid(4), name = "No response", path = null),
        ))
        val response = "* preserved vendor text\r\n100 12\r\n1000 18\r\n".encodeToByteArray()
        val storage = MemoryStorage(fixtures.document())
        val fetcher = Fetcher { url, _ ->
            when (url) {
                CATALOGUE_URL -> catalogue
                catalogResponseUrl(CatalogCdn.OVERSEAS, newPath) -> response
                else -> error("Unexpected URL $url")
            }
        }
        val repository = CatalogRepository(storage, { error("Unexpected bundled load") }, fetcher)
        try {
            repository.loadLocal()
            assertTrue(fetcher.requests.isEmpty())
            repository.pull(CatalogCdn.OVERSEAS)
            val state = repository.state.value
            val snapshot = state.snapshot!!
            assertNull(state.error)
            assertEquals(CatalogOrigin.PULL, state.origin)
            assertEquals(1, state.completedFiles)
            assertEquals(1, state.totalFiles)
            assertFalse(state.busy)
            assertEquals(setOf(fixtures.SECOND_UUID, uuid(3), uuid(4)), snapshot.productsByUuid.keys)
            assertNull(snapshot.productsByUuid[fixtures.UUID])
            assertEquals(setOf(newPath), snapshot.responsesByPath.keys)
            assertEquals(CATALOG_OVERSEAS_CDN_URL, snapshot.cdnBaseUrl)
            assertEquals(CATALOGUE_URL, snapshot.catalogueUrl)
            assertEquals(listOf(CATALOGUE_URL, catalogResponseUrl(CatalogCdn.OVERSEAS, newPath)), fetcher.requests.map { it.first })
            val root = fixtures.root(repository.exportBytes())
            assertEquals(fixtures.root(catalogue), root.getValue("catalogue"))
            assertContentEquals(response, fixtures.assetBytes((root.getValue("responseFiles") as JsonArray).single() as JsonObject))
            assertContentEquals(repository.exportBytes(), storage.active)
            assertEquals(1, storage.writes)
        } finally { repository.close() }
    }

    @Test fun completePullWithUnsupportedCurveStoresOriginalAssetAsUnavailable() = runTest {
        val unsupported = "* unknown third column\r\n100 40 0\r\n1000 60 0\r\n".encodeToByteArray()
        val storage = MemoryStorage(fixtures.document())
        val repository = CatalogRepository(storage, { error("Unexpected bundled load") }, Fetcher { url, _ ->
            if (url == CATALOGUE_URL) fixtures.catalogue() else unsupported
        })
        try {
            repository.loadLocal()
            repository.pull(CatalogCdn.CHINA)
            assertNull(repository.state.value.error)
            assertIs<CatalogResponse.Unavailable>(repository.state.value.snapshot!!.responsesByPath.getValue(fixtures.PATH))
            val asset = (fixtures.root(repository.exportBytes()).getValue("responseFiles") as JsonArray).single() as JsonObject
            assertContentEquals(unsupported, fixtures.assetBytes(asset))
            assertContentEquals(repository.exportBytes(), storage.active)
        } finally { repository.close() }
    }

    @Test fun nthAssetFailureNeverPublishesPartialDirectoryOrUsesOldAssets() = runTest {
        val paths = (1..7).map { "new/response-$it.txt" }
        val catalogue = catalogueWithPaths(paths)
        for (failedAsset in listOf(1, 2, 7)) {
            val storage = MemoryStorage(fixtures.document())
            var assetRequests = 0
            val repository = CatalogRepository(storage, { error("Unexpected bundled load") }, Fetcher { url, _ ->
                if (url == CATALOGUE_URL) catalogue else {
                    assetRequests++
                    if (assetRequests == failedAsset) error("Asset $failedAsset unavailable")
                    fixtures.responseBytes
                }
            })
            try {
                repository.loadLocal()
                val previous = repository.state.value.snapshot!!
                repository.pull(CatalogCdn.CHINA)
                assertRetained(repository, storage, previous)
                assertNotNull(repository.state.value.error)
                assertEquals(failedAsset, assetRequests)
            } finally { repository.close() }
        }
    }

    @Test fun invalidCatalogueStopsBeforeAnyResponseRequestAndKeepsOldSnapshot() = runTest {
        for (catalogue in listOf(
            "not JSON".encodeToByteArray(),
            fixtures.catalogue(listOf(fixtures.product(path = "../unsafe.txt"))),
            fixtures.catalogue(listOf(fixtures.product(), fixtures.product())),
            ByteArray(MAX_CATALOGUE_BYTES + 1),
        )) {
            val storage = MemoryStorage(fixtures.document())
            val fetcher = Fetcher { _, _ -> catalogue }
            val repository = CatalogRepository(storage, { error("Unexpected bundled load") }, fetcher)
            try {
                repository.loadLocal()
                val previous = repository.state.value.snapshot!!
                repository.pull(CatalogCdn.CHINA)
                assertRetained(repository, storage, previous)
                assertNotNull(repository.state.value.error)
                assertEquals(listOf(CATALOGUE_URL), fetcher.requests.map { it.first })
            } finally { repository.close() }
        }
    }

    @Test fun responseAndAggregateCapsRejectEvenAnInjectedFetcherThatIgnoresItsLimit() = runTest {
        for (aggregate in listOf(false, true)) {
            val paths = (1..if (aggregate) 25 else 1).map { "large/$it.txt" }
            val catalogue = catalogueWithPaths(paths)
            val oversized = ByteArray(MAX_RESPONSE_FILE_BYTES + if (aggregate) 0 else 1)
            val storage = MemoryStorage(fixtures.document())
            val fetcher = Fetcher { url, _ -> if (url == CATALOGUE_URL) catalogue else oversized }
            val repository = CatalogRepository(storage, { error("Unexpected bundled load") }, fetcher)
            try {
                repository.loadLocal()
                val previous = repository.state.value.snapshot!!
                repository.pull(CatalogCdn.CHINA)
                assertRetained(repository, storage, previous)
                assertNotNull(repository.state.value.error)
                assertEquals(MAX_CATALOGUE_BYTES, fetcher.requests.first().second)
                assertTrue(fetcher.requests.drop(1).all { it.second == MAX_RESPONSE_FILE_BYTES })
                assertTrue(repository.state.value.completedFiles < paths.size)
            } finally { repository.close() }
        }
    }

    @Test fun fourWorkerLimitProgressAndExportKeepServingTheOldSnapshotUntilCommit() = runTest {
        val paths = (1..6).map { "parallel/$it.txt" }
        val catalogue = catalogueWithPaths(paths)
        val entered = Channel<String>(Channel.UNLIMITED)
        val releases = paths.associate { catalogResponseUrl(CatalogCdn.CHINA, it) to CompletableDeferred<Unit>() }
        var inFlight = 0
        var peak = 0
        val storage = MemoryStorage(fixtures.document())
        val fetcher = Fetcher { url, _ ->
            if (url == CATALOGUE_URL) catalogue else {
                inFlight++
                peak = maxOf(peak, inFlight)
                entered.send(url)
                try { releases.getValue(url).await(); fixtures.responseBytes } finally { inFlight-- }
            }
        }
        val repository = CatalogRepository(storage, { error("Unexpected bundled load") }, fetcher)
        try {
            repository.loadLocal()
            val previous = repository.state.value.snapshot!!
            val pulling = launch { repository.pull(CatalogCdn.CHINA) }
            val firstFour = List(4) { entered.receive() }
            assertEquals(4, inFlight)
            assertEquals(6, repository.state.value.totalFiles)
            assertEquals(0, repository.state.value.completedFiles)
            assertTrue(repository.state.value.busy)
            assertSame(previous, repository.state.value.snapshot)
            assertContentEquals(previous.documentBytes, repository.exportBytes())
            assertEquals(0, storage.writes)
            releases.getValue(firstFour.first()).complete(Unit)
            val fifth = entered.receive()
            assertEquals(1, repository.state.value.completedFiles)
            assertSame(previous, repository.state.value.snapshot)
            assertContentEquals(previous.documentBytes, storage.active)
            releases.getValue(fifth).complete(Unit)
            val sixth = entered.receive()
            for (url in firstFour.drop(1) + sixth) releases.getValue(url).complete(Unit)
            pulling.join()
            assertEquals(4, peak)
            assertEquals(0, inFlight)
            assertEquals(6, repository.state.value.completedFiles)
            assertEquals(6, repository.state.value.totalFiles)
            assertNull(repository.state.value.error)
            assertEquals(1, storage.writes)
            assertEquals(7, fetcher.requests.size)
            assertContentEquals(repository.exportBytes(), storage.active)
        } finally { repository.close() }
    }

    @Test fun directoryAndMidAssetCancellationPropagateAndLeaveOldDiskAndState() = runTest {
        for (cancelDirectory in listOf(true, false)) {
            val entered = CompletableDeferred<Unit>()
            val blocker = CompletableDeferred<Unit>()
            var fetchCancelled = false
            val storage = MemoryStorage(fixtures.document())
            val repository = CatalogRepository(storage, { error("Unexpected bundled load") }, Fetcher { url, _ ->
                if (!cancelDirectory && url == CATALOGUE_URL) fixtures.catalogue() else {
                    entered.complete(Unit)
                    try { blocker.await(); error("Must be cancelled") } finally {
                        fetchCancelled = !currentCoroutineContext().isActive
                    }
                }
            })
            try {
                repository.loadLocal()
                val previous = repository.state.value.snapshot!!
                var propagated = false
                val pulling = launch {
                    try { repository.pull(CatalogCdn.CHINA) } catch (_: CancellationException) { propagated = true }
                }
                entered.await()
                pulling.cancel()
                pulling.join()
                assertTrue(propagated)
                assertTrue(fetchCancelled)
                assertRetained(repository, storage, previous)
                assertNotNull(repository.state.value.error)
            } finally { repository.close() }
        }
    }

    @Test fun concurrentPullAndImportAreRejectedImmediatelyRatherThanQueued() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val storage = MemoryStorage(fixtures.document())
        val fetcher = Fetcher { url, _ ->
            if (url == CATALOGUE_URL) {
                entered.complete(Unit)
                release.await()
                fixtures.catalogue()
            } else fixtures.responseBytes
        }
        val repository = CatalogRepository(storage, { error("Unexpected bundled load") }, fetcher)
        try {
            repository.loadLocal()
            val imported = repository.prepareImport(fixtures.document())
            val pulling = launch { repository.pull(CatalogCdn.CHINA) }
            entered.await()
            assertFailsWith<IllegalStateException> { repository.pull(CatalogCdn.OVERSEAS) }
            assertFailsWith<IllegalStateException> { repository.importSnapshot(imported) }
            assertTrue(repository.state.value.busy)
            assertEquals(1, fetcher.requests.size)
            assertEquals(0, storage.writes)
            release.complete(Unit)
            pulling.join()
            assertEquals(CatalogOrigin.PULL, repository.state.value.origin)
            assertNull(repository.state.value.error)
            assertEquals(2, fetcher.requests.size)
            assertEquals(1, storage.writes)
        } finally { repository.close() }
    }

    @Test fun failedAtomicWriteAfterPullOrImportPreservesOldSnapshotAndFile() = runTest {
        for (importing in listOf(false, true)) {
            val storage = MemoryStorage(fixtures.document()).apply { writeFailure = IllegalStateException("Atomic replacement denied") }
            val newCatalogue = fixtures.catalogue(listOf(fixtures.product(uuid = fixtures.SECOND_UUID)))
            val fetcher = Fetcher { url, _ -> if (url == CATALOGUE_URL) newCatalogue else fixtures.responseBytes }
            val repository = CatalogRepository(storage, { error("Unexpected bundled load") }, fetcher)
            try {
                repository.loadLocal()
                val previous = repository.state.value.snapshot!!
                if (importing) repository.importSnapshot(repository.prepareImport(fixtures.document(newCatalogue)))
                else repository.pull(CatalogCdn.CHINA)
                assertRetained(repository, storage, previous)
                assertNotNull(repository.state.value.error)
                assertEquals(1, storage.writeAttempts)
                if (importing) assertTrue(fetcher.requests.isEmpty())
            } finally { repository.close() }
        }
    }

    @Test fun cancellationDuringAtomicCommitReturnsSuccessAfterDiskAndPublicationWin() = runTest {
        for (importing in listOf(false, true)) {
            val enteredWrite = CompletableDeferred<Unit>()
            val releaseWrite = CompletableDeferred<Unit>()
            val storage = MemoryStorage(fixtures.document()).apply {
                beforeWrite = { enteredWrite.complete(Unit); releaseWrite.await() }
            }
            val catalogue = fixtures.catalogue(listOf(fixtures.product(uuid = fixtures.SECOND_UUID)))
            val repository = CatalogRepository(storage, { error("Unexpected bundled load") }, Fetcher { url, _ ->
                if (url == CATALOGUE_URL) catalogue else fixtures.responseBytes
            })
            try {
                repository.loadLocal()
                val prepared = repository.prepareImport(fixtures.document(catalogue))
                val previous = repository.state.value.snapshot!!
                var returnedSuccess = false
                var returnedCancellation = false
                val updating = launch {
                    try {
                        if (importing) repository.importSnapshot(prepared) else repository.pull(CatalogCdn.CHINA)
                        returnedSuccess = true
                    } catch (_: CancellationException) { returnedCancellation = true }
                }
                enteredWrite.await()
                updating.cancel()
                assertSame(previous, repository.state.value.snapshot)
                assertContentEquals(previous.documentBytes, storage.active)
                releaseWrite.complete(Unit)
                updating.join()
                assertTrue(returnedSuccess)
                assertFalse(returnedCancellation)
                assertEquals(setOf(fixtures.SECOND_UUID), repository.state.value.snapshot!!.productsByUuid.keys)
                assertEquals(if (importing) CatalogOrigin.IMPORT else CatalogOrigin.PULL, repository.state.value.origin)
                assertNull(repository.state.value.error)
                assertFalse(repository.state.value.busy)
                assertEquals(1, storage.writes)
                assertContentEquals(repository.exportBytes(), storage.active)
            } finally { repository.close() }
        }
    }

    @Test fun invalidImportPreparationNeverChangesTheCurrentStateOrTouchesNetworkOrStorage() = runTest {
        val storage = MemoryStorage(fixtures.document())
        val fetcher = Fetcher { _, _ -> error("Unexpected network") }
        val repository = CatalogRepository(storage, { error("Unexpected bundled load") }, fetcher)
        try {
            repository.loadLocal()
            val previous = repository.state.value.snapshot!!
            val document = fixtures.document()
            val file = (fixtures.root(document).getValue("responseFiles") as JsonArray).single() as JsonObject
            val brokenHash = JsonObject(file + ("sha256" to JsonPrimitive("0".repeat(64))))
            for (invalid in listOf(
                fixtures.changed(document, "schemaVersion", JsonPrimitive(1)),
                fixtures.changed(document, "responseFiles", JsonArray(listOf(brokenHash))),
                fixtures.document(responseFiles = emptyMap()),
                fixtures.document(fixtures.catalogue(listOf(fixtures.product(), fixtures.product()))),
                ByteArray(MAX_CATALOG_SNAPSHOT_BYTES + 1),
            )) {
                assertFailsWith<IllegalArgumentException> { repository.prepareImport(invalid) }
                assertRetained(repository, storage, previous)
                assertNull(repository.state.value.error)
            }
            assertTrue(fetcher.requests.isEmpty())
        } finally { repository.close() }
    }

    @Test fun closeCancelsDownloadAndClosesFetcherWithoutDiscardingTheLastSnapshot() = runTest {
        val entered = CompletableDeferred<Unit>()
        val blocker = CompletableDeferred<Unit>()
        var cancelled = false
        val storage = MemoryStorage(fixtures.document())
        val fetcher = Fetcher { _, _ ->
            entered.complete(Unit)
            try { blocker.await(); error("Must be closed") } finally { cancelled = !currentCoroutineContext().isActive }
        }
        val repository = CatalogRepository(storage, { error("Unexpected bundled load") }, fetcher)
        repository.loadLocal()
        val previous = repository.state.value.snapshot!!
        var propagated = false
        val pulling = launch {
            try { repository.pull(CatalogCdn.CHINA) } catch (_: CancellationException) { propagated = true }
        }
        entered.await()
        repository.close()
        pulling.join()
        repository.close()
        assertTrue(cancelled)
        assertTrue(propagated)
        assertEquals(1, fetcher.closes)
        assertRetained(repository, storage, previous)
        assertFailsWith<CancellationException> { repository.pull(CatalogCdn.CHINA) }
        assertEquals(1, fetcher.requests.size)
    }

    @Test fun closeDuringLocalReadCannotPublishALateBundledSnapshot() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val storage = MemoryStorage()
        val fetcher = Fetcher { _, _ -> error("Unexpected network") }
        val repository = CatalogRepository(storage, {
            entered.complete(Unit)
            withContext(NonCancellable) {
                release.await()
                fixtures.document()
            }
        }, fetcher)
        var propagated = false
        val loading = launch {
            try { repository.loadLocal() } catch (_: CancellationException) { propagated = true }
        }
        entered.await()
        repository.close()
        release.complete(Unit)
        loading.join()
        assertTrue(propagated)
        assertNull(repository.state.value.snapshot)
        assertFalse(repository.state.value.loading)
        assertFalse(repository.state.value.busy)
        assertEquals(0, storage.writes)
        assertTrue(fetcher.requests.isEmpty())
        assertEquals(1, fetcher.closes)
    }

    @Test fun cancellationImmediatelyAfterLastDownloadStillPreventsAtomicWrite() = runTest {
        val storage = MemoryStorage(fixtures.document())
        var callerJob: kotlinx.coroutines.Job? = null
        val fetcher = Fetcher { url, _ ->
            if (url == CATALOGUE_URL) fixtures.catalogue() else {
                callerJob!!.cancel(CancellationException("Cancel before commit"))
                fixtures.responseBytes
            }
        }
        val repository = CatalogRepository(storage, { error("Unexpected bundled load") }, fetcher)
        try {
            repository.loadLocal()
            val previous = repository.state.value.snapshot!!
            var propagated = false
            val pulling = launch {
                callerJob = currentCoroutineContext()[kotlinx.coroutines.Job]
                try { repository.pull(CatalogCdn.CHINA) } catch (_: CancellationException) { propagated = true }
            }
            pulling.join()
            assertTrue(propagated)
            assertRetained(repository, storage, previous)
            assertEquals(0, storage.writeAttempts)
            assertNotNull(repository.state.value.error)
        } finally { repository.close() }
    }

    private fun assertRetained(repository: CatalogRepository, storage: MemoryStorage, snapshot: CatalogSnapshot) {
        assertSame(snapshot, repository.state.value.snapshot)
        assertContentEquals(snapshot.documentBytes, repository.exportBytes())
        assertContentEquals(snapshot.documentBytes, storage.active)
        assertFalse(repository.state.value.busy)
        assertEquals(CatalogOrigin.LOCAL, repository.state.value.origin)
        assertEquals(0, storage.writes)
    }

    private fun uuid(index: Int): String = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"

    private fun catalogueWithPaths(paths: List<String>): ByteArray = fixtures.catalogue(paths.mapIndexed { index, path ->
        fixtures.product(uuid = uuid(index + 1), name = "Model ${index + 1}", path = path)
    })

    private class MemoryStorage(var active: ByteArray? = null) : CatalogStorage {
        var writes = 0
        var writeAttempts = 0
        var readFailure: Exception? = null
        var writeFailure: Exception? = null
        var beforeWrite: suspend () -> Unit = {}

        override suspend fun readActive(): ByteArray? {
            readFailure?.let { throw it }
            return active?.copyOf()
        }

        override suspend fun writeActive(bytes: ByteArray) {
            writeAttempts++
            beforeWrite()
            writeFailure?.let { throw it }
            active = bytes.copyOf()
            writes++
        }
    }

    private class Fetcher(private val body: suspend (String, Int) -> ByteArray) : CatalogByteFetcher {
        val requests = mutableListOf<Pair<String, Int>>()
        var closes = 0
        override suspend fun fetch(url: String, maxBytes: Int): ByteArray {
            requests += url to maxBytes
            return body(url, maxBytes)
        }
        override fun close() { closes++ }
    }
}
