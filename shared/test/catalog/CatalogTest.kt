package ink.lipoly.app.sunrise.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogTest {
    private val fixtures = CatalogTestFixtures
    @Test fun activeLoadImportPreparationAndOriginalExportAreEntirelyOffline() = runTest {
        val original = " \n".encodeToByteArray() + fixtures.document() + "\r\n".encodeToByteArray()
        val storage = MemoryStorage(original)
        val fetcher = Fetcher { _, _ -> error("Offline operation made a request") }
        var bundledReads = 0
        fixtures.withCatalog(this@runTest, storage, { bundledReads++; fixtures.document() }, fetcher) {
            awaitLoaded()
            assertEquals(CatalogOrigin.LOCAL, Catalog.state.value.origin)
            assertContentEquals(original, Catalog.exportBytes())
            assertEquals(0, bundledReads)
            assertEquals(0, storage.writes)
            val exported = Catalog.exportBytes()
            exported[0] = '!'.code.toByte()
            assertContentEquals(original, Catalog.exportBytes())
            val prepared = Catalog.prepareImport(original)
            assertContentEquals(original, prepared.documentBytes)
            assertContentEquals(original, Catalog.exportBytes())
            Catalog.importSnapshot(prepared)
            assertEquals(CatalogOrigin.IMPORT, Catalog.state.value.origin)
            assertContentEquals(original, storage.active)
            assertContentEquals(original, Catalog.exportBytes())
            assertEquals(1, storage.writes)
            assertTrue(fetcher.requests.isEmpty())
            Catalog.close()
            val restartedFetcher = Fetcher { _, _ -> error("Restart must stay offline") }
            Catalog.init(
                storage = { storage },
                loadBundled = { error("Should not load bundled") },
                fetcher = { restartedFetcher },
                dispatcher = StandardTestDispatcher(testScheduler),
            )
            awaitLoaded()
            assertEquals(CatalogOrigin.LOCAL, Catalog.state.value.origin)
            assertContentEquals(original, Catalog.exportBytes())
            assertEquals(1, fetcher.closes)
            assertTrue(restartedFetcher.requests.isEmpty())
            Catalog.close()
            assertEquals(1, restartedFetcher.closes)
        }
    }

    @Test fun firstInstallationUsesBundledWithoutCopyingItToStorage() = runTest {
        val storage = MemoryStorage()
        val fetcher = Fetcher { _, _ -> error("Unexpected network") }
        val bundled = fixtures.document()
        fixtures.withCatalog(this@runTest, storage, { bundled }, fetcher) {
            awaitLoaded()
            assertEquals(CatalogOrigin.BUNDLED, Catalog.state.value.origin)
            assertFalse(Catalog.state.value.loading)
            assertFalse(Catalog.state.value.busy)
            assertNull(Catalog.state.value.error)
            assertNull(Catalog.state.value.warning)
            assertNull(storage.active)
            assertEquals(0, storage.writes)
            assertContentEquals(bundled, Catalog.exportBytes())
            assertTrue(fetcher.requests.isEmpty())
        }
    }

    @Test fun corruptActiveAndReadFailureFallBackWithoutRepairingTheActiveFile() = runTest {
        val library = fixtures.responseLibrary(listOf(fixtures.responseEntry()))
        val bundled = fixtures.document(responseLibraryBytes = library)
        val legacy = JsonObject(fixtures.root(fixtures.document()) + mapOf(
            "format" to JsonPrimitive("sunrise-moondrop-bt"),
            "schemaVersion" to JsonPrimitive(2),
        )).toString().encodeToByteArray()
        for ((broken, readFails) in listOf(
            "broken active".encodeToByteArray() to false,
            "broken active".encodeToByteArray() to true,
            legacy to false,
        )) {
            val storage = MemoryStorage(broken).apply {
                if (readFails) readFailure = IllegalStateException("Storage read denied")
            }
            val fetcher = Fetcher { _, _ -> error("Unexpected network") }
            fixtures.withCatalog(this@runTest, storage, { bundled }, fetcher) {
                awaitLoaded()
                assertEquals(CatalogOrigin.BUNDLED, Catalog.state.value.origin)
                assertNotNull(Catalog.state.value.warning)
                assertNull(Catalog.state.value.error)
                assertContentEquals(broken, storage.active)
                assertContentEquals(bundled, Catalog.exportBytes())
                assertEquals(listOf(fixtures.UUID, fixtures.SECOND_UUID), Catalog.getAll().map { it.uuid })
                assertEquals(listOf(fixtures.UUID, fixtures.SECOND_UUID), Catalog.getHaveResponse().map { it.uuid })
                assertEquals(listOf(fixtures.UUID), Catalog.getBluetooth().map { it.uuid })
                assertEquals("Response", Catalog.getProduct(fixtures.SECOND_UUID)?.type)
                assertEquals(0, storage.writes)
                assertTrue(fetcher.requests.isEmpty())
            }
        }
    }

    @Test fun brokenBundledIsAnExplicitFailureAndImportCanRecoverWithoutNetwork() = runTest {
        val storage = MemoryStorage("broken active".encodeToByteArray())
        val fetcher = Fetcher { _, _ -> error("Unexpected network") }
        fixtures.withCatalog(this@runTest, storage, { "broken bundled".encodeToByteArray() }, fetcher) {
            assertFailsWith<IllegalStateException> { Catalog.exportBytes() }
            awaitLoaded()
            assertNull(Catalog.state.value.snapshot)
            assertNull(Catalog.state.value.origin)
            assertNotNull(Catalog.state.value.error)
            assertNotNull(Catalog.state.value.warning)
            assertFalse(Catalog.state.value.loading)
            assertFalse(Catalog.state.value.busy)
            assertEquals(0, storage.writes)
            assertFailsWith<IllegalStateException> { Catalog.exportBytes() }
            Catalog.importSnapshot(Catalog.prepareImport(fixtures.document()))
            assertNotNull(Catalog.state.value.snapshot)
            assertNull(Catalog.state.value.error)
            assertNull(Catalog.state.value.warning)
            assertEquals(CatalogOrigin.IMPORT, Catalog.state.value.origin)
            assertTrue(fetcher.requests.isEmpty())
        }
    }

    @Test fun reloadFailureRetainsAnAlreadyUsableSnapshot() = runTest {
        val storage = MemoryStorage(fixtures.document())
        fixtures.withCatalog(this@runTest, storage, { error("Bundled unavailable") }, Fetcher { _, _ -> error("Unexpected network") }) {
            awaitLoaded()
            val previous = Catalog.state.value.snapshot!!
            storage.readFailure = IllegalStateException("Active unreadable")
            Catalog.loadLocal()
            assertContentEquals(previous.documentBytes, Catalog.exportBytes())
            assertNotNull(Catalog.state.value.error)
            assertNotNull(Catalog.state.value.warning)
            assertEquals(0, storage.writes)
        }
    }

    @Test fun explicitPullDownloadsEveryDistinctPathOnceAndReplacesRatherThanMerges() = runTest {
        val newPath = "BT/新 型号%.txt"
        val catalogue = fixtures.catalogue(listOf(
            fixtures.product(uuid = fixtures.SECOND_UUID, name = "New model", path = newPath),
            fixtures.product(uuid = uuid(3), name = "New model", path = newPath, language = "zh-CN", type = "USB"),
            fixtures.product(uuid = uuid(4), name = "No response", path = null, type = "FUTURE"),
        ))
        val response = "* preserved vendor text\r\n100 12\r\n1000 18\r\n".encodeToByteArray()
        val storage = MemoryStorage(fixtures.document())
        val fetcher = Fetcher { url, _ ->
            when (url) {
                CATALOGUE_URL -> catalogue
                CATALOG_RESPONSE_LIBRARY_URL -> fixtures.responseLibrary()
                catalogResponseUrl(CatalogCdn.OVERSEAS, newPath) -> response
                else -> error("Unexpected URL $url")
            }
        }
        fixtures.withCatalog(this@runTest, storage, { error("Unexpected bundled load") }, fetcher) {
            awaitLoaded()
            assertTrue(fetcher.requests.isEmpty())
            Catalog.pull(CatalogCdn.OVERSEAS)
            val state = Catalog.state.value
            val snapshot = state.snapshot!!
            assertNull(state.error)
            assertEquals(CatalogOrigin.PULL, state.origin)
            assertEquals(1, state.completedFiles)
            assertEquals(1, state.totalFiles)
            assertFalse(state.busy)
            assertEquals(setOf(fixtures.SECOND_UUID, uuid(3), uuid(4)), snapshot.productsByUuid.keys)
            assertEquals(setOf("BT", "USB", "FUTURE"), snapshot.products.map { it.type }.toSet())
            assertNull(snapshot.productsByUuid[fixtures.UUID])
            assertEquals(setOf(newPath), snapshot.responsesByPath.keys)
            assertEquals(CATALOG_OVERSEAS_CDN_URL, snapshot.cdnBaseUrl)
            assertEquals(CATALOGUE_URL, snapshot.catalogueUrl)
            assertEquals(listOf(CATALOGUE_URL, CATALOG_RESPONSE_LIBRARY_URL, catalogResponseUrl(CatalogCdn.OVERSEAS, newPath)), fetcher.requests.map { it.first })
            val root = fixtures.root(Catalog.exportBytes())
            assertEquals(fixtures.root(catalogue), root.getValue("catalogue"))
            assertContentEquals(response, fixtures.assetBytes((root.getValue("responseFiles") as JsonArray).single() as JsonObject))
            assertContentEquals(Catalog.exportBytes(), storage.active)
            assertEquals(1, storage.writes)
        }
    }

    @Test fun completePullWithUnsupportedCurveStoresOriginalAssetAsUnavailable() = runTest {
        val unsupported = "* unknown third column\r\n100 40 0\r\n1000 60 0\r\n".encodeToByteArray()
        val storage = MemoryStorage(fixtures.document())
        fixtures.withCatalog(this@runTest, storage, { error("Unexpected bundled load") }, Fetcher { url, _ ->
            when (url) {
                CATALOGUE_URL -> fixtures.catalogue(listOf(fixtures.product(path = null)))
                CATALOG_RESPONSE_LIBRARY_URL -> fixtures.responseLibrary(listOf(fixtures.responseEntry(file = fixtures.PATH)))
                else -> unsupported
            }
        }) {
            awaitLoaded()
            Catalog.pull(CatalogCdn.CHINA)
            assertNull(Catalog.state.value.error)
            assertIs<CatalogResponse.Unavailable>(Catalog.getResponse(fixtures.SECOND_UUID))
            assertEquals("Response", Catalog.getProduct(fixtures.SECOND_UUID)?.type)
            assertEquals(listOf(fixtures.UUID, fixtures.SECOND_UUID), Catalog.getAll().map { it.uuid })
            assertTrue(Catalog.getHaveResponse().isEmpty())
            assertEquals(listOf(fixtures.UUID), Catalog.getBluetooth().map { it.uuid })
            val asset = (fixtures.root(Catalog.exportBytes()).getValue("responseFiles") as JsonArray).single() as JsonObject
            assertContentEquals(unsupported, fixtures.assetBytes(asset))
            assertContentEquals(Catalog.exportBytes(), storage.active)
        }
    }

    @Test fun nthAssetFailureNeverPublishesPartialDirectoryOrUsesOldAssets() = runTest {
        val paths = (1..7).map { "new/response-$it.txt" }
        val catalogue = catalogueWithPaths(paths)
        for (failedAsset in listOf(1, 2, 7)) {
            val storage = MemoryStorage(fixtures.document())
            var assetRequests = 0
            fixtures.withCatalog(this@runTest, storage, { error("Unexpected bundled load") }, Fetcher { url, _ ->
                if (url == CATALOGUE_URL) catalogue else if (url == CATALOG_RESPONSE_LIBRARY_URL) fixtures.responseLibrary() else {
                    assetRequests++
                    if (assetRequests == failedAsset) error("Asset $failedAsset unavailable")
                    fixtures.responseBytes
                }
            }) {
                awaitLoaded()
                val previous = Catalog.state.value.snapshot!!
                Catalog.pull(CatalogCdn.CHINA)
                assertRetained(storage, previous)
                assertNotNull(Catalog.state.value.error)
                assertEquals(failedAsset, assetRequests)
            }
        }
    }

    @Test fun responseLibraryFailureRetainsTheCompletePreviousSnapshot() = runTest {
        val library = fixtures.responseLibrary(listOf(fixtures.responseEntry()))
        val original = fixtures.document(responseLibraryBytes = library)
        for (invalidBody in listOf(false, true)) {
            val storage = MemoryStorage(original)
            val fetcher = Fetcher { url, _ ->
                when (url) {
                    CATALOGUE_URL -> fixtures.catalogue(listOf(fixtures.product(uuid = uuid(9))))
                    CATALOG_RESPONSE_LIBRARY_URL -> if (invalidBody) "{}".encodeToByteArray() else error("Library unavailable")
                    else -> error("No asset may be requested before both sources validate")
                }
            }
            fixtures.withCatalog(this@runTest, storage, { error("Unexpected bundled load") }, fetcher) {
                awaitLoaded()
                val previous = Catalog.state.value.snapshot!!
                Catalog.pull(CatalogCdn.CHINA)
                assertRetained(storage, previous)
                assertEquals(listOf(fixtures.UUID, fixtures.SECOND_UUID), Catalog.getAll().map { it.uuid })
                assertEquals(listOf(fixtures.UUID, fixtures.SECOND_UUID), Catalog.getHaveResponse().map { it.uuid })
                assertEquals(listOf(fixtures.UUID), Catalog.getBluetooth().map { it.uuid })
                assertEquals("BT", Catalog.getProduct(fixtures.UUID)?.type)
                assertEquals("Response", Catalog.getProduct(fixtures.SECOND_UUID)?.type)
                assertIs<CatalogResponse.Ready>(Catalog.getResponse(fixtures.UUID))
                assertIs<CatalogResponse.Ready>(Catalog.getResponse(fixtures.SECOND_UUID))
                assertNotNull(Catalog.state.value.error)
                assertEquals(listOf(CATALOGUE_URL, CATALOG_RESPONSE_LIBRARY_URL), fetcher.requests.map { it.first })
                assertEquals(0, storage.writeAttempts)
            }
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
            fixtures.withCatalog(this@runTest, storage, { error("Unexpected bundled load") }, fetcher) {
                awaitLoaded()
                val previous = Catalog.state.value.snapshot!!
                Catalog.pull(CatalogCdn.CHINA)
                assertRetained(storage, previous)
                assertNotNull(Catalog.state.value.error)
                assertEquals(listOf(CATALOGUE_URL), fetcher.requests.map { it.first })
            }
        }
    }

    @Test fun responseAndAggregateCapsRejectEvenAnInjectedFetcherThatIgnoresItsLimit() = runTest {
        for (aggregate in listOf(false, true)) {
            val paths = (1..if (aggregate) 25 else 1).map { "large/$it.txt" }
            val catalogue = catalogueWithPaths(paths)
            val oversized = ByteArray(MAX_RESPONSE_FILE_BYTES + if (aggregate) 0 else 1)
            val storage = MemoryStorage(fixtures.document())
            val fetcher = Fetcher { url, _ ->
                when (url) {
                    CATALOGUE_URL -> catalogue
                    CATALOG_RESPONSE_LIBRARY_URL -> fixtures.responseLibrary()
                    else -> oversized
                }
            }
            fixtures.withCatalog(this@runTest, storage, { error("Unexpected bundled load") }, fetcher) {
                awaitLoaded()
                val previous = Catalog.state.value.snapshot!!
                Catalog.pull(CatalogCdn.CHINA)
                assertRetained(storage, previous)
                assertNotNull(Catalog.state.value.error)
                assertEquals(MAX_CATALOGUE_BYTES, fetcher.requests.first().second)
                assertTrue(fetcher.requests.drop(2).all { it.second == MAX_RESPONSE_FILE_BYTES })
                assertTrue(Catalog.state.value.completedFiles < paths.size)
            }
        }
    }

    @Test fun fourWorkerLimitProgressAndExportKeepServingTheOldSnapshotUntilCommit() = runTest {
        val paths = (1..6).map { "parallel/$it.txt" }
        val catalogue = catalogueWithPaths(paths.take(5))
        val library = fixtures.responseLibrary(listOf(
            fixtures.responseEntry(uuid = uuid(7), file = paths.last()),
            fixtures.responseEntry(uuid = uuid(8), file = paths.first()),
        ))
        val entered = Channel<String>(Channel.UNLIMITED)
        val releases = paths.associate { catalogResponseUrl(CatalogCdn.CHINA, it) to CompletableDeferred<Unit>() }
        var inFlight = 0
        var peak = 0
        val storage = MemoryStorage(fixtures.document())
        val fetcher = Fetcher { url, _ ->
            if (url == CATALOGUE_URL) catalogue else if (url == CATALOG_RESPONSE_LIBRARY_URL) library else {
                inFlight++
                peak = maxOf(peak, inFlight)
                entered.send(url)
                try { releases.getValue(url).await(); fixtures.responseBytes } finally { inFlight-- }
            }
        }
        fixtures.withCatalog(this@runTest, storage, { error("Unexpected bundled load") }, fetcher) {
            try {
                awaitLoaded()
                val previous = Catalog.state.value.snapshot!!
                val pulling = launch { Catalog.pull(CatalogCdn.CHINA) }
                val firstFour = List(4) { entered.receive() }
                assertEquals(4, inFlight)
                assertEquals(6, Catalog.state.value.totalFiles)
                assertEquals(0, Catalog.state.value.completedFiles)
                assertTrue(Catalog.state.value.busy)
                assertContentEquals(previous.documentBytes, Catalog.exportBytes())
                assertEquals(listOf(fixtures.UUID), Catalog.getAll().map { it.uuid })
                assertEquals(listOf(fixtures.UUID), Catalog.getHaveResponse().map { it.uuid })
                assertEquals(listOf(fixtures.UUID), Catalog.getBluetooth().map { it.uuid })
                assertEquals(0, storage.writes)
                releases.getValue(firstFour.first()).complete(Unit)
                val fifth = entered.receive()
                assertEquals(1, Catalog.state.value.completedFiles)
                assertContentEquals(previous.documentBytes, storage.active)
                assertEquals(listOf(fixtures.UUID), Catalog.getAll().map { it.uuid })
                assertEquals(listOf(fixtures.UUID), Catalog.getHaveResponse().map { it.uuid })
                assertEquals(listOf(fixtures.UUID), Catalog.getBluetooth().map { it.uuid })
                releases.getValue(fifth).complete(Unit)
                val sixth = entered.receive()
                for (url in firstFour.drop(1) + sixth) releases.getValue(url).complete(Unit)
                pulling.join()
                assertEquals(4, peak)
                assertEquals(0, inFlight)
                assertEquals(6, Catalog.state.value.completedFiles)
                assertEquals(6, Catalog.state.value.totalFiles)
                assertNull(Catalog.state.value.error)
                assertEquals(1, storage.writes)
                assertEquals(8, fetcher.requests.size)
                assertEquals(paths.toSet(), fetcher.requests.drop(2).map { it.first }.map { url ->
                    paths.single { catalogResponseUrl(CatalogCdn.CHINA, it) == url }
                }.toSet())
                val mergedUuids = (1..5).map(::uuid) + listOf(uuid(7), uuid(8))
                assertEquals(mergedUuids, Catalog.getAll().map { it.uuid })
                assertEquals(mergedUuids, Catalog.getHaveResponse().map { it.uuid })
                assertEquals((1..5).map(::uuid), Catalog.getBluetooth().map { it.uuid })
                assertContentEquals(Catalog.exportBytes(), storage.active)
            } finally {
                releases.values.forEach { it.complete(Unit) }
            }
        }
    }

    @Test fun directoryAndMidAssetCancellationPropagateAndLeaveOldDiskAndState() = runTest {
        for (cancelStage in 0..2) {
            val entered = CompletableDeferred<Unit>()
            val blocker = CompletableDeferred<Unit>()
            var fetchCancelled = false
            val storage = MemoryStorage(fixtures.document())
            fixtures.withCatalog(this@runTest, storage, { error("Unexpected bundled load") }, Fetcher { url, _ ->
                if (cancelStage != 0 && url == CATALOGUE_URL) fixtures.catalogue()
                else if (cancelStage == 2 && url == CATALOG_RESPONSE_LIBRARY_URL) fixtures.responseLibrary() else {
                    entered.complete(Unit)
                    try { blocker.await(); error("Must be cancelled") } finally {
                        fetchCancelled = !currentCoroutineContext().isActive
                    }
                }
            }) {
                awaitLoaded()
                val previous = Catalog.state.value.snapshot!!
                var propagated = false
                val pulling = launch {
                    try { Catalog.pull(CatalogCdn.CHINA) } catch (_: CancellationException) { propagated = true }
                }
                entered.await()
                pulling.cancel()
                pulling.join()
                assertTrue(propagated)
                assertTrue(fetchCancelled)
                assertRetained(storage, previous)
                assertNotNull(Catalog.state.value.error)
            }
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
            } else if (url == CATALOG_RESPONSE_LIBRARY_URL) fixtures.responseLibrary() else fixtures.responseBytes
        }
        fixtures.withCatalog(this@runTest, storage, { error("Unexpected bundled load") }, fetcher) {
            try {
                awaitLoaded()
                val imported = Catalog.prepareImport(fixtures.document())
                val pulling = launch { Catalog.pull(CatalogCdn.CHINA) }
                entered.await()
                assertFailsWith<IllegalStateException> { Catalog.pull(CatalogCdn.OVERSEAS) }
                assertFailsWith<IllegalStateException> { Catalog.importSnapshot(imported) }
                assertTrue(Catalog.state.value.busy)
                assertEquals(1, fetcher.requests.size)
                assertEquals(0, storage.writes)
                release.complete(Unit)
                pulling.join()
                assertEquals(CatalogOrigin.PULL, Catalog.state.value.origin)
                assertNull(Catalog.state.value.error)
                assertEquals(3, fetcher.requests.size)
                assertEquals(1, storage.writes)
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test fun failedAtomicWriteAfterPullOrImportPreservesOldSnapshotAndFile() = runTest {
        for (importing in listOf(false, true)) {
            val storage = MemoryStorage(fixtures.document()).apply { writeFailure = IllegalStateException("Atomic replacement denied") }
            val newCatalogue = fixtures.catalogue(listOf(fixtures.product(uuid = fixtures.SECOND_UUID)))
            val fetcher = Fetcher { url, _ ->
                when (url) {
                    CATALOGUE_URL -> newCatalogue
                    CATALOG_RESPONSE_LIBRARY_URL -> fixtures.responseLibrary()
                    else -> fixtures.responseBytes
                }
            }
            fixtures.withCatalog(this@runTest, storage, { error("Unexpected bundled load") }, fetcher) {
                awaitLoaded()
                val previous = Catalog.state.value.snapshot!!
                if (importing) Catalog.importSnapshot(Catalog.prepareImport(fixtures.document(newCatalogue)))
                else Catalog.pull(CatalogCdn.CHINA)
                assertRetained(storage, previous)
                assertNotNull(Catalog.state.value.error)
                assertEquals(1, storage.writeAttempts)
                if (importing) assertTrue(fetcher.requests.isEmpty())
            }
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
            fixtures.withCatalog(this@runTest, storage, { error("Unexpected bundled load") }, Fetcher { url, _ ->
                when (url) {
                    CATALOGUE_URL -> catalogue
                    CATALOG_RESPONSE_LIBRARY_URL -> fixtures.responseLibrary()
                    else -> fixtures.responseBytes
                }
            }) {
                try {
                    awaitLoaded()
                    val prepared = Catalog.prepareImport(fixtures.document(catalogue))
                    val previous = Catalog.state.value.snapshot!!
                    var returnedSuccess = false
                    var returnedCancellation = false
                    val updating = launch {
                        try {
                            if (importing) Catalog.importSnapshot(prepared) else Catalog.pull(CatalogCdn.CHINA)
                            returnedSuccess = true
                        } catch (_: CancellationException) { returnedCancellation = true }
                    }
                    enteredWrite.await()
                    updating.cancel()
                    assertContentEquals(previous.documentBytes, storage.active)
                    releaseWrite.complete(Unit)
                    updating.join()
                    assertTrue(returnedSuccess)
                    assertFalse(returnedCancellation)
                    assertEquals(setOf(fixtures.SECOND_UUID), Catalog.state.value.snapshot!!.productsByUuid.keys)
                    assertEquals(if (importing) CatalogOrigin.IMPORT else CatalogOrigin.PULL, Catalog.state.value.origin)
                    assertNull(Catalog.state.value.error)
                    assertFalse(Catalog.state.value.busy)
                    assertEquals(1, storage.writes)
                    assertContentEquals(Catalog.exportBytes(), storage.active)
                } finally {
                    releaseWrite.complete(Unit)
                }
            }
        }
    }

    @Test fun invalidImportPreparationNeverChangesTheCurrentStateOrTouchesNetworkOrStorage() = runTest {
        val storage = MemoryStorage(fixtures.document())
        val fetcher = Fetcher { _, _ -> error("Unexpected network") }
        fixtures.withCatalog(this@runTest, storage, { error("Unexpected bundled load") }, fetcher) {
            awaitLoaded()
            val previous = Catalog.state.value.snapshot!!
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
                assertFailsWith<IllegalArgumentException> { Catalog.prepareImport(invalid) }
                assertRetained(storage, previous)
                assertNull(Catalog.state.value.error)
            }
            assertTrue(fetcher.requests.isEmpty())
        }
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
        fixtures.withCatalog(this@runTest, storage, { error("Unexpected bundled load") }, fetcher) {
            awaitLoaded()
            val previous = Catalog.state.value.snapshot!!
            var propagated = false
            val pulling = launch {
                try { Catalog.pull(CatalogCdn.CHINA) } catch (_: CancellationException) { propagated = true }
            }
            entered.await()
            Catalog.close()
            pulling.join()
            Catalog.close()
            assertTrue(cancelled)
            assertTrue(propagated)
            assertEquals(1, fetcher.closes)
            assertRetained(storage, previous)
            assertFailsWith<CancellationException> { Catalog.pull(CatalogCdn.CHINA) }
            assertEquals(1, fetcher.requests.size)
        }
    }

    @Test fun closeDuringLocalReadCannotPublishALateBundledSnapshot() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val storage = MemoryStorage()
        val fetcher = Fetcher { _, _ -> error("Unexpected network") }
        fixtures.withCatalog(this@runTest, storage, {
            entered.complete(Unit)
            withContext(NonCancellable) {
                release.await()
                fixtures.document()
            }
        }, fetcher) {
            try {
                entered.await()
                val closing = launch { Catalog.close() }
                runCurrent()
                assertFalse(closing.isCompleted)
                release.complete(Unit)
                closing.join()
                assertNull(Catalog.state.value.snapshot)
                assertFalse(Catalog.state.value.loading)
                assertFalse(Catalog.state.value.busy)
                assertEquals(0, storage.writes)
                assertTrue(fetcher.requests.isEmpty())
                assertEquals(1, fetcher.closes)
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test fun cancellationImmediatelyAfterLastDownloadStillPreventsAtomicWrite() = runTest {
        val storage = MemoryStorage(fixtures.document())
        var callerJob: kotlinx.coroutines.Job? = null
        val fetcher = Fetcher { url, _ ->
            if (url == CATALOGUE_URL) fixtures.catalogue() else if (url == CATALOG_RESPONSE_LIBRARY_URL) fixtures.responseLibrary() else {
                callerJob!!.cancel(CancellationException("Cancel before commit"))
                fixtures.responseBytes
            }
        }
        fixtures.withCatalog(this@runTest, storage, { error("Unexpected bundled load") }, fetcher) {
            awaitLoaded()
            val previous = Catalog.state.value.snapshot!!
            var propagated = false
            val pulling = launch {
                callerJob = currentCoroutineContext()[kotlinx.coroutines.Job]
                try { Catalog.pull(CatalogCdn.CHINA) } catch (_: CancellationException) { propagated = true }
            }
            pulling.join()
            assertTrue(propagated)
            assertRetained(storage, previous)
            assertEquals(0, storage.writeAttempts)
            assertNotNull(Catalog.state.value.error)
        }
    }

    private fun assertRetained(storage: MemoryStorage, snapshot: CatalogSnapshot) {
        assertContentEquals(snapshot.documentBytes, Catalog.exportBytes())
        assertContentEquals(snapshot.documentBytes, storage.active)
        assertEquals(snapshot.products.map { it.uuid }, Catalog.getAll().map { it.uuid })
        assertEquals(snapshot.products.filter {
            snapshot.responsesByPath[it.freqResponse] is CatalogResponse.Ready
        }.map { it.uuid }, Catalog.getHaveResponse().map { it.uuid })
        assertEquals(snapshot.products.filter { it.type == "BT" }.map { it.uuid }, Catalog.getBluetooth().map { it.uuid })
        assertEquals(snapshot.responseHashesByPath, Catalog.state.value.snapshot!!.responseHashesByPath)
        assertFalse(Catalog.state.value.busy)
        assertEquals(CatalogOrigin.LOCAL, Catalog.state.value.origin)
        assertEquals(0, storage.writes)
    }

    private fun catalogueWithPaths(paths: List<String>): ByteArray = fixtures.catalogue(paths.mapIndexed { index, path ->
        fixtures.product(uuid = uuid(index + 1), name = "Model ${index + 1}", path = path)
    })


    @Test fun queryViewsUseReadyAndExactBluetoothType() = runTest {
        val unsupportedPath = "smoke/unsupported.txt"
        val usbPath = "smoke/usb.txt"
        val outsidePath = "smoke/outside.txt"
        val unsupported = "100 40 0\n1000 60 0\n".encodeToByteArray()
        val catalogue = fixtures.catalogue(listOf(
            fixtures.product(uuid = uuid(4), type = "USB", path = usbPath),
            fixtures.product(uuid = uuid(2), path = null),
            fixtures.product(uuid = uuid(6), type = "FUTURE", path = outsidePath),
            fixtures.product(uuid = uuid(3), path = unsupportedPath),
            fixtures.product(uuid = uuid(1)),
            fixtures.product(uuid = uuid(5), type = "bt", path = null),
        ))
        val library = fixtures.responseLibrary(listOf(fixtures.responseEntry(uuid = uuid(7), tags = listOf("standard"))))
        val original = fixtures.document(catalogue, linkedMapOf(
            fixtures.PATH to fixtures.responseBytes,
            usbPath to "100 45\n1000 55\n".encodeToByteArray(),
            outsidePath to "1 10\n2 20\n".encodeToByteArray(),
            unsupportedPath to unsupported,
        ), library)
        val storage = MemoryStorage(original)
        val fetcher = Fetcher { _, _ -> error("Unexpected network") }
        fixtures.withCatalog(this, storage, { error("Unexpected bundled load") }, fetcher) {
            assertTrue(Catalog.getAll().isEmpty())
            assertTrue(Catalog.getHaveResponse().isEmpty())
            assertTrue(Catalog.getBluetooth().isEmpty())
            assertNull(Catalog.getProduct(uuid(1)))
            assertNull(Catalog.getResponse(uuid(1)))
            awaitLoaded()
            val boundary = listOf(storage.reads, storage.writeAttempts, storage.writes, fetcher.requests.size)
            fun assertViews() {
                assertEquals(listOf(4, 2, 6, 3, 1, 5, 7).map(::uuid), Catalog.getAll().map { it.uuid })
                assertEquals(listOf(4, 6, 1, 7).map(::uuid), Catalog.getHaveResponse().map { it.uuid })
                assertEquals(listOf(2, 3, 1).map(::uuid), Catalog.getBluetooth().map { it.uuid })
            }
            assertViews()
            val usb = assertNotNull(Catalog.getProduct(uuid(4)))
            assertEquals("USB", usb.type)
            assertEquals("Ultra", usb.model)
            val responseProduct = assertNotNull(Catalog.getProduct(uuid(7)))
            assertEquals("Response", responseProduct.type)
            assertNull(responseProduct.model)
            assertNull(responseProduct.languageType)
            assertEquals(JsonPrimitive(fixtures.PATH), responseProduct.raw["file"])
            assertEquals(JsonArray(listOf(JsonPrimitive("standard"))), responseProduct.raw["tags"])
            val snapshot = assertNotNull(Catalog.state.value.snapshot)
            assertEquals(CATALOG_RESPONSE_LIBRARY_URL, Catalog.sourceUrl(snapshot, responseProduct))
            assertEquals(CATALOGUE_URL, Catalog.sourceUrl(snapshot, usb))
            for (index in listOf(1, 7)) {
                val response = assertIs<CatalogResponse.Ready>(Catalog.getResponse(uuid(index))).response
                assertContentEquals(doubleArrayOf(100.0, 1000.0), response.frequencyHz)
                assertContentEquals(doubleArrayOf(40.0, 60.0), response.splDb)
            }
            assertIs<CatalogResponse.Unavailable>(Catalog.getResponse(uuid(3)))
            assertNull(Catalog.getResponse(uuid(2)))
            assertNull(Catalog.getProduct(uuid(99)))
            assertNull(Catalog.getResponse(uuid(99)))
            val exported = fixtures.root(Catalog.exportBytes())
            assertEquals(fixtures.root(library), exported["responseLibrary"])
            val rawEntry = (fixtures.root(library).getValue("data") as JsonArray).single() as JsonObject
            assertFalse("type" in rawEntry)
            assertFalse("freqResponse" in rawEntry)
            val unsupportedAsset = (exported.getValue("responseFiles") as JsonArray)
                .map { it as JsonObject }.single { it["path"] == JsonPrimitive(unsupportedPath) }
            assertContentEquals(unsupported, fixtures.assetBytes(unsupportedAsset))
            assertEquals(JsonPrimitive(catalogSha256(unsupported)), unsupportedAsset["sha256"])
            assertContentEquals(original, Catalog.exportBytes())
            assertEquals(boundary, listOf(storage.reads, storage.writeAttempts, storage.writes, fetcher.requests.size))

            Catalog.close()
            assertViews()
            assertContentEquals(original, Catalog.exportBytes())
            assertEquals(1, fetcher.closes)
            assertEquals(boundary, listOf(storage.reads, storage.writeAttempts, storage.writes, fetcher.requests.size))
        }
    }

    @Test fun repeatedInitPreservesImportedData() = runTest {
        val original = document(1)
        val imported = document(2, "USB")
        val storage = MemoryStorage(original)
        val fetcher = Fetcher { _, _ -> error("Unexpected network") }
        fixtures.withCatalog(this, storage, { error("Unexpected bundled load") }, fetcher) {
            awaitLoaded()
            Catalog.importSnapshot(Catalog.prepareImport(imported))
            val boundary = listOf(storage.reads, storage.writeAttempts, storage.writes)
            val callers = List(2) {
                launch {
                    Catalog.init(
                        storage = { error("Repeated init constructed storage") },
                        loadBundled = { error("Repeated init loaded bundled data") },
                        fetcher = { error("Repeated init constructed fetcher") },
                        dispatcher = StandardTestDispatcher(testScheduler),
                    )
                }
            }
            callers.forEach { it.join() }
            assertEquals(listOf(uuid(2)), Catalog.getAll().map { it.uuid })
            assertEquals(listOf(uuid(2)), Catalog.getHaveResponse().map { it.uuid })
            assertTrue(Catalog.getBluetooth().isEmpty())
            assertEquals(CatalogOrigin.IMPORT, Catalog.state.value.origin)
            assertContentEquals(imported, Catalog.exportBytes())
            assertContentEquals(imported, storage.active)
            assertEquals(boundary, listOf(storage.reads, storage.writeAttempts, storage.writes))
            assertEquals(1, storage.writes)
            assertTrue(fetcher.requests.isEmpty())
            assertEquals(0, fetcher.closes)
        }
    }

    @Test fun subscribersOutliveOtherSubscribersAndReinitialization() = runTest {
        val storage = MemoryStorage(document(1))
        val firstFetcher = Fetcher { _, _ -> error("Unexpected network") }
        val secondFetcher = Fetcher { _, _ -> error("Unexpected network") }
        fixtures.withCatalog(this, storage, { error("Unexpected bundled load") }, firstFetcher) {
            val firstEvents = Channel<CatalogState>(Channel.UNLIMITED)
            val secondEvents = Channel<CatalogState>(Channel.UNLIMITED)
            val firstSubscriber = backgroundScope.launch { Catalog.state.collect { firstEvents.send(it) } }
            val secondSubscriber = backgroundScope.launch { Catalog.state.collect { secondEvents.send(it) } }
            try {
                firstEvents.awaitCommitted(uuid(1), CatalogOrigin.LOCAL)
                secondEvents.awaitCommitted(uuid(1), CatalogOrigin.LOCAL)
                firstSubscriber.cancel()
                firstSubscriber.join()
                val imported = document(2, "USB")
                Catalog.importSnapshot(Catalog.prepareImport(imported))
                secondEvents.awaitCommitted(uuid(2), CatalogOrigin.IMPORT)
                assertContentEquals(imported, Catalog.exportBytes())
                assertContentEquals(imported, storage.active)
                assertEquals(0, firstFetcher.closes)
                Catalog.close()
                Catalog.init({ storage }, { error("Unexpected bundled load") }, { secondFetcher }, StandardTestDispatcher(testScheduler))
                secondEvents.awaitCommitted(uuid(2), CatalogOrigin.LOCAL)
                val next = document(3, "WIRED")
                Catalog.importSnapshot(Catalog.prepareImport(next))
                secondEvents.awaitCommitted(uuid(3), CatalogOrigin.IMPORT)
                assertEquals(listOf(uuid(3)), Catalog.getAll().map { it.uuid })
                assertContentEquals(next, Catalog.exportBytes())
                assertContentEquals(next, storage.active)
                assertEquals(1, firstFetcher.closes)
                assertEquals(0, secondFetcher.closes)
                assertTrue(firstFetcher.requests.isEmpty())
                assertTrue(secondFetcher.requests.isEmpty())
            } finally {
                firstSubscriber.cancel()
                secondSubscriber.cancel()
                firstEvents.close()
                secondEvents.close()
            }
        }
        assertEquals(1, secondFetcher.closes)
    }

    @Test fun initialLoadOutlivesItsInitiatingCaller() = runTest {
        val helperFetcher = Fetcher { _, _ -> error("Unexpected network") }
        val fetcher = Fetcher { _, _ -> error("Unexpected network") }
        val storage = MemoryStorage()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val initialized = CompletableDeferred<Unit>()
        val original = document(2, "USB")
        fixtures.withCatalog(this, MemoryStorage(), { document(1) }, helperFetcher) {
            Catalog.close()
            val caller = launch {
                Catalog.init({ storage }, {
                    entered.complete(Unit)
                    release.await()
                    original
                }, { fetcher }, StandardTestDispatcher(testScheduler))
                initialized.complete(Unit)
                awaitCancellation()
            }
            try {
                initialized.await()
                entered.await()
                caller.cancel()
                release.complete(Unit)
                caller.join()
                awaitLoaded()
                assertEquals(CatalogOrigin.BUNDLED, Catalog.state.value.origin)
                assertEquals(listOf(uuid(2)), Catalog.getAll().map { it.uuid })
                assertContentEquals(original, Catalog.exportBytes())
                assertNull(Catalog.state.value.error)
                assertEquals(1, storage.reads)
                assertEquals(0, storage.writeAttempts)
                assertTrue(fetcher.requests.isEmpty())
            } finally {
                release.complete(Unit)
                caller.cancel()
            }
        }
        assertEquals(1, helperFetcher.closes)
        assertEquals(1, fetcher.closes)
    }

    @Test fun closeBeforeInitialLoadStartsDrainsFlags() = runTest {
        val storage = MemoryStorage(document(1))
        val fetcher = Fetcher { _, _ -> error("Unexpected network") }
        var bundledReads = 0
        fixtures.withCatalog(this, storage, { bundledReads++; document(2) }, fetcher) {
            Catalog.close()
            Catalog.close()
            assertFalse(Catalog.state.value.loading)
            assertFalse(Catalog.state.value.busy)
            assertNull(Catalog.state.value.snapshot)
            assertTrue(Catalog.getAll().isEmpty())
            assertEquals(0, storage.reads)
            assertEquals(0, bundledReads)
            assertEquals(0, storage.writeAttempts)
            assertTrue(fetcher.requests.isEmpty())
            assertEquals(1, fetcher.closes)
        }
    }

    @Test fun reinitWaitsForOldNonCancellableRead() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val closingEntered = CompletableDeferred<Unit>()
        val nextAttempted = CompletableDeferred<Unit>()
        val oldFetcher = Fetcher({ _, _ -> error("Unexpected network") }, onClose = { closingEntered.complete(Unit) })
        val nextFetcher = Fetcher { _, _ -> error("Unexpected network") }
        val oldStorage = MemoryStorage()
        val nextDocument = document(2, "USB")
        val nextStorage = MemoryStorage(nextDocument)
        var nextFactories = 0
        fixtures.withCatalog(this, oldStorage, {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await(); document(1) }
        }, oldFetcher) {
            try {
                entered.await()
                val closing = launch { Catalog.close() }
                closingEntered.await()
                val restarting = launch {
                    nextAttempted.complete(Unit)
                    Catalog.init({ nextFactories++; nextStorage }, { error("Unexpected bundled load") },
                        { nextFetcher }, StandardTestDispatcher(testScheduler))
                }
                nextAttempted.await()
                runCurrent()
                assertFalse(closing.isCompleted)
                assertFalse(restarting.isCompleted)
                assertEquals(0, nextFactories)
                assertEquals(0, nextStorage.reads)
                assertNull(Catalog.state.value.snapshot)
                release.complete(Unit)
                closing.join()
                restarting.join()
                awaitLoaded()
                assertEquals(1, nextFactories)
                assertEquals(CatalogOrigin.LOCAL, Catalog.state.value.origin)
                assertEquals(listOf(uuid(2)), Catalog.getAll().map { it.uuid })
                assertContentEquals(nextDocument, Catalog.exportBytes())
                assertEquals(0, oldStorage.writeAttempts)
                assertEquals(0, nextStorage.writeAttempts)
                assertEquals(1, oldFetcher.closes)
                assertTrue(oldFetcher.requests.isEmpty())
                assertTrue(nextFetcher.requests.isEmpty())
            } finally { release.complete(Unit) }
        }
        assertEquals(1, nextFetcher.closes)
    }

    @Test fun reinitWaitsForAtomicCommitAndItsPublication() = runTest {
        val enteredWrite = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val closingEntered = CompletableDeferred<Unit>()
        val nextAttempted = CompletableDeferred<Unit>()
        val storage = MemoryStorage(document(1)).apply {
            beforeWrite = { enteredWrite.complete(Unit); releaseWrite.await() }
        }
        val oldFetcher = Fetcher({ _, _ -> error("Unexpected network") }, onClose = { closingEntered.complete(Unit) })
        val nextFetcher = Fetcher { _, _ -> error("Unexpected network") }
        val imported = document(2, "USB")
        var nextFactories = 0
        var returnedSuccess = false
        fixtures.withCatalog(this, storage, { error("Unexpected bundled load") }, oldFetcher) {
            awaitLoaded()
            val prepared = Catalog.prepareImport(imported)
            val publication = Channel<CatalogState>(Channel.UNLIMITED)
            val observer = backgroundScope.launch { Catalog.state.collect { publication.send(it) } }
            try {
                publication.awaitCommitted(uuid(1), CatalogOrigin.LOCAL)
                val importing = launch { Catalog.importSnapshot(prepared); returnedSuccess = true }
                enteredWrite.await()
                val closing = launch { Catalog.close() }
                closingEntered.await()
                val restarting = launch {
                    nextAttempted.complete(Unit)
                    Catalog.init({
                        nextFactories++
                        assertFalse(Catalog.state.value.busy)
                        assertFalse(Catalog.state.value.loading)
                        assertEquals(CatalogOrigin.IMPORT, Catalog.state.value.origin)
                        assertContentEquals(imported, Catalog.exportBytes())
                        assertContentEquals(imported, storage.active)
                        storage
                    }, { error("Unexpected bundled load") }, { nextFetcher }, StandardTestDispatcher(testScheduler))
                }
                nextAttempted.await()
                runCurrent()
                assertFalse(closing.isCompleted)
                assertFalse(restarting.isCompleted)
                assertEquals(0, nextFactories)
                assertEquals(listOf(uuid(1)), Catalog.getAll().map { it.uuid })
                assertEquals(0, storage.writes)
                releaseWrite.complete(Unit)
                importing.join()
                closing.join()
                restarting.join()
                awaitLoaded()
                publication.awaitCommitted(uuid(2), CatalogOrigin.LOCAL)
                assertTrue(returnedSuccess)
                assertEquals(1, nextFactories)
                assertEquals(1, storage.writes)
                assertEquals(1, storage.writeAttempts)
                assertEquals(CatalogOrigin.LOCAL, Catalog.state.value.origin)
                assertEquals(listOf(uuid(2)), Catalog.getAll().map { it.uuid })
                assertEquals(listOf(uuid(2)), Catalog.getHaveResponse().map { it.uuid })
                assertTrue(Catalog.getBluetooth().isEmpty())
                assertContentEquals(imported, Catalog.exportBytes())
                assertContentEquals(imported, storage.active)
                assertEquals(1, oldFetcher.closes)
                assertTrue(oldFetcher.requests.isEmpty())
                assertTrue(nextFetcher.requests.isEmpty())
            } finally {
                releaseWrite.complete(Unit)
                observer.cancel()
                publication.close()
            }
        }
        assertEquals(1, nextFetcher.closes)
    }

    @Test fun failedFactoriesLeaveTheLastSnapshotAndAllowLaterInitialization() = runTest {
        val original = document(1)
        val storage = MemoryStorage(original)
        val firstFetcher = Fetcher { _, _ -> error("Unexpected network") }
        val recoveryFetcher = Fetcher { _, _ -> error("Unexpected network") }
        fixtures.withCatalog(this, storage, { error("Unexpected bundled load") }, firstFetcher) {
            awaitLoaded()
            Catalog.close()
            val retained = Catalog.state.value
            var fetcherFactories = 0
            assertFailsWith<IllegalArgumentException> {
                Catalog.init({ throw IllegalArgumentException("Storage factory failed") }, { error("Unexpected bundled load") },
                    { fetcherFactories++; error("Must not create fetcher") }, StandardTestDispatcher(testScheduler))
            }
            assertEquals(0, fetcherFactories)
            assertEquals(retained, Catalog.state.value)
            assertFailsWith<IllegalArgumentException> {
                Catalog.init({ storage }, { error("Unexpected bundled load") },
                    { throw IllegalArgumentException("Fetcher factory failed") }, StandardTestDispatcher(testScheduler))
            }
            assertEquals(retained, Catalog.state.value)
            assertContentEquals(original, Catalog.exportBytes())
            assertEquals(1, storage.reads)
            assertEquals(0, storage.writeAttempts)
            Catalog.init({ storage }, { error("Unexpected bundled load") }, { recoveryFetcher }, StandardTestDispatcher(testScheduler))
            awaitLoaded()
            assertEquals(CatalogOrigin.LOCAL, Catalog.state.value.origin)
            assertContentEquals(original, Catalog.exportBytes())
            assertEquals(2, storage.reads)
        }
        assertEquals(1, firstFetcher.closes)
        assertEquals(1, recoveryFetcher.closes)
    }

    @Test fun fetcherCloseFailureStillDrainsOldWorkAndAllowsReinitialization() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val closeEntered = CompletableDeferred<Unit>()
        val failedFetcher = Fetcher({ _, _ -> error("Unexpected network") }, onClose = {
            closeEntered.complete(Unit)
            throw IllegalArgumentException("Fetcher close failed")
        })
        val recoveryFetcher = Fetcher { _, _ -> error("Unexpected network") }
        val recoveryStorage = MemoryStorage(document(2, "USB"))
        var closingFailure: Throwable? = null
        fixtures.withCatalog(this, MemoryStorage(), {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await(); document(1) }
        }, failedFetcher) {
            try {
                entered.await()
                val closing = launch { closingFailure = runCatching { Catalog.close() }.exceptionOrNull() }
                closeEntered.await()
                runCurrent()
                assertFalse(closing.isCompleted)
                release.complete(Unit)
                closing.join()
                assertIs<IllegalArgumentException>(closingFailure)
                assertFalse(Catalog.state.value.loading)
                assertFalse(Catalog.state.value.busy)
                assertNull(Catalog.state.value.snapshot)
                Catalog.close()
                assertEquals(1, failedFetcher.closes)
                Catalog.init({ recoveryStorage }, { error("Unexpected bundled load") },
                    { recoveryFetcher }, StandardTestDispatcher(testScheduler))
                awaitLoaded()
                assertEquals(listOf(uuid(2)), Catalog.getAll().map { it.uuid })
                assertContentEquals(recoveryStorage.active, Catalog.exportBytes())
                assertEquals(0, recoveryStorage.writeAttempts)
                assertTrue(failedFetcher.requests.isEmpty())
                assertTrue(recoveryFetcher.requests.isEmpty())
            } finally { release.complete(Unit) }
        }
        assertEquals(1, recoveryFetcher.closes)
    }

    private suspend fun awaitLoaded(): CatalogState = Catalog.state.first { !it.loading && !it.busy }

    private suspend fun Channel<CatalogState>.awaitCommitted(uuid: String, origin: CatalogOrigin) {
        while (true) {
            val state = receive()
            if (!state.loading && !state.busy && state.origin == origin && state.snapshot?.productsByUuid?.containsKey(uuid) == true) return
        }
    }

    private fun uuid(index: Int): String = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"

    private fun document(index: Int, type: String = "BT"): ByteArray = fixtures.document(
        fixtures.catalogue(listOf(fixtures.product(uuid = uuid(index), type = type))),
    )

    private class MemoryStorage(var active: ByteArray? = null) : CatalogStorage {
        var reads = 0
        var writes = 0
        var writeAttempts = 0
        var readFailure: Exception? = null
        var writeFailure: Exception? = null
        var beforeWrite: suspend () -> Unit = {}

        override suspend fun readActive(): ByteArray? {
            reads++
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

    private class Fetcher(
        private val body: suspend (String, Int) -> ByteArray,
        private val onClose: () -> Unit,
    ) : CatalogByteFetcher {
        constructor(body: suspend (String, Int) -> ByteArray) : this(body, {})

        val requests = mutableListOf<Pair<String, Int>>()
        var closes = 0

        override suspend fun fetch(url: String, maxBytes: Int): ByteArray {
            requests += url to maxBytes
            return body(url, maxBytes)
        }

        override fun close() {
            closes++
            onClose()
        }
    }
}
