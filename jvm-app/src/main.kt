package ink.lipoly.app.sunrise

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "SunRise") {
        DesktopApp(window)
    }
}