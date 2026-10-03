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
import ink.lipoly.app.sunrise.blueConnector.BtPermissions
import ink.lipoly.app.sunrise.blueConnector.createBtManager
import ink.lipoly.app.sunrise.headset.createHeadsetClient

class MainActivity : ComponentActivity() {
    private val missingPermissions = mutableStateOf(emptySet<String>())
    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        missingPermissions.value = BtPermissions.missing(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        missingPermissions.value = BtPermissions.missing(this)
        setContent {
            val bt = remember { createBtManager(applicationContext) }
            val client = remember(bt) { createHeadsetClient(applicationContext, bt) }
            DisposableEffect(client, bt) {
                onDispose {
                    client.close()
                    bt.close()
                }
            }
            App(
                client = client,
                missingPermissions = missingPermissions.value,
                onRequestPermissions = {
                    val missing = BtPermissions.missing(this)
                    if (missing.isNotEmpty()) permissionRequest.launch(missing.toTypedArray())
                    else missingPermissions.value = emptySet()
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        missingPermissions.value = BtPermissions.missing(this)
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
