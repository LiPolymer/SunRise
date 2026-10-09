package ink.lipoly.app.sunrise.catalog

import java.awt.Dialog
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JOptionPane
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal class JvmCatalogDocuments(private val window: Frame) : CatalogDocuments, AutoCloseable {
    private class Request(val continuation: CancellableContinuation<*>) {
        @Volatile var dialog: Dialog? = null
    }

    private val pending = AtomicReference<Request?>(null)
    private val closed = AtomicBoolean(false)

    override suspend fun openImport(): ByteArray? {
        val path = chooseFile(save = false, suggestedName = null) ?: return null
        return withContext(Dispatchers.IO) { readCatalogDocument(path, currentCoroutineContext()) }
    }

    override suspend fun saveExport(bytes: ByteArray, suggestedName: String): Boolean {
        require(bytes.size <= MAX_CATALOG_SNAPSHOT_BYTES) { "Snapshot exceeds 64 MiB" }
        val path = chooseFile(save = true, suggestedName = suggestedName) ?: return false
        val exists = withContext(Dispatchers.IO) { Files.exists(path) }
        if (exists && !confirmOverwrite(path)) return false
        withContext(Dispatchers.IO) { replaceCatalogDocument(path, bytes, currentCoroutineContext()) }
        return true
    }

    private suspend fun chooseFile(save: Boolean, suggestedName: String?): Path? = showDialog { request ->
        val picker = FileDialog(
            window,
            if (save) "导出设备数据库" else "导入设备数据库",
            if (save) FileDialog.SAVE else FileDialog.LOAD,
        )
        request.dialog = picker
        if (suggestedName != null) picker.file = suggestedName
        if (!request.continuation.isActive) return@showDialog null
        picker.isVisible = true
        picker.file?.let { File(picker.directory ?: ".", it).toPath() }
    }

    private suspend fun <T> showDialog(action: (Request) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val request = Request(continuation)
            if (closed.get()) {
                continuation.cancel(CancellationException("Catalog document picker is closed"))
                return@suspendCancellableCoroutine
            }
            if (!pending.compareAndSet(null, request)) {
                continuation.resumeWithException(IllegalStateException("A catalog document picker is already open"))
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation {
                pending.compareAndSet(request, null)
                EventQueue.invokeLater { request.dialog?.dispose() }
            }
            if (closed.get()) {
                continuation.cancel(CancellationException("Catalog document picker is closed"))
                return@suspendCancellableCoroutine
            }
            EventQueue.invokeLater {
                try {
                    if (!continuation.isActive) return@invokeLater
                    val result = action(request)
                    pending.compareAndSet(request, null)
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    pending.compareAndSet(request, null)
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally {
                    request.dialog?.dispose()
                    request.dialog = null
                    pending.compareAndSet(request, null)
                }
            }
        }

    /** Runs on the EDT, with the same owner and cancellation disposal as the native picker. */
    private suspend fun confirmOverwrite(path: Path): Boolean = showDialog { request ->
        val pane = JOptionPane(
            "替换现有文件？\n${path.fileName}",
            JOptionPane.WARNING_MESSAGE,
            JOptionPane.YES_NO_OPTION,
            null,
            arrayOf("替换", "取消"),
            "取消",
        )
        val dialog = pane.createDialog(window, "确认覆盖")
        request.dialog = dialog
        if (!request.continuation.isActive) return@showDialog false
        dialog.isVisible = true
        request.continuation.isActive && pane.value == "替换"
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pending.getAndSet(null)?.continuation?.cancel(CancellationException("Catalog document picker is closed"))
    }
}
