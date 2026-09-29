package ink.lipoly.app.sunrise

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import ink.lipoly.app.sunrise.compose.App
import ink.lipoly.app.sunrise.drop.DropPermissions
import ink.lipoly.app.sunrise.drop.createDropClient

class MainActivity : ComponentActivity() {
    private val missingPermissions = mutableStateOf(emptySet<String>())
    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        missingPermissions.value = DropPermissions.missing(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        missingPermissions.value = DropPermissions.missing(this)
        setContent {
            val client = remember { createDropClient(applicationContext) }
            DisposableEffect(client) {
                onDispose { client.close() }
            }
            App(
                client = client,
                missingPermissions = missingPermissions.value,
                onRequestPermissions = {
                    val missing = DropPermissions.missing(this)
                    if (missing.isNotEmpty()) permissionRequest.launch(missing.toTypedArray())
                    else missingPermissions.value = emptySet()
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        missingPermissions.value = DropPermissions.missing(this)
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
