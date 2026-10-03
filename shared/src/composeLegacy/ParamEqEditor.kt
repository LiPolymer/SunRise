package ink.lipoly.app.sunrise.composeLegacy

import ink.lipoly.app.sunrise.drop.DropException
import ink.lipoly.app.sunrise.drop.GaiaBluetrumPeqCodec
import ink.lipoly.app.sunrise.drop.GaiaControls
import ink.lipoly.app.sunrise.drop.GaiaParamEqState
import ink.lipoly.app.sunrise.drop.GaiaPeqBand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal enum class ParamEqEditPhase { LOADING, READY, PENDING, WRITING, FAILED, UNAVAILABLE }

internal data class ParamEqEditState(
    val phase: ParamEqEditPhase,
    val confirmed: GaiaParamEqState? = null,
    val draft: List<GaiaPeqBand> = emptyList(),
    val error: Exception? = null,
    val isEditing: Boolean = false,
    val canUndo: Boolean = false,
)

/**
 * UI-context-confined editor for one controls/binding lifetime. The one worker owns all device
 * operations; its channel is only a wake-up, never a queue of obsolete parameter snapshots.
 */
internal class ParamEqEditor(
    scope: CoroutineScope,
    private val controls: GaiaControls,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val mutableState = MutableStateFlow(ParamEqEditState(ParamEqEditPhase.LOADING))
    val state: StateFlow<ParamEqEditState> = mutableState.asStateFlow()

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var closed = false
    private var reloadRequested = true
    private var flushRequested = false
    private var editVersion = 0L
    private var inflightVersion: Long? = null
    private var lastWriteStart: TimeMark? = null
    private var editStart: List<GaiaPeqBand>? = null
    private var undoSnapshot: List<GaiaPeqBand>? = null
    private val worker = scope.launch { runWorker() }

    init {
        wake.trySend(Unit)
    }

    fun beginEdit() {
        val current = state.value
        if (closed || !current.phase.allowsEditing() || current.isEditing) return
        editStart = current.draft
        mutableState.value = current.copy(isEditing = true, canUndo = false)
    }

    fun editBand(band: GaiaPeqBand) {
        val current = state.value
        if (closed || !current.phase.allowsEditing()) return
        require(band.index in current.draft.indices) { "Band index is outside the loaded EQ" }
        if (current.draft[band.index] == band) return
        val bands = current.draft.toMutableList()
        bands[band.index] = band
        GaiaBluetrumPeqCodec.validateBands(bands)
        publishDraft(current, bands)
    }

    /** Publishes one validated complete configuration, including flatten/undo operations. */
    fun replaceDraft(bands: List<GaiaPeqBand>) {
        val current = state.value
        if (closed || !current.phase.allowsEditing()) return
        require(bands.size == current.confirmed?.bands?.size) { "Refresh before changing the band count" }
        GaiaBluetrumPeqCodec.validateBands(bands)
        if (bands == current.draft) return
        publishDraft(current, bands.toList())
    }

    private fun publishDraft(current: ParamEqEditState, snapshot: List<GaiaPeqBand>) {
        editVersion++
        val phase = when {
            inflightVersion != null -> ParamEqEditPhase.WRITING
            snapshot == current.confirmed?.bands -> ParamEqEditPhase.READY
            else -> ParamEqEditPhase.PENDING
        }
        mutableState.value = current.copy(
            phase = phase,
            draft = snapshot,
            error = null,
            canUndo = canUndo(phase, current.isEditing),
        )
        wake.trySend(Unit)
    }

    fun endEdit() {
        val current = state.value
        if (closed || !current.isEditing) return
        val start = editStart
        editStart = null
        if (current.phase.allowsEditing() && start != null && start != current.draft) {
            undoSnapshot = start
        }
        mutableState.value = current.copy(
            isEditing = false,
            canUndo = canUndo(current.phase, false),
        )
        // A canceled/failed gesture still ends, but must not restart the stopped worker.
        if (current.phase.allowsEditing()) flush()
    }

    fun undo() {
        val current = state.value
        if (closed || !current.canUndo || current.isEditing || !current.phase.allowsEditing()) return
        val snapshot = undoSnapshot ?: return
        undoSnapshot = null
        replaceDraft(snapshot)
        mutableState.value = state.value.copy(canUndo = false)
        flush()
    }

    /** Release/commit bypasses the next throttle wait, not an already-running transaction. */
    fun flush() {
        val current = state.value
        if (closed || !current.phase.allowsEditing()) return
        if (inflightVersion == null && current.draft == current.confirmed?.bands) return
        flushRequested = true
        wake.trySend(Unit)
    }

    /** Only idle or failed states may discard a draft and reload the actual device. */
    fun refresh() {
        val current = state.value
        if (closed || current.isEditing || inflightVersion != null ||
            current.phase == ParamEqEditPhase.PENDING || current.phase == ParamEqEditPhase.WRITING ||
            current.phase == ParamEqEditPhase.LOADING
        ) return
        editVersion++
        editStart = null
        undoSnapshot = null
        flushRequested = false
        reloadRequested = true
        mutableState.value = ParamEqEditState(ParamEqEditPhase.LOADING)
        wake.trySend(Unit)
    }

    fun close() {
        if (closed) return
        closed = true
        reloadRequested = false
        flushRequested = false
        editStart = null
        undoSnapshot = null
        mutableState.value = ParamEqEditState(ParamEqEditPhase.UNAVAILABLE)
        worker.cancel()
        wake.close()
    }

    private suspend fun runWorker() {
        while (wake.receiveCatching().isSuccess) {
            while (!closed) {
                if (reloadRequested) {
                    reloadRequested = false
                    load()
                    continue
                }
                val current = state.value
                if (!current.phase.allowsEditing()) break
                if (current.draft == current.confirmed?.bands) {
                    flushRequested = false
                    mutableState.value = current.copy(
                        phase = ParamEqEditPhase.READY,
                        canUndo = canUndo(ParamEqEditPhase.READY, current.isEditing),
                    )
                    break
                }
                if (!flushRequested) {
                    val remaining = lastWriteStart?.let { 150.milliseconds - it.elapsedNow() }
                    if (remaining != null && remaining.isPositive()) {
                        // Round upward: a sub-millisecond remainder must not start a write early.
                        val wholeMillis = remaining.inWholeMilliseconds
                        val waitMillis = wholeMillis + if (remaining > wholeMillis.milliseconds) 1L else 0L
                        withTimeoutOrNull(waitMillis) { wake.receive() }
                        continue // Re-read the latest draft, flush flag and monotonic clock.
                    }
                }
                writeLatest()
            }
        }
    }

    private suspend fun load() {
        try {
            val actual = controls.getParamEq()
            if (closed) return
            mutableState.value = ParamEqEditState(
                phase = ParamEqEditPhase.READY,
                confirmed = actual,
                draft = actual.bands.toList(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (closed) return
            val phase = if (error is DropException.UnsupportedCapability || error is DropException.UnsupportedDevice) {
                ParamEqEditPhase.UNAVAILABLE
            } else {
                ParamEqEditPhase.FAILED
            }
            mutableState.value = ParamEqEditState(phase, error = error)
            discardSignals()
        }
    }

    private suspend fun writeLatest() {
        val current = state.value
        val sent = current.draft
        val version = editVersion
        inflightVersion = version
        flushRequested = false
        lastWriteStart = timeSource.markNow()
        mutableState.value = current.copy(
            phase = ParamEqEditPhase.WRITING,
            canUndo = canUndo(ParamEqEditPhase.WRITING, current.isEditing),
        )
        try {
            val actual = controls.setParamEq(sent)
            if (actual.bands != sent) throw DropException.ParamEqMismatch(actual)
            if (closed) return
            inflightVersion = null
            val latest = state.value
            val phase = if (editVersion == version || latest.draft == actual.bands) {
                ParamEqEditPhase.READY
            } else {
                ParamEqEditPhase.PENDING
            }
            // An older readback only confirms the device; it never replaces a newer local draft.
            mutableState.value = latest.copy(
                phase = phase,
                confirmed = actual,
                error = null,
                canUndo = canUndo(phase, latest.isEditing),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (closed) return
            inflightVersion = null
            val latest = state.value
            val confirmed = when (error) {
                is DropException.ParamEqMismatch -> error.observed
                is DropException.Unverified, is DropException.Disconnected, is DropException.NotReady -> null
                else -> latest.confirmed
            }
            mutableState.value = latest.copy(
                phase = ParamEqEditPhase.FAILED,
                confirmed = confirmed,
                error = error,
                canUndo = false,
            )
            discardSignals()
        }
    }

    private fun discardSignals() {
        flushRequested = false
        while (wake.tryReceive().isSuccess) { /* Discard wake-ups, not the retained failed draft. */ }
    }

    private fun canUndo(phase: ParamEqEditPhase, isEditing: Boolean): Boolean =
        undoSnapshot != null && !isEditing && phase.allowsEditing()
}

private fun ParamEqEditPhase.allowsEditing(): Boolean =
    this == ParamEqEditPhase.READY || this == ParamEqEditPhase.PENDING || this == ParamEqEditPhase.WRITING
