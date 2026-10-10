package dev.glowcow.mnt.ui.components

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.glowcow.mnt.R
import dev.glowcow.mnt.ui.theme.MntTheme

/** Shows [content] once the app may work on all files, and the way to allow it until then. */
@Composable
fun StorageGate(top: Dp, content: @Composable () -> Unit) {
    val c = MntTheme.colors
    val context = LocalContext.current
    var granted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    // The permission is a switch on a system page, so it is read again each time the app comes back.
    LifecycleResumeEffect(Unit) {
        granted = Environment.isExternalStorageManager()
        onPauseOrDispose {}
    }

    if (granted) {
        content()
        return
    }
    Column(Modifier.padding(horizontal = 16.dp).padding(top = top), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.access_title), color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.access_text), color = c.muted, fontSize = 14.sp)
        }
        Group {
            GroupRow(stringResource(R.string.access_allow), onClick = {
                context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", context.packageName, null)))
            })
        }
    }
}
