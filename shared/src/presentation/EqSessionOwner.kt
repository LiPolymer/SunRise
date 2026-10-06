package ink.lipoly.app.sunrise.presentation

import ink.lipoly.app.sunrise.controls.peq.ParamEqEditor
import ink.lipoly.app.sunrise.drop.GaiaControls
import kotlinx.coroutines.CoroutineScope
import kotlin.time.TimeSource

/** Owns the editor for exactly one GAIA controls identity at a time. */
internal class EqSessionOwner(
    private val scope: CoroutineScope,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val guard = Any()
    private var controls: GaiaControls? = null
    private var editor: ParamEqEditor? = null
    private var closed = false

    fun bind(controls: GaiaControls?): ParamEqEditor? = synchronized(guard) {
        if (closed) return@synchronized null
        if (this.controls === controls) return@synchronized editor

        editor?.close()
        editor = null
        this.controls = null
        if (controls == null) return@synchronized null

        ParamEqEditor(scope, controls, timeSource).also {
            this.controls = controls
            editor = it
        }
    }

    fun close() = synchronized(guard) {
        if (closed) return@synchronized
        closed = true
        editor?.close()
        editor = null
        controls = null
    }
}
