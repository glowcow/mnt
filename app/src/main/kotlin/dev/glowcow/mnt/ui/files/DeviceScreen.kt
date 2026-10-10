package dev.glowcow.mnt.ui.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.glowcow.mnt.container
import dev.glowcow.mnt.ui.components.DetailScreen
import dev.glowcow.mnt.ui.components.Group
import dev.glowcow.mnt.ui.components.GroupDivider
import dev.glowcow.mnt.ui.components.GroupRow
import dev.glowcow.mnt.ui.components.LocalOrigins
import dev.glowcow.mnt.ui.components.deviceKey

/** The volumes of a device that has several: a tap on one opens its root. */
@Composable
fun DeviceScreen(id: String, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val volumes = LocalContext.current.container.volumes
    val devices by volumes.devices.collectAsStateWithLifecycle()
    val device = devices.firstOrNull { it.id == id }
    LifecycleResumeEffect(Unit) {
        volumes.refresh()
        onPauseOrDispose {}
    }
    // Pulled out while its page is open, the device takes the page with it.
    LaunchedEffect(device == null) { if (device == null) onBack() }
    if (device == null) return

    val origins = LocalOrigins.current
    val from = remember { origins.take(deviceKey(id)) }
    DetailScreen(device.title(), onBack, from = from) {
        Group {
            device.volumes.forEachIndexed { i, volume ->
                if (i > 0) GroupDivider()
                GroupRow(
                    volume.label,
                    subtitle = spaceText(volume.free, volume.total),
                    icon = device.kind.icon,
                    iconTint = Color.Unspecified,
                    iconSize = PLACE_ICON,
                    onClick = { onOpen(volume.path) },
                    below = { UsageBar(volume.free, volume.total) },
                )
            }
        }
    }
}
