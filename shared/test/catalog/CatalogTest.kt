package ink.lipoly.app.sunrise.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
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
        fixtures.withCatalog(this@runTest, storage, { bundledReads++; fixtures.document() }, fetcher) { catalog ->
            awaitLoaded(catalog)
            assertEquals(CatalogOrigin.LOCAL, catalog.state.value.origin)
            assertContentEquals(original, catalog.exportBytes())
            assertEquals(0, bundledReads)
            assertEquals(0, storage.writes)
            val exported = catalog.exportBytes()
            exported[0] = '!'.code.toByte()
            assertContentEquals(original, catalog.exportBytes())
            val prepared = catalog.prepareImport(original)
            assertContentEquals(original, prepared.documentBytes)
            assertContentEquals(original, catalog.exportBytes())
            catalog.importSnapshot(prepared)
            assertEquals(CatalogOrigin.IMPORT, catalog.state.value.origin)
            assertContentEquals(original, storage.active)
            assertContentEquals(original, catalog.exportBytes())
            assertEquals(1, storage.writes)
            assertTrue(fetcher.requests.isEmpty())
            catalog.close()
            val restartedFetcher = Fetcher { _, _ -> error("Restart must stay offline") }
            catalog.init(
                storage = { storage },
                loadBundled = { error("Should not load bundled") },
                fetcher = { restartedFetcher },
                dispatcher = StandardTestDispatcher(testScheduler),
            )
            awaitLoaded(catalog)
            assertEquals(CatalogOrigin.LOCAL, catalog.state.value.origin)
            assertContentEquals(original, catalog.exportBytes())
            assertEquals(1, fetcher.closes)
            assertTrue(restartedFetcher.requests.isEmpty())
            catalog.close()
            assertEquals(1, restartedFetcher.closes)
        }
    }

    @Test fun firstInstallationUsesBundledWithoutCopyingItToStorage() = runTest {
        val storage = MemoryStorage()
        val fetcher = Fetcher { _, _ -> error("Unexpected network") }
        val bundled = fixtures.document()
        fixtures.withCatalog(this@runTest, storage, { bundled }, fetcher) { catalog ->
            awaitLoaded(catalog)
            assertEquals(CatalogOrigin.BUNDLED, catalog.state.value.origin)
            assertFalse(catalog.state.value.loading)
            assertFalse(catalog.state.value.busy)
            assertNull(catalog.state.value.error)
            assertNull(catalog.state.value.warning)
            assertNull(storage.active)
            assertEquals(0, storage.writes)
            assertContentEquals(bundled, catalog.exportBytes())
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
            fixtures.withCatalog(this@runTest, storage, { bundled }, fetcher) { catalog ->
                awaitLoaded(catalog)
                assertEquals(CatalogOrigin.BUNDLED, catalog.state.value.origin)
                assertNotNull(catalog.state.value.warning)
                assertNull(catalog.state.value.error)
                assertContentEquals(broken, storage.active)
                assertContentEquals(bundled, catalog.exportBytes())
                assertEquals(listOf(fixtures.UUID, fixtures.SECOND_UUID), catalog.getAll().map { it.uuid })
                assertEquals(listOf(fixtures.UUID, fixtures.SECOND_UUID), catalog.getHaveResponse().map { it.uuid })
                assertEquals(listOf(fixtures.UUID), catalog.getBluetooth().map { it.uuid })
                assertEquals("Response", catalog.getProduct(fixtures.SECOND_UUID)?.type)
                assertEquals(0, storage.writes)
                assertTrue(fetcher.requests.isEmpty())
            }
        }
    }

    @Test fun brokenBundledIsAnExplicitFailureAndImportCanRecoverWithoutNetwork() = runTest {
        val storage = MemoryStorage("broken active".encodeToByteArray())
        val fetcher = Fetcher { _, _ -> error("Unexpected network") }
        fixtures.withCatalog(this@runTest, storage, { "broken bundled".encodeToByteArray() }, fetcher) { catalog ->
            assertFailsWith<IllegalStateException> { catalog.exportBytes() }
            awaitLoaded(catalog)
            assertNull(catalog.state.value.snapshot)
            assertNull(catalog.state.value.origin)
            assertNotNull(catalog.state.value.error)
            assertNotNull(catalog.state.value.warning)
            assertFalse(catalog.state.value.loading)
            assertFalse(catalog.state.value.busy)
            assertEquals(0, storage.writes)
            assertFailsWith<IllegalStateException> { catalog.exportBytes() }
            catalog.importSnapshot(catalog.prepareImport(fixtures.document()))
            assertNotNull(catalog.state.value.snapshot)
            assertNull(catalog.state.value.error)
            assertNull(catalog.state.value.warning)
            assertEquals(CatalogOrigin.IMPORT, catalog.state.value.origin)
            assertTrue(fetcher.requests.isEmpty())
        }
    }

    @Test fun reloadFailureRetainsAnAlreadyUsableSnapshot() = runTest {
        val storage = MemoryStorage(fixtures.document())
        fixtures.withCatalog(this@runTest, storage, { error("Bundled unavailable") }, Fetcher { _, _ -> error("Unexpected network") }) { catalog ->
            awaitLoaded(catalog)
            val previous = catalog.state.value.snapshot!!
            storage.readFailure = IllegalStateException("Active unreadable")
            catalog.loadLocal()
            assertContentEquals(previous.documentBytes, catalog.exportBytes())
            assertNotNull(catalog.state.value.error)
            assertNotNull(catalog.state.value.warning)
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
        fixtures.withCatalog(this@runTest, storage, { error("Unexpected bundled load") }, fetcher) { catalog ->
            awaitLoaded(catalog)
            assertTrue(fetcher.requests.isEmpty())
            catalog.pull(CatalogCdn.OVERSEAS)
            val state = catalog.state.value
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
            val root = fixtures.root(catalog.exportBytes())
            assertEquals(fixtures.root(catalogue), root.getValue("catalogue"))
            assertContentEquals(response, fixtures.assetBytes((root.getValue("responseFiles") as JsonArray).single() as JsonObject))
            assertContentEquals(catalog.exportBytes(), storage.active)
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
        }) { catalog ->
            awaitLoaded(catalog)
            catalog.pull(CatalogCdn.CHINA)
            assertNull(catalog.state.value.error)
            assertIs<CatalogResponse.Unavailable>(catalog.getResponse(fixtures.SECOND_UUID))
            assertEquals("Response", catalog.getProduct(fixtures.SECOND_UUID)?.type)
            assertEquals(listOf(fixtures.UUID, fixtures.SECOND_UUID), catalog.getAll().map { it.uuid })
            assertTrue(catalog.getHaveResponse().isEmpty())
            assertEquals(listOf(fixtures.UUID), catalog.getBluetooth().map { it.uuid })
            val asset = (fixtures.root(catalog.exportBytes()).getValue("responseFiles") as JsonArray).single() as JsonObject
            assertContentEquals(unsupported, fixtures.assetBytes(asset))
            assertContentEquals(catalog.exportBytes(), storage.active)
        }
    }

    @Test fun nthAssetFailureNeverPublishesPartialDirectoryOrUsesOldAssets() = runTest {
        val paths = (1..7).map { "new/response-$it.txt" }
        val catalogue = catalogueWithPaths(paths)
        for (failedAsset in listOf(1, 2, 7)) {
            val storage = MemoryStorage(fixtures.document())
            var assetRequests = 0
            fixtures.withCatalog(this@runTest, storage, { error("Unexpected bundled load") }, Fetcher { url, _ ->
                when (url) {
                    CATALOGUE_URL -> catalogue
                    CATALOG_RESPONSE_LIBRARY_URL -> fixtures.responseLibrary()
                    else -> {
                        assetRequests++
                        if (assetRequests == failedAsset) error("Asset $failedAsset unavailable")
                        fixtures.responseBytes
                    }
                }
            }) { catalog ->
                awaitLoaded(catalog)
                val previous = catalog.state.value.snapshot!!
                catalog.pull(CatalogCdn.CHINA)
                assertRetained(catalog, storage, previous)
                assertNotNull(catalog.state.value.error)
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
            fixtures.withCatalog(this@runTest, storage, { error("Unexpected bundled load") }, fetcher) { catalog ->
                awaitLoaded(catalog)
                val previous = catalog.state.value.snapshot!!
                catalog.pull(CatalogCdn.CHINA)
                assertRetained(catalog, storage, previous)
                assertEquals(listOf(fixtures.UUID, fixtures.SECOND_UUID), catalog.getAll().map { it.uuid })
                assertEquals(listOf(fixtures.UUID, fixtures.SECOND_UUID), catalog.getHaveResponse().map { it.uuid })
                assertEquals(listOf(fixtures.UUID), catalog.getBluetooth().map { it.uuid })
                assertEquals("BT", catalog.getProduct(fixtures.UUID)?.type)
                assertEquals("Response", catalog.getProduct(fixtures.SECOND_UUID)?.type)
                assertIs<CatalogResponse.Ready>(catalog.getResponse(fixtures.UUID))
                assertIs<CatalogResponse.Ready>(catalog.getResponse(fixtures.SECOND_UUID))
                assertNotNull(catalog.state.value.error)
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
            fixtures.withCatalog(this@runTest, storage, { error("Unexpected bundled load") }, fetcher) { catalog ->
                awaitLoaded(catalog)
                val previous = catalog.state.value.snapshot!!
                catalog.pull(CatalogCdn.CHINA)
                assertRetained(catalog, storage, previous)
                assertNotNull(catalog.state.value.error)
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
            fixtures.withCatalog(this@runTest, storage, { error("Unexpected bundled load") }, fetcher) { catalog ->
                awaitLoaded(catalog)
                val previous = catalog.state.value.snapshot!!
                catalog.pull(CatalogCdn.CHINA)
                assertRetained(catalog, storage, previous)
                assertNotNull(catalog.state.value.error)
                assertEquals(MAX_CATALOGUE_BYTES, fetcher.requests.first().second)
                assertTrue(fetcher.requests.drop(2).all { it.second == MAX_RESPONSE_FILE_BYTES })
                assertTrue(catalog.state.value.completedFiles < paths.size)
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
            when (url) {
                CATALOGUE_URL -> catalogue
                CATALOG_RESPONSE_LIBRARY_URL -> library
                else -> {
                    inFlight++
                    peak = maxOf(peak, inFlight)
                    entered.send(url)
                    try { releases.getValue(url).await(); fixtures.responseBytes } finally { inFlight-- }
                }
            }
        }
        fixtures.withCatalog(this@runTest, storage, { error("Unexpected bundled load") }, fetcher) { catalog ->
            try {
                awaitLoaded(catalog)
                val previous = catalog.state.value.snapshot!!
                val pulling = launch { catalog.pull(CatalogCdn.CHINA) }
                val firstFour = List(4) { entered.receive() }
                assertEquals(4, inFlight)
                assertEquals(6, catalog.state.value.totalFiles)
                assertEquals(0, catalog.state.value.completedFiles)
                assertTrue(catalog.state.value.busy)
                assertContentEquals(previous.documentBytes, catalog.exportBytes())
                assertEquals(listOf(fixtures.UUID), catalog.getAll().map { it.uuid })
                assertEquals(listOf(fixtures.UUID), catalog.getHaveResponse().map { it.uuid })
                assertEquals(listOf(fixtures.UUID), catalog.getBluetooth().map { it.uuid })
                assertEquals(0, storage.writes)
                releases.getValue(firstFour.first()).complete(Unit)
                val fifth = entered.receive()
                assertEquals(1, catalog.state.value.completedFiles)
                assertContentEquals(previous.documentBytes, storage.active)
                assertEquals(listOf(fixtures.UUID), catalog.getAll().map { it.uuid })
                assertEquals(listOf(fixtures.UUID), catalog.getHaveResponse().map { it.uuid })
                assertEquals(listOf(fixtures.UUID), catalog.getBluetooth().map { it.uuid })
                releases.getValue(fifth).complete(Unit)
                val sixth = entered.receive()
                for (url in firstFour.drop(1) + sixth) releases.getValue(url).complete(Unit)
                pulling.join()
                assertEquals(4, peak)
                assertEquals(0, inFlight)
                assertEquals(6, catalog.state.value.completedFiles)
                assertEquals(6, catalog.state.value.totalFiles)
                assertNull(catalog.state.value.error)
                assertEquals(1, storage.writes)
                assertEquals(8, fetcher.requests.size)
                assertEquals(paths.toSet(), fetcher.requests.drop(2).map { it.first }.map { url ->
                    paths.single { catalogResponseUrl(CatalogCdn.CHINA, it) == url }
                }.toSet())
                val mergedUuids = (1..5).map(::uuid) + listOf(uuid(7), uuid(8))
                assertEquals(mergedUuids, catalog.getAll().map { it.uuid })
                assertEquals(mergedUuids, catalog.getHaveResponse().map { it.uuid })
                assertEquals((1..5).map(::uuid), catalog.getBluetooth().map { it.uuid })
                assertContentEquals(catalog.exportBytes(), storage.active)
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
            }) { catalog ->
                awaitLoaded(catalog)
                val previous = catalog.state.value.snapshot!!
                var propagated = false
                val pulling = launch {
                    try { catalog.pull(CatalogCdn.CHINA) } catch (_: CancellationException) { propagated = true }
                }
                entered.await()
                pulling.cancel()
                pulling.join()
                assertTrue(propagated)
                assertTrue(fetchCancelled)
                assertRetained(catalog, storage, previous)
                assertNotNull(catalog.state.value.error)
            }
        }
    }

    @Test fun concurrentPullAndImportAreRejectedImmediatelyRatherThanQueued() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val storage = MemoryStorage(fixtures.document())
        val fetcher = Fetcher { url, _ ->
            when (url) {
                CATALOGUE_URL -> {
                    entered.complete(Unit)
                    release.await()
                    fixtures.catalogue()
                }
                CATALOG_RESPONSE_LIBRARY_URL -> fixtures.responseLibrary()
                else -> fixtures.responseBytes
            }
        }
        fixtures.withCatalog(this@runTest, storage, { error("Unexpected bundled load") }, fetcher) { catalog ->
            try {
                awaitLoaded(catalog)
                val imported = catalog.prepareImport(fixtures.document())
                val pulling = launch { catalog.pull(CatalogCdn.CHINA) }
                entered.await()
                assertFailsWith<IllegalStateException> { catalog.pull(CatalogCdn.OVERSEAS) }
                assertFailsWith<IllegalStateException> { catalog.importSnapshot(imported) }
                assertTrue(catalog.state.value.busy)
                assertEquals(1, fetcher.requests.size)
                assertEquals(0, storage.writes)
                release.complete(Unit)
                pulling.join()
                assertEquals(CatalogOrigin.PULL, catalog.state.value.origin)
                assertNull(catalog.state.value.error)
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
            fixtures.withCatalog(this@runTest, storage, { error("Unexpected bundled load") }, fetcher) { catalog ->
                awaitLoaded(catalog)
                val previous = catalog.state.value.snapshot!!
                if (importing) catalog.importSnapshot(catalog.prepareImport(fixtures.document(newCatalogue)))
                else catalog.pull(CatalogCdn.CHINA)
                assertRetained(catalog, storage, previous)
                assertNotNull(catalog.state.value.error)
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
            }) { catalog ->
                try {
                    awaitLoaded(catalog)
                    val prepared = catalog.prepareImport(fixtures.document(catalogue))
                    val previous = catalog.state.value.snapshot!!
                    var returnedSuccess = false
                    var returnedCancellation = false
                    val updating = launch {
                        try {
                            if (importing) catalog.importSnapshot(prepared) else catalog.pull(CatalogCdn.CHINA)
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
                    assertEquals(setOf(fixtures.SECOND_UUID), catalog.state.value.snapshot!!.productsByUuid.keys)
                    assertEquals(if (importing) CatalogOrigin.IMPORT else CatalogOrigin.PULL, catalog.state.value.origin)
                    assertNull(catalog.state.value.error)
                    assertFalse(catalog.state.value.busy)
                    assertEquals(1, storage.writes)
                    assertContentEquals(catalog.exportBytes(), storage.active)
                } finally {
                    releaseWrite.complete(Unit)
                }
            }
        }
    }

    @Test fun invalidImportPreparationNeverChangesTheCurrentStateOrTouchesNetworkOrStorage() = runTest {
        val storage = MemoryStorage(fixtures.document())
        val fetcher = Fetcher { _, _ -> error("Unexpected network") }
        fixtures.withCatalog(this@runTest, storage, { error("Unexpected bundled load") }, fetcher) { catalog ->
            awaitLoaded(catalog)
            val previous = catalog.state.value.snapshot!!
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
                assertFailsWith<IllegalArgumentException> { catalog.prepareImport(invalid) }
                assertRetained(catalog, storage, previous)
                assertNull(catalog.state.value.error)
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
        fixtures.withCatalog(this@runTest, storage, { error("Unexpected bundled load") }, fetcher) { catalog ->
            awaitLoaded(catalog)
            val previous = catalog.state.value.snapshot!!
            var propagated = false
            val pulling = launch {
                try { catalog.pull(CatalogCdn.CHINA) } catch (_: CancellationException) { propagated = true }
            }
            entered.await()
            catalog.close()
            pulling.join()
            catalog.close()
            assertTrue(cancelled)
            assertTrue(propagated)
            assertEquals(1, fetcher.closes)
            assertRetained(catalog, storage, previous)
            assertFailsWith<CancellationException> { catalog.pull(CatalogCdn.CHINA) }
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
        }, fetcher) { catalog ->
            try {
                entered.await()
                val closing = launch { catalog.close() }
                runCurrent()
                assertFalse(closing.isCompleted)
                release.complete(Unit)
                closing.join()
                assertNull(catalog.state.value.snapshot)
                assertFalse(catalog.state.value.loading)
                assertFalse(catalog.state.value.busy)
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
            when (url) {
                CATALOGUE_URL -> fixtures.catalogue()
                CATALOG_RESPONSE_LIBRARY_URL -> fixtures.responseLibrary()
                else -> {
                    callerJob!!.cancel(CancellationException("Cancel before commit"))
                    fixtures.responseBytes
                }
            }
        }
        fixtures.withCatalog(this@runTest, storage, { error("Unexpected bundled load") }, fetcher) { catalog ->
            awaitLoaded(catalog)
            val previous = catalog.state.value.snapshot!!
            var propagated = false
            val pulling = launch {
                callerJob = currentCoroutineContext()[kotlinx.coroutines.Job]
                try { catalog.pull(CatalogCdn.CHINA) } catch (_: CancellationException) { propagated = true }
            }
            pulling.join()
            assertTrue(propagated)
            assertRetained(catalog, storage, previous)
            assertEquals(0, storage.writeAttempts)
            assertNotNull(catalog.state.value.error)
        }
    }

    private fun assertRetained(catalog: Catalog, storage: MemoryStorage, snapshot: CatalogSnapshot) {
        assertContentEquals(snapshot.documentBytes, catalog.exportBytes())
        assertContentEquals(snapshot.documentBytes, storage.active)
        assertEquals(snapshot.products.map { it.uuid }, catalog.getAll().map { it.uuid })
        assertEquals(snapshot.products.filter {
            snapshot.responsesByPath[it.freqResponse] is CatalogResponse.Ready
        }.map { it.uuid }, catalog.getHaveResponse().map { it.uuid })
        assertEquals(snapshot.products.filter { it.type == "BT" }.map { it.uuid }, catalog.getBluetooth().map { it.uuid })
        assertEquals(snapshot.responseHashesByPath, catalog.state.value.snapshot!!.responseHashesByPath)
        assertFalse(catalog.state.value.busy)
        assertEquals(CatalogOrigin.LOCAL, catalog.state.value.origin)
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
        fixtures.withCatalog(this, storage, { error("Unexpected bundled load") }, fetcher) { catalog ->
            assertTrue(catalog.getAll().isEmpty())
            assertTrue(catalog.getHaveResponse().isEmpty())
            assertTrue(catalog.getBluetooth().isEmpty())
            assertNull(catalog.getProduct(uuid(1)))
            assertNull(catalog.getResponse(uuid(1)))
            awaitLoaded(catalog)
            val boundary = listOf(storage.reads, storage.writeAttempts, storage.writes, fetcher.requests.size)
            fun assertViews() {
                assertEquals(listOf(4, 2, 6, 3, 1, 5, 7).map(::uuid), catalog.getAll().map { it.uuid })
                assertEquals(listOf(4, 6, 1, 7).map(::uuid), catalog.getHaveResponse().map { it.uuid })
                assertEquals(listOf(2, 3, 1).map(::uuid), catalog.getBluetooth().map { it.uuid })
            }
            assertViews()
            val usb = assertNotNull(catalog.getProduct(uuid(4)))
            assertEquals("USB", usb.type)
            assertEquals("Ultra", usb.model)
            val responseProduct = assertNotNull(catalog.getProduct(uuid(7)))
            assertEquals("Response", responseProduct.type)
            assertNull(responseProduct.model)
            assertNull(responseProduct.languageType)
            assertEquals(JsonPrimitive(fixtures.PATH), responseProduct.raw["file"])
            assertEquals(JsonArray(listOf(JsonPrimitive("standard"))), responseProduct.raw["tags"])
            val snapshot = assertNotNull(catalog.state.value.snapshot)
            assertEquals(CATALOG_RESPONSE_LIBRARY_URL, catalogSourceUrl(snapshot, responseProduct))
            assertEquals(CATALOGUE_URL, catalogSourceUrl(snapshot, usb))
            for (index in listOf(1, 7)) {
                val response = assertIs<CatalogResponse.Ready>(catalog.getResponse(uuid(index))).response
                assertContentEquals(doubleArrayOf(100.0, 1000.0), response.frequencyHz)
                assertContentEquals(doubleArrayOf(40.0, 60.0), response.splDb)
            }
            assertIs<CatalogResponse.Unavailable>(catalog.getResponse(uuid(3)))
            assertNull(catalog.getResponse(uuid(2)))
            assertNull(catalog.getProduct(uuid(99)))
            assertNull(catalog.getResponse(uuid(99)))
            val exported = fixtures.root(catalog.exportBytes())
            assertEquals(fixtures.root(library), exported["responseLibrary"])
            val rawEntry = (fixtures.root(library).getValue("data") as JsonArray).single() as JsonObject
            assertFalse("type" in rawEntry)
            assertFalse("freqResponse" in rawEntry)
            val unsupportedAsset = (exported.getValue("responseFiles") as JsonArray)
                .map { it as JsonObject }.single { it["path"] == JsonPrimitive(unsupportedPath) }
            assertContentEquals(unsupported, fixtures.assetBytes(unsupportedAsset))
            assertEquals(JsonPrimitive(catalogSha256(unsupported)), unsupportedAsset["sha256"])
            assertContentEquals(original, catalog.exportBytes())
            assertEquals(boundary, listOf(storage.reads, storage.writeAttempts, storage.writes, fetcher.requests.size))

            catalog.close()
            assertViews()
            assertContentEquals(original, catalog.exportBytes())
            assertEquals(1, fetcher.closes)
            assertEquals(boundary, listOf(storage.reads, storage.writeAttempts, storage.writes, fetcher.requests.size))
        }
    }

    @Test fun concurrentCatalogInstancesKeepStateAndTransactionsIsolated() = runTest {
        val storageA = MemoryStorage(document(1))
        val storageB = MemoryStorage(document(2, "USB"))
        val fetcherA = Fetcher { _, _ -> error("Unexpected network") }
        val fetcherB = Fetcher { _, _ -> error("Unexpected network") }
        val restartedFetcherA = Fetcher { _, _ -> error("Unexpected reinit network") }
        val catalogA = Catalog()
        val catalogB = Catalog()
        val importedA = document(3, "USB")
        val importedB = document(4, "WIRED")
        val enteredWriteA = CompletableDeferred<Unit>()
        val enteredWriteB = CompletableDeferred<Unit>()
        val releaseWrites = CompletableDeferred<Unit>()
        storageA.beforeWrite = { enteredWriteA.complete(Unit); releaseWrites.await() }
        storageB.beforeWrite = { enteredWriteB.complete(Unit); releaseWrites.await() }
        try {
            catalogA.init({ storageA }, { error("Unexpected bundled load") }, { fetcherA }, StandardTestDispatcher(testScheduler))
            catalogB.init({ storageB }, { error("Unexpected bundled load") }, { fetcherB }, StandardTestDispatcher(testScheduler))
            awaitLoaded(catalogA)
            awaitLoaded(catalogB)
            val publishedB = Channel<CatalogState>(Channel.UNLIMITED)
            val subscriberB = backgroundScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                catalogB.state.collect { publishedB.send(it) }
            }
            assertNotSame(catalogA.state, catalogB.state)
            assertEquals(listOf(uuid(1)), catalogA.getAll().map { it.uuid })
            assertEquals(listOf(uuid(2)), catalogB.getAll().map { it.uuid })

            val importA = launch { catalogA.importSnapshot(catalogA.prepareImport(importedA)) }
            val importB = launch { catalogB.importSnapshot(catalogB.prepareImport(importedB)) }
            enteredWriteA.await()
            enteredWriteB.await()
            releaseWrites.complete(Unit)
            importA.join()
            importB.join()

            assertEquals(listOf(uuid(3)), catalogA.getAll().map { it.uuid })
            assertEquals(listOf(uuid(4)), catalogB.getAll().map { it.uuid })
            assertContentEquals(importedA, catalogA.exportBytes())
            assertContentEquals(importedB, catalogB.exportBytes())
            assertContentEquals(importedA, storageA.active)
            assertContentEquals(importedB, storageB.active)
            runCurrent()
            while (publishedB.tryReceive().isSuccess) { /* Drain B's own import notifications. */ }
            val retainedBState = catalogB.state.value

            catalogA.close()
            assertContentEquals(importedA, catalogA.exportBytes())
            assertEquals(1, fetcherA.closes)
            assertEquals(0, fetcherB.closes)
            catalogA.init({ storageA }, { error("Unexpected bundled reload") }, { restartedFetcherA }, StandardTestDispatcher(testScheduler))
            awaitLoaded(catalogA)
            runCurrent()
            assertContentEquals(importedA, catalogA.exportBytes())
            assertContentEquals(importedB, catalogB.exportBytes())
            assertSame(retainedBState, catalogB.state.value)
            assertTrue(publishedB.tryReceive().isFailure, "A close/reinit must not publish any state to B's subscriber")
            val nextB = document(5)
            catalogB.importSnapshot(catalogB.prepareImport(nextB))
            assertContentEquals(importedA, catalogA.exportBytes())
            assertContentEquals(nextB, catalogB.exportBytes())
            publishedB.awaitCommitted(uuid(5), CatalogOrigin.IMPORT)
            subscriberB.cancel()
        } finally {
            releaseWrites.complete(Unit)
            withContext(NonCancellable) {
                catalogA.close()
                catalogB.close()
            }
        }
        assertEquals(1, fetcherA.closes)
        assertEquals(1, restartedFetcherA.closes)
        assertEquals(1, fetcherB.closes)
    }

    @Test fun repeatedInitPreservesImportedData() = runTest {
        val original = document(1)
        val imported = document(2, "USB")
        val storage = MemoryStorage(original)
        val fetcher = Fetcher { _, _ -> error("Unexpected network") }
        fixtures.withCatalog(this, storage, { error("Unexpected bundled load") }, fetcher) { catalog ->
            awaitLoaded(catalog)
            catalog.importSnapshot(catalog.prepareImport(imported))
            val boundary = listOf(storage.reads, storage.writeAttempts, storage.writes)
            val callers = List(2) {
                launch {
                    catalog.init(
                        storage = { error("Repeated init constructed storage") },
                        loadBundled = { error("Repeated init loaded bundled data") },
                        fetcher = { error("Repeated init constructed fetcher") },
                        dispatcher = StandardTestDispatcher(testScheduler),
                    )
                }
            }
            callers.joinAll()
            assertEquals(listOf(uuid(2)), catalog.getAll().map { it.uuid })
            assertEquals(listOf(uuid(2)), catalog.getHaveResponse().map { it.uuid })
            assertTrue(catalog.getBluetooth().isEmpty())
            assertEquals(CatalogOrigin.IMPORT, catalog.state.value.origin)
            assertContentEquals(imported, catalog.exportBytes())
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
        fixtures.withCatalog(this, storage, { error("Unexpected bundled load") }, firstFetcher) { catalog ->
            val firstEvents = Channel<CatalogState>(Channel.UNLIMITED)
            val secondEvents = Channel<CatalogState>(Channel.UNLIMITED)
            val firstSubscriber = backgroundScope.launch { catalog.state.collect { firstEvents.send(it) } }
            val secondSubscriber = backgroundScope.launch { catalog.state.collect { secondEvents.send(it) } }
            try {
                firstEvents.awaitCommitted(uuid(1), CatalogOrigin.LOCAL)
                secondEvents.awaitCommitted(uuid(1), CatalogOrigin.LOCAL)
                firstSubscriber.cancel()
                firstSubscriber.join()
                val imported = document(2, "USB")
                catalog.importSnapshot(catalog.prepareImport(imported))
                secondEvents.awaitCommitted(uuid(2), CatalogOrigin.IMPORT)
                assertContentEquals(imported, catalog.exportBytes())
                assertContentEquals(imported, storage.active)
                assertEquals(0, firstFetcher.closes)
                catalog.close()
                catalog.init({ storage }, { error("Unexpected bundled load") }, { secondFetcher }, StandardTestDispatcher(testScheduler))
                secondEvents.awaitCommitted(uuid(2), CatalogOrigin.LOCAL)
                val next = document(3, "WIRED")
                catalog.importSnapshot(catalog.prepareImport(next))
                secondEvents.awaitCommitted(uuid(3), CatalogOrigin.IMPORT)
                assertEquals(listOf(uuid(3)), catalog.getAll().map { it.uuid })
                assertContentEquals(next, catalog.exportBytes())
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
        fixtures.withCatalog(this, MemoryStorage(), { document(1) }, helperFetcher) { catalog ->
            catalog.close()
            val caller = launch {
                catalog.init({ storage }, {
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
                awaitLoaded(catalog)
                assertEquals(CatalogOrigin.BUNDLED, catalog.state.value.origin)
                assertEquals(listOf(uuid(2)), catalog.getAll().map { it.uuid })
                assertContentEquals(original, catalog.exportBytes())
                assertNull(catalog.state.value.error)
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
        fixtures.withCatalog(this, storage, { bundledReads++; document(2) }, fetcher) { catalog ->
            catalog.close()
            catalog.close()
            assertFalse(catalog.state.value.loading)
            assertFalse(catalog.state.value.busy)
            assertNull(catalog.state.value.snapshot)
            assertTrue(catalog.getAll().isEmpty())
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
        }, oldFetcher) { catalog ->
            try {
                entered.await()
                val closing = launch { catalog.close() }
                closingEntered.await()
                val restarting = launch {
                    nextAttempted.complete(Unit)
                    catalog.init({ nextFactories++; nextStorage }, { error("Unexpected bundled load") },
                        { nextFetcher }, StandardTestDispatcher(testScheduler))
                }
                nextAttempted.await()
                runCurrent()
                assertFalse(closing.isCompleted)
                assertFalse(restarting.isCompleted)
                assertEquals(0, nextFactories)
                assertEquals(0, nextStorage.reads)
                assertNull(catalog.state.value.snapshot)
                release.complete(Unit)
                closing.join()
                restarting.join()
                awaitLoaded(catalog)
                assertEquals(1, nextFactories)
                assertEquals(CatalogOrigin.LOCAL, catalog.state.value.origin)
                assertEquals(listOf(uuid(2)), catalog.getAll().map { it.uuid })
                assertContentEquals(nextDocument, catalog.exportBytes())
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
        fixtures.withCatalog(this, storage, { error("Unexpected bundled load") }, oldFetcher) { catalog ->
            awaitLoaded(catalog)
            val prepared = catalog.prepareImport(imported)
            val publication = Channel<CatalogState>(Channel.UNLIMITED)
            val observer = backgroundScope.launch { catalog.state.collect { publication.send(it) } }
            try {
                publication.awaitCommitted(uuid(1), CatalogOrigin.LOCAL)
                val importing = launch { catalog.importSnapshot(prepared); returnedSuccess = true }
                enteredWrite.await()
                val closing = launch { catalog.close() }
                closingEntered.await()
                val restarting = launch {
                    nextAttempted.complete(Unit)
                    catalog.init({
                        nextFactories++
                        assertFalse(catalog.state.value.busy)
                        assertFalse(catalog.state.value.loading)
                        assertEquals(CatalogOrigin.IMPORT, catalog.state.value.origin)
                        assertContentEquals(imported, catalog.exportBytes())
                        assertContentEquals(imported, storage.active)
                        storage
                    }, { error("Unexpected bundled load") }, { nextFetcher }, StandardTestDispatcher(testScheduler))
                }
                nextAttempted.await()
                runCurrent()
                assertFalse(closing.isCompleted)
                assertFalse(restarting.isCompleted)
                assertEquals(0, nextFactories)
                assertEquals(listOf(uuid(1)), catalog.getAll().map { it.uuid })
                assertEquals(0, storage.writes)
                releaseWrite.complete(Unit)
                importing.join()
                closing.join()
                restarting.join()
                awaitLoaded(catalog)
                publication.awaitCommitted(uuid(2), CatalogOrigin.LOCAL)
                assertTrue(returnedSuccess)
                assertEquals(1, nextFactories)
                assertEquals(1, storage.writes)
                assertEquals(1, storage.writeAttempts)
                assertEquals(CatalogOrigin.LOCAL, catalog.state.value.origin)
                assertEquals(listOf(uuid(2)), catalog.getAll().map { it.uuid })
                assertEquals(listOf(uuid(2)), catalog.getHaveResponse().map { it.uuid })
                assertTrue(catalog.getBluetooth().isEmpty())
                assertContentEquals(imported, catalog.exportBytes())
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
        fixtures.withCatalog(this, storage, { error("Unexpected bundled load") }, firstFetcher) { catalog ->
            awaitLoaded(catalog)
            catalog.close()
            val retained = catalog.state.value
            var fetcherFactories = 0
            assertFailsWith<IllegalArgumentException> {
                catalog.init({ throw IllegalArgumentException("Storage factory failed") }, { error("Unexpected bundled load") },
                    { fetcherFactories++; error("Must not create fetcher") }, StandardTestDispatcher(testScheduler))
            }
            assertEquals(0, fetcherFactories)
            assertEquals(retained, catalog.state.value)
            assertFailsWith<IllegalArgumentException> {
                catalog.init({ storage }, { error("Unexpected bundled load") },
                    { throw IllegalArgumentException("Fetcher factory failed") }, StandardTestDispatcher(testScheduler))
            }
            assertEquals(retained, catalog.state.value)
            assertContentEquals(original, catalog.exportBytes())
            assertEquals(1, storage.reads)
            assertEquals(0, storage.writeAttempts)
            catalog.init({ storage }, { error("Unexpected bundled load") }, { recoveryFetcher }, StandardTestDispatcher(testScheduler))
            awaitLoaded(catalog)
            assertEquals(CatalogOrigin.LOCAL, catalog.state.value.origin)
            assertContentEquals(original, catalog.exportBytes())
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
        }, failedFetcher) { catalog ->
            try {
                entered.await()
                val closing = launch { closingFailure = runCatching { catalog.close() }.exceptionOrNull() }
                closeEntered.await()
                runCurrent()
                assertFalse(closing.isCompleted)
                release.complete(Unit)
                closing.join()
                assertIs<IllegalArgumentException>(closingFailure)
                assertFalse(catalog.state.value.loading)
                assertFalse(catalog.state.value.busy)
                assertNull(catalog.state.value.snapshot)
                catalog.close()
                assertEquals(1, failedFetcher.closes)
                catalog.init({ recoveryStorage }, { error("Unexpected bundled load") },
                    { recoveryFetcher }, StandardTestDispatcher(testScheduler))
                awaitLoaded(catalog)
                assertEquals(listOf(uuid(2)), catalog.getAll().map { it.uuid })
                assertContentEquals(recoveryStorage.active, catalog.exportBytes())
                assertEquals(0, recoveryStorage.writeAttempts)
                assertTrue(failedFetcher.requests.isEmpty())
                assertTrue(recoveryFetcher.requests.isEmpty())
            } finally { release.complete(Unit) }
        }
        assertEquals(1, recoveryFetcher.closes)
    }

    private suspend fun awaitLoaded(catalog: Catalog): CatalogState = catalog.state.first { !it.loading && !it.busy }

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
