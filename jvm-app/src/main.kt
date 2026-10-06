package ink.lipoly.app.sunrise

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import ink.lipoly.app.sunrise.di.createDesktopSunRiseRuntime
import kotlinx.coroutines.runBlocking

fun main() {
    val runtime = createDesktopSunRiseRuntime()
    val shutdownHook = Thread({ runBlocking { runtime.close() } }, "sunrise-runtime-shutdown")
    var hookRegistered = false
    try {
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        hookRegistered = true
        application(exitProcessOnExit = false) {
            Window(onCloseRequest = ::exitApplication, title = "SunRise") {
                DesktopApp(window, runtime)
            }
        }
    } finally {
        try {
            runBlocking { runtime.close() }
        } finally {
            try {
                if (hookRegistered) Runtime.getRuntime().removeShutdownHook(shutdownHook)
            } catch (failure: IllegalStateException) {
                if (failure.message != "Shutdown in progress") throw failure
            }
        }
    }
}
