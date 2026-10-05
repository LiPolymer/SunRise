package ink.lipoly.app.sunrise.catalog

import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class JvmCatalogStorage(
    private val directory: Path = Path.of(System.getProperty("user.home"), ".sunrise", "catalog"),
) : CatalogStorage {
    private val active = directory.resolve("active.json")

    override suspend fun readActive(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            readCatalogDocument(active)
        } catch (_: NoSuchFileException) {
            null
        }
    }

    override suspend fun writeActive(bytes: ByteArray) = withContext(Dispatchers.IO) {
        require(bytes.size <= MAX_CATALOG_SNAPSHOT_BYTES) { "Snapshot exceeds 64 MiB" }
        Files.createDirectories(directory)
        replaceCatalogDocument(active, bytes)
    }
}

/** Bounds both known file sizes and files which grow while being read. Call on Dispatchers.IO. */
internal suspend fun readCatalogDocument(path: Path): ByteArray {
    val size = Files.size(path)
    require(size <= MAX_CATALOG_SNAPSHOT_BYTES) { "Snapshot exceeds 64 MiB" }
    val context = currentCoroutineContext()
    return Files.newInputStream(path).use { input ->
        val output = ByteArrayOutputStream(minOf(size, 64 * 1024L).toInt())
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            context.ensureActive()
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_CATALOG_SNAPSHOT_BYTES - total + 1))
            if (count < 0) break
            require(count <= MAX_CATALOG_SNAPSHOT_BYTES - total) { "Snapshot exceeds 64 MiB" }
            output.write(buffer, 0, count)
            total += count
        }
        context.ensureActive()
        output.toByteArray()
    }
}

/** Never degrades an unsupported atomic move to a truncating or non-atomic replacement. */
internal suspend fun replaceCatalogDocument(path: Path, bytes: ByteArray) {
    require(bytes.size <= MAX_CATALOG_SNAPSHOT_BYTES) { "Snapshot exceeds 64 MiB" }
    val target = path.toAbsolutePath()
    val temporary = Files.createTempFile(target.parent, ".sunrise-catalog-", ".tmp")
    var failure: Throwable? = null
    try {
        val context = currentCoroutineContext()
        FileOutputStream(temporary.toFile()).use { output ->
            var offset = 0
            while (offset < bytes.size) {
                context.ensureActive()
                val count = minOf(64 * 1024, bytes.size - offset)
                output.write(bytes, offset, count)
                offset += count
            }
            output.flush()
            output.fd.sync()
        }
        context.ensureActive()
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        try {
            Files.deleteIfExists(temporary)
        } catch (cleanup: Exception) {
            if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
        }
    }
}
