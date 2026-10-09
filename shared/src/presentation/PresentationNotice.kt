package ink.lipoly.app.sunrise.presentation

internal data class PresentationNotice(
    val message: String,
    val error: Exception? = null,
)
