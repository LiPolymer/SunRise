package ink.lipoly.app.sunrise.catalog

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AndroidCatalogStorage(context: Context) : CatalogStorage {
    private val directory = File(context.applicationContext.filesDir, "catalog")
    private val activeFile = File(directory, "active.json")
    private val backupFile = File(directory, "active.json.bak")
    private val newFile = File(directory, "active.json.new")
    private val atomicFile = AtomicFile(activeFile)

    override suspend fun readActive(): ByteArray? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!activeFile.exists() && !backupFile.exists()) return@withLock null
            atomicFile.openRead().use { input ->
                checkBackupRestored()
                readAndroidCatalogDocument(input)
            }
        }
    }

    override suspend fun writeActive(bytes: ByteArray) = withContext(Dispatchers.IO) {
        require(bytes.size <= MAX_CATALOG_SNAPSHOT_BYTES) { "Snapshot exceeds 64 MiB" }
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IOException("Cannot create the catalog storage directory")
            }
            if (backupFile.exists()) {
                atomicFile.openRead().use { checkBackupRestored() }
            }
            if (activeFile.exists() && !activeFile.isFile) {
                throw IOException("The active catalog path is not a regular file")
            }
            // Before Android 11, startWrite logs a failed backup rename and still truncates
            // the active file. Perform that rename with a checked result before handing off.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && activeFile.exists()) {
                if (!activeFile.renameTo(backupFile)) {
                    throw IOException("Cannot preserve the previous catalog snapshot")
                }
            }
            val output = atomicFile.startWrite()
            try {
                writeAndroidCatalogDocument(output, bytes)
                // AtomicFile logs sync/commit failures instead of throwing them.
                output.fd.sync()
                currentCoroutineContext().ensureActive()
                atomicFile.finishWrite(output)
                val uncommitted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) newFile else backupFile
                if (uncommitted.exists()) {
                    throw IOException("Cannot atomically commit the catalog snapshot")
                }
            } catch (error: Throwable) {
                atomicFile.failWrite(output)
                throw error
            }
        }
    }

    private fun checkBackupRestored() {
        if (backupFile.exists()) throw IOException("Cannot restore the previous catalog snapshot")
    }

    private companion object {
        // A retiring Activity may still be finishing its non-cancellable commit.
        val mutex = Mutex()
    }
}

internal suspend fun readAndroidCatalogDocument(input: InputStream): ByteArray {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    val output = ByteArrayOutputStream(DEFAULT_BUFFER_SIZE)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer, 0, minOf(buffer.size, MAX_CATALOG_SNAPSHOT_BYTES - output.size() + 1))
        if (count < 0) break
        if (count > MAX_CATALOG_SNAPSHOT_BYTES - output.size()) {
            throw IOException("Snapshot exceeds 64 MiB")
        }
        output.write(buffer, 0, count)
    }
    currentCoroutineContext().ensureActive()
    return output.toByteArray()
}

internal suspend fun writeAndroidCatalogDocument(output: OutputStream, bytes: ByteArray) {
    require(bytes.size <= MAX_CATALOG_SNAPSHOT_BYTES) { "Snapshot exceeds 64 MiB" }
    var offset = 0
    while (offset < bytes.size) {
        currentCoroutineContext().ensureActive()
        val count = minOf(DEFAULT_BUFFER_SIZE, bytes.size - offset)
        output.write(bytes, offset, count)
        offset += count
    }
    currentCoroutineContext().ensureActive()
    output.flush()
}
