package ink.lipoly.app.sunrise.composeLegacy

import ink.lipoly.app.sunrise.drop.DropException
import ink.lipoly.app.sunrise.drop.GaiaPeqParameters
import ink.lipoly.app.sunrise.drop.GaiaControls
import ink.lipoly.app.sunrise.drop.GaiaParamEqState
import ink.lipoly.app.sunrise.drop.GaiaPeqBand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal enum class ParamEqEditPhase { LOADING, READY, SENT, PENDING, WRITING, FAILED, UNAVAILABLE }

internal enum class ParamEqSubmitMode { REALTIME, MANUAL }

internal data class ParamEqEditState(
    val phase: ParamEqEditPhase,
    val confirmed: GaiaParamEqState? = null,
    val lastSent: List<GaiaPeqBand>? = null,
    val draft: List<GaiaPeqBand> = emptyList(),
    val error: Exception? = null,
    val isEditing: Boolean = false,
    val canUndo: Boolean = false,
    val submitMode: ParamEqSubmitMode = ParamEqSubmitMode.REALTIME,
) {
    val canSubmit: Boolean
        get() = submitMode == ParamEqSubmitMode.MANUAL && !isEditing &&
            (phase == ParamEqEditPhase.READY || phase == ParamEqEditPhase.PENDING)

    val canChangeSubmitMode: Boolean
        get() = !isEditing && (phase == ParamEqEditPhase.READY || phase == ParamEqEditPhase.SENT)
}

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
    private var queuedSubmission: List<GaiaPeqBand>? = null
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
        if (closed || !current.allowsEditing() || current.isEditing) return
        editStart = current.draft
        mutableState.value = current.copy(isEditing = true, canUndo = false)
    }

    fun editBand(band: GaiaPeqBand) {
        val current = state.value
        if (closed || !current.allowsEditing()) return
        require(band.index in current.draft.indices) { "Band index is outside the loaded EQ" }
        if (current.draft[band.index] == band) return
        val bands = current.draft.toMutableList()
        bands[band.index] = band
        GaiaPeqParameters.validateBands(bands)
        publishDraft(current, bands)
    }

    /** Publishes one validated complete configuration, including flatten/undo operations. */
    fun replaceDraft(bands: List<GaiaPeqBand>) {
        val current = state.value
        if (closed || !current.allowsEditing()) return
        require(bands.size == current.draft.size) { "Refresh before changing the band count" }
        GaiaPeqParameters.validateBands(bands)
        if (bands == current.draft) return
        publishDraft(current, bands.toList())
    }

    private fun publishDraft(current: ParamEqEditState, snapshot: List<GaiaPeqBand>) {
        editVersion++
        val phase = when {
            inflightVersion != null -> ParamEqEditPhase.WRITING
            snapshot == current.transportBaseline -> current.settledPhase
            else -> ParamEqEditPhase.PENDING
        }
        mutableState.value = current.copy(
            phase = phase,
            draft = snapshot,
            error = null,
            canUndo = canUndo(phase, current.isEditing),
        )
        if (current.submitMode == ParamEqSubmitMode.REALTIME) wake.trySend(Unit)
    }

    fun endEdit() {
        val current = state.value
        if (closed || !current.isEditing) return
        val start = editStart
        editStart = null
        if (current.allowsEditing() && start != null && start != current.draft) {
            undoSnapshot = start
        }
        mutableState.value = current.copy(
            isEditing = false,
            canUndo = canUndo(current.phase, false),
        )
        // A canceled/failed gesture still ends, but must not restart the stopped worker.
        if (current.allowsEditing()) flush()
    }

    fun undo() {
        val current = state.value
        if (closed || !current.canUndo || current.isEditing || !current.allowsEditing()) return
        val snapshot = undoSnapshot ?: return
        undoSnapshot = null
        replaceDraft(snapshot)
        mutableState.value = state.value.copy(canUndo = false)
        flush()
    }

    /** Switching an idle editor never commits a draft or replays an old realtime wake-up. */
    fun setSubmitMode(mode: ParamEqSubmitMode) {
        val current = state.value
        if (closed || !current.canChangeSubmitMode || current.submitMode == mode) return
        discardSignals()
        mutableState.value = current.copy(submitMode = mode)
    }

    /** Capture one complete manual transaction and close the tap/edit gate synchronously. */
    fun submit() {
        val current = state.value
        if (closed || !current.canSubmit) return
        queuedSubmission = current.draft.toList()
        mutableState.value = current.copy(phase = ParamEqEditPhase.WRITING, canUndo = false)
        wake.trySend(Unit)
    }

    /** Release/commit bypasses the next throttle wait, not an already-running transaction. */
    fun flush() {
        val current = state.value
        if (closed || current.submitMode == ParamEqSubmitMode.MANUAL || !current.allowsEditing()) return
        if (inflightVersion == null && current.draft == current.transportBaseline) return
        flushRequested = true
        wake.trySend(Unit)
    }

    /** Manual idle refresh intentionally discards an unsent draft; realtime pending work is retained. */
    fun refresh() {
        val current = state.value
        if (closed || current.isEditing || inflightVersion != null ||
            (current.phase == ParamEqEditPhase.PENDING && current.submitMode == ParamEqSubmitMode.REALTIME) ||
            current.phase == ParamEqEditPhase.WRITING ||
            current.phase == ParamEqEditPhase.LOADING
        ) return
        editVersion++
        editStart = null
        undoSnapshot = null
        discardSignals()
        reloadRequested = true
        mutableState.value = ParamEqEditState(ParamEqEditPhase.LOADING, submitMode = current.submitMode)
        wake.trySend(Unit)
    }

    fun close() {
        if (closed) return
        closed = true
        reloadRequested = false
        discardSignals()
        editStart = null
        undoSnapshot = null
        mutableState.value = ParamEqEditState(ParamEqEditPhase.UNAVAILABLE, submitMode = state.value.submitMode)
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
                if (current.submitMode == ParamEqSubmitMode.MANUAL) {
                    val sent = queuedSubmission ?: break
                    queuedSubmission = null
                    writeSnapshot(sent)
                    break // A manual click is exactly one transaction, never an automatic follow-up.
                }
                if (!current.phase.allowsEditing()) break
                if (current.draft == current.transportBaseline) {
                    flushRequested = false
                    mutableState.value = current.copy(
                        phase = current.settledPhase,
                        canUndo = canUndo(current.settledPhase, current.isEditing),
                    )
                    break
                }
                if (!flushRequested) {
                    val remaining = lastWriteStart?.let { 150.milliseconds - it.elapsedNow() }
                    if (remaining != null && remaining.isPositive()) {
                        // Round upward: a sub-millisecond remainder must not start a write early.
                        val wholeMillis = remaining.inWholeMilliseconds
                        val waitMillis = wholeMillis + if (remaining > wholeMillis.milliseconds) 1L else 0L
                        withTimeoutOrNull(waitMillis.milliseconds) { wake.receive() }
                        continue // Re-read the latest draft, flush flag and monotonic clock.
                    }
                }
                writeSnapshot(current.draft)
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
                submitMode = state.value.submitMode,
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
            mutableState.value = ParamEqEditState(phase, error = error, submitMode = state.value.submitMode)
            discardSignals()
        }
    }

    private suspend fun writeSnapshot(sent: List<GaiaPeqBand>) {
        val current = state.value
        val version = editVersion
        inflightVersion = version
        flushRequested = false
        lastWriteStart = timeSource.markNow()
        mutableState.value = current.copy(
            phase = ParamEqEditPhase.WRITING,
            confirmed = null,
            canUndo = canUndo(ParamEqEditPhase.WRITING, current.isEditing),
        )
        try {
            controls.setParamEq(sent)
            if (closed) return
            inflightVersion = null
            val latest = state.value
            val phase = if (editVersion == version || latest.draft == sent) {
                ParamEqEditPhase.SENT
            } else {
                ParamEqEditPhase.PENDING
            }
            // A successful transport never confirms device state or replaces a newer local draft.
            mutableState.value = latest.copy(
                phase = phase,
                lastSent = sent,
                error = null,
                canUndo = canUndo(phase, latest.isEditing),
            )
        } catch (error: Exception) {
            if (error is CancellationException) currentCoroutineContext().ensureActive()
            if (closed) return
            inflightVersion = null
            val latest = state.value
            mutableState.value = latest.copy(
                phase = ParamEqEditPhase.FAILED,
                confirmed = null,
                lastSent = null,
                error = error,
                canUndo = false,
            )
            discardSignals()
        }
    }

    private fun discardSignals() {
        flushRequested = false
        queuedSubmission = null
        while (wake.tryReceive().isSuccess) { /* Discard wake-ups, not the retained failed draft. */ }
    }

    private fun canUndo(phase: ParamEqEditPhase, isEditing: Boolean): Boolean =
        undoSnapshot != null && !isEditing && phase.allowsEditing() &&
            (state.value.submitMode == ParamEqSubmitMode.REALTIME || phase != ParamEqEditPhase.WRITING)
}

private fun ParamEqEditState.allowsEditing(): Boolean =
    phase.allowsEditing() && (submitMode == ParamEqSubmitMode.REALTIME || phase != ParamEqEditPhase.WRITING)

private fun ParamEqEditPhase.allowsEditing(): Boolean =
    this == ParamEqEditPhase.READY || this == ParamEqEditPhase.SENT ||
        this == ParamEqEditPhase.PENDING || this == ParamEqEditPhase.WRITING

private val ParamEqEditState.transportBaseline: List<GaiaPeqBand>?
    get() = lastSent ?: confirmed?.bands

private val ParamEqEditState.settledPhase: ParamEqEditPhase
    get() = if (lastSent != null) ParamEqEditPhase.SENT else ParamEqEditPhase.READY
