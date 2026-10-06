package ink.lipoly.app.sunrise.presentation

internal data class PresentationNotice(
    val chinese: String,
    val english: String,
    val error: Exception? = null,
)
