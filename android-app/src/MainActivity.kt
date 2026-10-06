package ink.lipoly.app.sunrise

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import ink.lipoly.app.sunrise.blueConnector.BtPermissions

class MainActivity : ComponentActivity() {
    private val missingPermissions = mutableStateOf(emptySet<String>())
    private val runtime get() = (application as SunRiseApplication).runtime
    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        refreshPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        refreshPermissions()
        setContent {
            App(
                runtime = runtime,
                missingPermissions = missingPermissions.value,
                onRequestPermissions = {
                    val missing = BtPermissions.missing(this)
                    if (missing.isNotEmpty()) permissionRequest.launch(missing.toTypedArray())
                    else refreshPermissions()
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    private fun refreshPermissions() {
        val missing = BtPermissions.missing(this)
        missingPermissions.value = missing
        runtime.updatePermissions(missing)
    }
}
