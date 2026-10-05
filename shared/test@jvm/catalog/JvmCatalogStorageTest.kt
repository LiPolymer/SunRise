package ink.lipoly.app.sunrise.catalog

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JvmCatalogStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun missingActiveFileDoesNotCreateTheWritableCatalogDirectory() = runBlocking {
        val directory = temporary.root.toPath().resolve("catalog")
        assertNull(JvmCatalogStorage(directory).readActive())
        assertEquals(false, Files.exists(directory))
    }

    @Test fun diskRoundTripAndReplacementKeepTheExactDocumentAcrossStorageInstances() = runBlocking {
        val directory = temporary.root.toPath().resolve("catalog")
        val original = byteArrayOf(0, 1, 13, 10, -1)
        val replacement = "complete replacement\n".encodeToByteArray()
        val storage = JvmCatalogStorage(directory)
        storage.writeActive(original)
        assertContentEquals(original, JvmCatalogStorage(directory).readActive())
        storage.writeActive(replacement)
        assertContentEquals(replacement, JvmCatalogStorage(directory).readActive())
        assertContentEquals(replacement, Files.readAllBytes(directory.resolve("active.json")))
        Files.list(directory).use { files -> assertEquals(listOf("active.json"), files.map { it.fileName.toString() }.toList()) }
    }

    @Test fun oversizedActiveFileIsRejectedWithoutDeletingOrRewritingIt() = runBlocking {
        val directory = temporary.newFolder("catalog").toPath()
        val active = directory.resolve("active.json")
        val size = MAX_CATALOG_SNAPSHOT_BYTES.toLong() + 1
        RandomAccessFile(active.toFile(), "rw").use { it.setLength(size) }
        assertFailsWith<IllegalArgumentException> { JvmCatalogStorage(directory).readActive() }
        assertEquals(size, Files.size(active))
    }

    @Test fun rejectedOversizedWritePreservesThePreviouslyCommittedFile() = runBlocking {
        val directory = temporary.newFolder("catalog").toPath()
        val storage = JvmCatalogStorage(directory)
        val original = "old complete snapshot".encodeToByteArray()
        storage.writeActive(original)
        assertFailsWith<IllegalArgumentException> { storage.writeActive(ByteArray(MAX_CATALOG_SNAPSHOT_BYTES + 1)) }
        assertContentEquals(original, storage.readActive())
        Files.list(directory).use { files -> assertEquals(listOf("active.json"), files.map { it.fileName.toString() }.toList()) }
    }

    @Test fun failedAtomicExportDoesNotRemoveExistingDestinationAndCleansTemporaryFile() = runBlocking {
        val directory = temporary.newFolder("exports").toPath()
        val destination = Files.createDirectory(directory.resolve("database.json"))
        val oldContents = "must survive a failed replacement".encodeToByteArray()
        val marker = destination.resolve("original.txt")
        Files.write(marker, oldContents)
        assertFailsWith<IOException> { replaceCatalogDocument(destination, "replacement".encodeToByteArray()) }
        assertContentEquals(oldContents, Files.readAllBytes(marker))
        Files.list(directory).use { files -> assertEquals(listOf("database.json"), files.map { it.fileName.toString() }.toList()) }
    }
}
