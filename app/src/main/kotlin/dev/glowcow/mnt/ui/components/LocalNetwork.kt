package dev.glowcow.mnt.ui.components

import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import dev.glowcow.mnt.R

private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

/**
 * Runs what reaches the home network once the app may: Android 17 drops the connections of an app without this
 * permission. Asked when a server or the speakers are opened; refused, the work still runs and a toast says why it fails.
 */
@Composable
fun rememberLocalNetwork(): (then: () -> Unit) -> Unit {
    val context = LocalContext.current
    val resources = LocalResources.current
    val pending = remember { arrayOfNulls<() -> Unit>(1) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(context, resources.getString(R.string.local_network_denied), Toast.LENGTH_LONG).show()
        pending[0]?.invoke()
        pending[0] = null
    }
    return remember(ask) {
        { then ->
            if (context.checkSelfPermission(LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED) {
                then()
            } else {
                pending[0] = then
                ask.launch(LOCAL_NETWORK)
            }
        }
    }
}
