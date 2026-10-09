package ink.lipoly.app.sunrise.presentation

import ink.lipoly.app.sunrise.catalog.CATALOG_EXPORT_NAME
import ink.lipoly.app.sunrise.catalog.Catalog
import ink.lipoly.app.sunrise.catalog.CatalogCdn
import ink.lipoly.app.sunrise.catalog.CatalogDocuments
import ink.lipoly.app.sunrise.catalog.CatalogSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class CatalogOperationState(
    val documentBusy: Boolean = false,
    val importPreview: CatalogSnapshot? = null,
    val pullActive: Boolean = false,
    val error: Exception? = null,
    val notice: PresentationNotice? = null,
)

/** Owns only the file and pull operations started by one presentation host. */
internal class CatalogOperations(
    private val catalog: Catalog,
    private val documents: CatalogDocuments,
    private val scope: CoroutineScope,
    private val preview: Boolean,
) {
    private val mutableState = MutableStateFlow(CatalogOperationState())
    val state: StateFlow<CatalogOperationState> = mutableState.asStateFlow()

    private val guard = Any()
    private var closed = false
    private var documentJob: Job? = null
    private var pullJob: Job? = null

    fun pull(cdn: CatalogCdn) = synchronized(guard) {
        if (closed) return@synchronized
        if (preview) {
            reportPreviewUnavailable("预览中无法拉取目录")
            return@synchronized
        }
        if (pullJob != null || catalog.state.value.busy || catalog.state.value.loading ||
            state.value.documentBusy || state.value.importPreview != null
        ) return@synchronized

        update { it.copy(error = null, pullActive = true) }
        launchPull {
            catalog.pull(cdn)
        }
    }

    fun cancelPull() = synchronized(guard) {
        if (closed) return@synchronized
        pullJob?.cancel()
        update { it.copy(pullActive = false) }
    }

    fun prepareImport() = synchronized(guard) {
        if (closed) return@synchronized
        if (preview) {
            reportPreviewUnavailable("预览中无法写入目录")
            return@synchronized
        }
        if (!canStartDocumentOperation(allowLoading = false)) return@synchronized

        update { it.copy(documentBusy = true, error = null) }
        launchDocument {
            val bytes = documents.openImport() ?: return@launchDocument
            if (!isDocumentOperationOpen()) return@launchDocument
            val prepared = catalog.prepareImport(bytes)
            if (isDocumentOperationOpen()) {
                update { it.copy(importPreview = prepared) }
            }
        }
    }

    fun export() = synchronized(guard) {
        if (closed) return@synchronized
        if (preview) {
            reportPreviewUnavailable("预览中无法写入目录")
            return@synchronized
        }
        if (!canStartDocumentOperation(allowLoading = true) || catalog.state.value.snapshot == null) return@synchronized

        val bytes = try {
            catalog.exportBytes()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            reportFailure(error)
            return@synchronized
        }
        update { it.copy(documentBusy = true, error = null) }
        launchDocument {
            if (documents.saveExport(bytes, CATALOG_EXPORT_NAME) && isDocumentOperationOpen()) {
                update {
                    it.copy(notice = PresentationNotice("已导出完整数据库"))
                }
            }
        }
    }

    fun confirmImport() = synchronized(guard) {
        if (closed) return@synchronized
        if (preview) {
            reportPreviewUnavailable("预览中无法写入目录")
            return@synchronized
        }
        val snapshot = state.value.importPreview ?: return@synchronized
        if (!canStartDocumentOperation(allowLoading = false, allowPreview = true)) return@synchronized

        update { it.copy(documentBusy = true, importPreview = null, error = null) }
        launchDocument {
            catalog.importSnapshot(snapshot)
            if (isDocumentOperationOpen() && catalog.state.value.error == null) {
                update {
                    it.copy(notice = PresentationNotice("数据库已替换"))
                }
            }
        }
    }

    fun dismissImport() = synchronized(guard) {
        if (closed) return@synchronized
        update { it.copy(importPreview = null) }
    }

    fun clearNotice(expected: PresentationNotice) {
        update { current ->
            if (current.notice === expected) current.copy(notice = null) else current
        }
    }

    fun close() = synchronized(guard) {
        if (closed) return@synchronized
        closed = true
        documentJob?.cancel()
        pullJob?.cancel()
        documentJob = null
        pullJob = null
        mutableState.value = CatalogOperationState()
    }

    private fun canStartDocumentOperation(
        allowLoading: Boolean,
        allowPreview: Boolean = false,
    ): Boolean {
        val catalogState = catalog.state.value
        val operationState = state.value
        return documentJob == null && !catalogState.busy && (allowLoading || !catalogState.loading) &&
            pullJob == null && !operationState.documentBusy && (allowPreview || operationState.importPreview == null)
    }

    private fun launchDocument(block: suspend () -> Unit) {
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isDocumentOperationOpen(job)) reportFailure(error)
            }
        }
        documentJob = job
        job.invokeOnCompletion {
            synchronized(guard) {
                if (documentJob === job) {
                    documentJob = null
                    update { it.copy(documentBusy = false) }
                }
            }
        }
        job.start()
    }

    private fun launchPull(block: suspend () -> Unit) {
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                synchronized(guard) {
                    if (!closed && pullJob === job) reportFailure(error)
                }
            }
        }
        pullJob = job
        job.invokeOnCompletion {
            synchronized(guard) {
                if (pullJob === job) {
                    pullJob = null
                    update { it.copy(pullActive = false) }
                }
            }
        }
        job.start()
    }

    private fun isDocumentOperationOpen(job: Job? = null): Boolean = synchronized(guard) {
        val current = documentJob
        !closed && current != null && (job == null || current === job) && current.isActive
    }

    private fun reportFailure(error: Exception) {
        update {
            it.copy(
                error = error,
                notice = PresentationNotice("", error),
            )
        }
    }

    private fun reportPreviewUnavailable(message: String) {
        val error = IllegalStateException(message)
        update {
            it.copy(error = error, notice = PresentationNotice(message))
        }
    }

    private inline fun update(crossinline transform: (CatalogOperationState) -> CatalogOperationState) {
        synchronized(guard) {
            if (!closed) mutableState.update { transform(it) }
        }
    }
}
