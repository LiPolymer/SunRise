package ink.lipoly.app.sunrise

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import ink.lipoly.app.sunrise.compose.DesktopApp

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "SunRise") {
        DesktopApp()
    }
}