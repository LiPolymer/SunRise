package ink.lipoly.app.sunrise.presentation

import ink.lipoly.app.sunrise.catalog.CATALOG_EXPORT_NAME
import ink.lipoly.app.sunrise.catalog.Catalog
import ink.lipoly.app.sunrise.catalog.CatalogByteFetcher
import ink.lipoly.app.sunrise.catalog.CatalogCdn
import ink.lipoly.app.sunrise.catalog.CatalogDocuments
import ink.lipoly.app.sunrise.catalog.CatalogOrigin
import ink.lipoly.app.sunrise.catalog.CatalogState
import ink.lipoly.app.sunrise.catalog.CatalogStorage
import ink.lipoly.app.sunrise.catalog.CatalogTestFixtures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogOperationsTest {
    @Test
    fun cancelledFilePickersDoNotWriteOrReportSuccessOrFailure() = runTest {
        val initial = CatalogTestFixtures.document()
        val storage = MemoryStorage(initial)
        val catalog = openCatalog(storage)
        try {
            val documents = ControlledDocuments().apply {
                importResult = { null }
                exportResult = { _, _ -> false }
            }
            val operations = CatalogOperations(catalog, documents, backgroundScope, preview = false)
            val writesBefore = storage.writes

            operations.prepareImport()
            runCurrent()
            awaitDocumentIdle(operations)
            assertNull(operations.state.value.importPreview)
            assertNull(operations.state.value.error)
            assertNull(operations.state.value.notice)

            operations.export()
            runCurrent()
            awaitDocumentIdle(operations)
            assertEquals(1, documents.exportCalls)
            assertEquals(CATALOG_EXPORT_NAME, documents.exportName)
            assertContentEquals(initial, documents.exportedBytes)
            assertNull(operations.state.value.error)
            assertNull(operations.state.value.notice)
            assertEquals(writesBefore, storage.writes)
            operations.close()
        } finally {
            catalog.close()
        }
    }

    @Test
    fun invalidImportDoesNotOpenConfirmationAndSuccessfulConfirmCommitsOnce() = runTest {
        val storage = MemoryStorage(CatalogTestFixtures.document())
        val catalog = openCatalog(storage)
        try {
            val replacement = CatalogTestFixtures.document(
                catalogueBytes = CatalogTestFixtures.catalogue(
                    listOf(CatalogTestFixtures.product(uuid = CatalogTestFixtures.SECOND_UUID, name = "Replacement")),
                ),
            )
            val documents = ControlledDocuments().apply { importResult = { "not a catalog".encodeToByteArray() } }
            val operations = CatalogOperations(catalog, documents, backgroundScope, preview = false)

            operations.prepareImport()
            runCurrent()
            awaitDocumentIdle(operations)
            val invalidFailure = assertNotNull(operations.state.value.error)
            assertNull(operations.state.value.importPreview)
            assertSame(invalidFailure, operations.state.value.notice?.error)
            assertEquals(0, storage.writes)

            documents.importResult = { replacement }
            operations.prepareImport()
            runCurrent()
            operations.state.first { it.importPreview != null }
            operations.dismissImport()
            assertNull(operations.state.value.importPreview)
            assertEquals(0, storage.writes)

            operations.prepareImport()
            runCurrent()
            val preview = operations.state.first { it.importPreview != null }.importPreview!!
            assertContentEquals(replacement, preview.documentBytes)
            assertNull(operations.state.value.error)

            operations.confirmImport()
            runCurrent()
            awaitDocumentIdle(operations)
            assertNull(operations.state.value.importPreview)
            assertNull(operations.state.value.error)
            assertNotNull(operations.state.value.notice)
            assertEquals(CatalogOrigin.IMPORT, catalog.state.value.origin)
            assertContentEquals(replacement, catalog.exportBytes())
            assertContentEquals(replacement, storage.active)
            assertEquals(1, storage.writes)
            operations.close()
        } finally {
            catalog.close()
        }
    }

    @Test
    fun failedCatalogCommitUsesCatalogErrorAndDoesNotPublishSuccessNotice() = runTest {
        val storage = MemoryStorage(CatalogTestFixtures.document())
        val catalog = openCatalog(storage)
        try {
            val documents = ControlledDocuments().apply {
                importResult = { CatalogTestFixtures.document(CatalogTestFixtures.catalogue(listOf(
                    CatalogTestFixtures.product(uuid = CatalogTestFixtures.SECOND_UUID),
                ))) }
            }
            val operations = CatalogOperations(catalog, documents, backgroundScope, preview = false)
            operations.prepareImport()
            runCurrent()
            operations.state.first { it.importPreview != null }
            storage.writeFailure = IllegalStateException("disk full")

            operations.confirmImport()
            runCurrent()
            awaitDocumentIdle(operations)
            assertEquals("disk full", catalog.state.value.error)
            assertNull(operations.state.value.error)
            assertNull(operations.state.value.notice)
            assertContentEquals(CatalogTestFixtures.document(), catalog.exportBytes())
            assertEquals(0, storage.writes)
            operations.close()
        } finally {
            catalog.close()
        }
    }

    @Test
    fun exportKeepsTheSnapshotCapturedBeforeTheDocumentPickerSuspends() = runTest {
        val initial = CatalogTestFixtures.document()
        val replacement = CatalogTestFixtures.document(CatalogTestFixtures.catalogue(listOf(
            CatalogTestFixtures.product(uuid = CatalogTestFixtures.SECOND_UUID, name = "New snapshot"),
        )))
        val storage = MemoryStorage(initial)
        val catalog = openCatalog(storage)
        try {
            val saving = CompletableDeferred<Boolean>()
            val documents = ControlledDocuments().apply { exportResult = { _, _ -> saving.await() } }
            val operations = CatalogOperations(catalog, documents, backgroundScope, preview = false)

            operations.export()
            runCurrent()
            assertTrue(operations.state.value.documentBusy)
            assertContentEquals(initial, documents.exportedBytes)
            catalog.importSnapshot(catalog.prepareImport(replacement))
            assertContentEquals(replacement, catalog.exportBytes())
            saving.complete(true)
            runCurrent()
            awaitDocumentIdle(operations)

            assertContentEquals(initial, documents.exportedBytes)
            operations.close()
        } finally {
            catalog.close()
        }
    }

    @Test
    fun documentCancellationIsNotConvertedIntoAnOrdinaryError() = runTest {
        val storage = MemoryStorage(CatalogTestFixtures.document())
        val catalog = openCatalog(storage)
        try {
            val cancellation = CancellationException("picker dismissed")
            val documents = ControlledDocuments().apply {
                importResult = { throw cancellation }
                exportResult = { _, _ -> throw cancellation }
            }
            val operations = CatalogOperations(catalog, documents, backgroundScope, preview = false)

            operations.prepareImport()
            runCurrent()
            awaitDocumentIdle(operations)
            assertNull(operations.state.value.importPreview)
            assertNull(operations.state.value.error)
            assertNull(operations.state.value.notice)

            operations.export()
            runCurrent()
            awaitDocumentIdle(operations)
            assertNull(operations.state.value.error)
            assertNull(operations.state.value.notice)
            assertEquals(0, storage.writes)
            operations.close()
        } finally {
            catalog.close()
        }
    }

    @Test
    fun closeDuringAtomicImportDoesNotUndoTheCatalogCommitOrPublishLateNotice() = runTest {
        val replacement = CatalogTestFixtures.document(CatalogTestFixtures.catalogue(listOf(
            CatalogTestFixtures.product(uuid = CatalogTestFixtures.SECOND_UUID, name = "Committed while closing"),
        )))
        val storage = MemoryStorage(CatalogTestFixtures.document())
        val catalog = openCatalog(storage)
        try {
            val documents = ControlledDocuments().apply { importResult = { replacement } }
            val operations = CatalogOperations(catalog, documents, backgroundScope, preview = false)
            operations.prepareImport()
            runCurrent()
            operations.state.first { it.importPreview != null }

            val writeEntered = CompletableDeferred<Unit>()
            val finishWrite = CompletableDeferred<Unit>()
            storage.beforeWrite = {
                writeEntered.complete(Unit)
                finishWrite.await()
            }
            operations.confirmImport()
            runCurrent()
            writeEntered.await()
            operations.close()
            finishWrite.complete(Unit)
            catalog.state.first { !it.loading && !it.busy && it.origin == CatalogOrigin.IMPORT }

            assertContentEquals(replacement, storage.active)
            assertContentEquals(replacement, catalog.exportBytes())
            assertNull(operations.state.value.importPreview)
            assertNull(operations.state.value.notice)
            assertFalse(operations.state.value.documentBusy)
        } finally {
            catalog.close()
        }
    }

    @Test
    fun previewActionsRejectWritesAndPullsWithoutCallingDocumentsOrNetwork() = runTest {
        val storage = MemoryStorage(CatalogTestFixtures.document())
        val fetcher = CountingFetcher { _, _ -> error("Preview attempted a network request") }
        val catalog = openCatalog(storage, fetcher)
        try {
            val documents = ControlledDocuments()
            val operations = CatalogOperations(catalog, documents, backgroundScope, preview = true)

            operations.prepareImport()
            assertIs<IllegalStateException>(operations.state.value.error)
            val writeNotice = operations.state.value.notice!!
            operations.clearNotice(writeNotice.copy())
            assertSame(writeNotice, operations.state.value.notice)
            operations.clearNotice(writeNotice)
            assertNull(operations.state.value.notice)

            operations.export()
            assertIs<IllegalStateException>(operations.state.value.error)
            operations.pull(CatalogCdn.CHINA)
            assertIs<IllegalStateException>(operations.state.value.error)
            assertEquals(0, documents.importCalls)
            assertEquals(0, documents.exportCalls)
            assertTrue(fetcher.requests.isEmpty())
            assertEquals(0, storage.writes)
            operations.close()
        } finally {
            catalog.close()
        }
    }

    @Test
    fun delayedFileResultAfterCloseCannotPublishPreviewOrCloseCatalogOrScope() = runTest {
        val initial = CatalogTestFixtures.document()
        val storage = MemoryStorage(initial)
        val catalog = openCatalog(storage)
        try {
            val selection = CompletableDeferred<ByteArray?>()
            val documents = ControlledDocuments().apply {
                importResult = { withContext(NonCancellable) { selection.await() } }
            }
            val operations = CatalogOperations(catalog, documents, this, preview = false)
            val sibling = launch { awaitCancellation() }
            operations.prepareImport()
            runCurrent()
            assertTrue(operations.state.value.documentBusy)

            operations.close()
            selection.complete(CatalogTestFixtures.document(CatalogTestFixtures.catalogue(listOf(
                CatalogTestFixtures.product(uuid = CatalogTestFixtures.SECOND_UUID),
            ))))
            runCurrent()

            assertNull(operations.state.value.importPreview)
            assertFalse(operations.state.value.documentBusy)
            assertContentEquals(initial, catalog.exportBytes())
            assertTrue(sibling.isActive)
            assertTrue(coroutineContext[kotlinx.coroutines.Job]!!.isActive)
            sibling.cancel()
        } finally {
            catalog.close()
        }
    }

    @Test
    fun cancelAndCloseStopOnlyOwnedPullsAndLeaveOtherCatalogSubscribersUsable() = runTest {
        val storageA = MemoryStorage(CatalogTestFixtures.document())
        val storageB = MemoryStorage(CatalogTestFixtures.document(CatalogTestFixtures.catalogue(listOf(
            CatalogTestFixtures.product(uuid = CatalogTestFixtures.SECOND_UUID),
        ))))
        val requests = Channel<Unit>(Channel.UNLIMITED)
        var cancelledRequests = 0
        val fetcherA = CountingFetcher { _, _ ->
            requests.send(Unit)
            try {
                awaitCancellation()
            } catch (cancelled: CancellationException) {
                cancelledRequests++
                throw cancelled
            }
        }
        val catalogA = openCatalog(storageA, fetcherA)
        val catalogB = openCatalog(storageB)
        try {
            val observations = mutableListOf<CatalogState>()
            val subscriber = backgroundScope.launch { catalogB.state.collect { observations += it } }
            runCurrent()
            val documents = ControlledDocuments()
            val operations = CatalogOperations(catalogA, documents, backgroundScope, preview = false)

            operations.pull(CatalogCdn.OVERSEAS)
            runCurrent()
            requests.receive()
            assertTrue(operations.state.value.pullActive)
            operations.cancelPull()
            runCurrent()
            assertEquals(1, cancelledRequests)
            assertFalse(operations.state.value.pullActive)
            assertNull(operations.state.value.error)
            assertNull(operations.state.value.notice)
            assertTrue(subscriber.isActive)

            operations.pull(CatalogCdn.CHINA)
            runCurrent()
            requests.receive()
            operations.close()
            runCurrent()
            assertEquals(2, cancelledRequests)
            assertTrue(subscriber.isActive)
            assertContentEquals(storageA.active, catalogA.exportBytes())

            val catalogBBytes = catalogB.exportBytes()
            assertContentEquals(storageB.active, catalogBBytes)
            assertEquals(listOf(CatalogTestFixtures.SECOND_UUID), catalogB.getAll().map { it.uuid })
            val beforeUpdate = observations.size
            val nextA = CatalogTestFixtures.document(CatalogTestFixtures.catalogue(listOf(
                CatalogTestFixtures.product(uuid = CatalogTestFixtures.UUID, name = "Independent update"),
            )))
            catalogA.importSnapshot(catalogA.prepareImport(nextA))
            assertContentEquals(nextA, storageA.active)
            assertEquals(beforeUpdate, observations.size)

            val nextB = CatalogTestFixtures.document(CatalogTestFixtures.catalogue(listOf(
                CatalogTestFixtures.product(uuid = CatalogTestFixtures.SECOND_UUID, name = "Subscriber update"),
            )))
            catalogB.importSnapshot(catalogB.prepareImport(nextB))
            runCurrent()
            assertContentEquals(nextB, observations.last().snapshot?.documentBytes)
            assertContentEquals(nextB, catalogB.exportBytes())
            assertContentEquals(nextB, storageB.active)
            assertEquals(1, storageB.writes)
            subscriber.cancel()
        } finally {
            catalogA.close()
            catalogB.close()
            requests.close()
        }
    }

    private suspend fun awaitDocumentIdle(operations: CatalogOperations) {
        operations.state.first { !it.documentBusy }
    }

    private suspend fun TestScope.openCatalog(
        storage: MemoryStorage,
        fetcher: CatalogByteFetcher = CountingFetcher { _, _ -> error("Unexpected catalog fetch") },
    ): Catalog {
        val catalog = Catalog()
        catalog.init(
            storage = { storage },
            loadBundled = { error("Existing storage must be used") },
            fetcher = { fetcher },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        catalog.state.first { !it.loading && !it.busy }
        return catalog
    }

    private class ControlledDocuments : CatalogDocuments {
        var importCalls = 0
        var exportCalls = 0
        var exportName: String? = null
        var exportedBytes: ByteArray? = null
        var importResult: suspend () -> ByteArray? = { null }
        var exportResult: suspend (ByteArray, String) -> Boolean = { _, _ -> false }

        override suspend fun openImport(): ByteArray? {
            importCalls++
            return importResult()
        }

        override suspend fun saveExport(bytes: ByteArray, suggestedName: String): Boolean {
            exportCalls++
            exportName = suggestedName
            exportedBytes = bytes.copyOf()
            return exportResult(bytes, suggestedName)
        }
    }

    private class MemoryStorage(var active: ByteArray? = null) : CatalogStorage {
        var writes = 0
        var writeFailure: Exception? = null
        var beforeWrite: suspend () -> Unit = {}

        override suspend fun readActive(): ByteArray? = active?.copyOf()

        override suspend fun writeActive(bytes: ByteArray) {
            beforeWrite()
            writeFailure?.let { throw it }
            active = bytes.copyOf()
            writes++
        }
    }

    private class CountingFetcher(
        private val body: suspend (String, Int) -> ByteArray,
    ) : CatalogByteFetcher {
        val requests = mutableListOf<Pair<String, Int>>()

        override suspend fun fetch(url: String, maxBytes: Int): ByteArray {
            requests += url to maxBytes
            return body(url, maxBytes)
        }

        override fun close() = Unit
    }
}
