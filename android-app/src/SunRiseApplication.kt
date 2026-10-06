package ink.lipoly.app.sunrise

import android.app.Application
import ink.lipoly.app.sunrise.di.SunRiseRuntime
import ink.lipoly.app.sunrise.di.createAndroidSunRiseRuntime

class SunRiseApplication : Application() {
    lateinit var runtime: SunRiseRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = createAndroidSunRiseRuntime(applicationContext)
    }
}
