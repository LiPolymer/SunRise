package ink.lipoly.app.sunrise.catalog

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import java.io.IOException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable
internal fun rememberCatalogDocuments(): CatalogDocuments {
    if (LocalInspectionMode.current) return PreviewCatalogDocuments
    val resolver = LocalContext.current.contentResolver
    val picker = remember { AndroidCatalogDocumentPicker() }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        picker.onResult(DocumentAction.IMPORT, it)
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        picker.onResult(DocumentAction.EXPORT, it)
    }
    DisposableEffect(picker) {
        onDispose { picker.close() }
    }
    return remember(resolver, picker, open, save) {
        AndroidCatalogDocuments(
            resolver = resolver,
            picker = picker,
            launchImport = { open.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
            launchExport = { save.launch(it) },
        )
    }
}

private object PreviewCatalogDocuments : CatalogDocuments {
    override suspend fun openImport(): ByteArray? = null
    override suspend fun saveExport(bytes: ByteArray, suggestedName: String): Boolean = false
}

private enum class DocumentAction { IMPORT, EXPORT }

private class AndroidCatalogDocumentPicker {
    private class Pending(val action: DocumentAction, var continuation: CancellableContinuation<Uri?>?)

    private val lock = Any()
    private var pending: Pending? = null
    private var closed = false

    suspend fun await(action: DocumentAction, launch: () -> Unit): Uri? = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            val request = synchronized(lock) {
                check(!closed) { "The catalog document picker is closed" }
                check(pending == null) { "A catalog document picker is still open; finish or dismiss it first" }
                Pending(action, continuation).also { pending = it }
            }
            continuation.invokeOnCancellation {
                synchronized(lock) {
                    // Retain the in-flight request as a tombstone until its result arrives.
                    // An old dialog must never complete a newer request's continuation.
                    if (pending === request) request.continuation = null
                }
            }
            if (!continuation.isActive) {
                // No dialog was launched, so no result can arrive for this request.
                synchronized(lock) { if (pending === request) pending = null }
                return@suspendCancellableCoroutine
            }
            try {
                launch()
            } catch (error: Exception) {
                synchronized(lock) { if (pending === request) pending = null }
                continuation.resumeWithException(error)
            }
        }
    }

    fun onResult(action: DocumentAction, uri: Uri?) {
        val continuation = synchronized(lock) {
            val request = pending ?: return
            if (request.action != action) return
            pending = null
            request.continuation.also { request.continuation = null }
        }
        continuation?.resume(uri)
    }

    fun close() {
        val continuation = synchronized(lock) {
            closed = true
            pending?.let { request -> request.continuation.also { request.continuation = null } }
        }
        continuation?.cancel(CancellationException("Catalog document picker was disposed"))
    }
}

private class AndroidCatalogDocuments(
    private val resolver: ContentResolver,
    private val picker: AndroidCatalogDocumentPicker,
    private val launchImport: () -> Unit,
    private val launchExport: (String) -> Unit,
) : CatalogDocuments {
    override suspend fun openImport(): ByteArray? {
        val uri = picker.await(DocumentAction.IMPORT, launchImport) ?: return null
        return withContext(Dispatchers.IO) {
            try {
                currentCoroutineContext().ensureActive()
                val input = resolver.openInputStream(uri)
                    ?: throw IOException("The document provider did not open an input stream")
                input.use { readAndroidCatalogDocument(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw IOException("Cannot read the selected catalog snapshot: ${error.message ?: error.javaClass.simpleName}", error)
            }
        }
    }

    override suspend fun saveExport(bytes: ByteArray, suggestedName: String): Boolean {
        require(bytes.size <= MAX_CATALOG_SNAPSHOT_BYTES) { "Snapshot exceeds 64 MiB" }
        val uri = picker.await(DocumentAction.EXPORT) { launchExport(suggestedName) } ?: return false
        return withContext(Dispatchers.IO) {
            try {
                currentCoroutineContext().ensureActive()
                val output = resolver.openOutputStream(uri, "wt")
                    ?: throw IOException("The document provider did not open an output stream")
                output.use { writeAndroidCatalogDocument(it, bytes) }
                currentCoroutineContext().ensureActive()
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw IOException(
                    "Cannot export the catalog snapshot; the document provider may have left an incomplete file. " +
                        (error.message ?: error.javaClass.simpleName),
                    error,
                )
            }
        }
    }
}
