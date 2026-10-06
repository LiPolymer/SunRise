package ink.lipoly.app.sunrise.settings

import kotlinx.coroutines.flow.StateFlow

internal interface UiSettingsStore {
    val state: StateFlow<UiSettings>
    val current: UiSettings get() = state.value
    fun update(next: UiSettings)
}
