package dev.glowcow.mnt.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glowcow.mnt.R
import dev.glowcow.mnt.audio.Playback
import dev.glowcow.mnt.container
import dev.glowcow.mnt.ui.components.solid
import dev.glowcow.mnt.ui.theme.MntIcons
import dev.glowcow.mnt.ui.theme.MntTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * How loud the speaker plays, after Symfonium's: over everything for a moment after the volume keys turned it, with
 * a slider to turn it further; a tap beside it puts it away.
 */
@Composable
fun VolumePanel(playback: Playback) {
    val c = MntTheme.colors
    val renderer = LocalContext.current.container.casting.target
    var shown by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    val first = remember { playback.volumeAsked }
    LaunchedEffect(playback.volumeAsked, held) {
        if (playback.volumeAsked == first) return@LaunchedEffect
        shown = true
        if (!held) {
            delay(SHOWN_MS)
            shown = false
        }
    }
    AnimatedVisibility(shown && renderer != null, enter = fadeIn() + scaleIn(initialScale = 0.94f), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(SCRIM)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { shown = false },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .padding(horizontal = 32.dp)
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .shadow(12.dp, PANEL, ambientColor = SHADE, spotColor = SHADE)
                    .clip(PANEL)
                    .background(c.group)
                    .solid()
                    .padding(horizontal = 24.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(renderer?.title.orEmpty(), color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.player_volume, playback.volume), color = c.muted, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(MntIcons.Volume, null, tint = c.text, modifier = Modifier.size(24.dp))
                    Slider(
                        value = playback.volume.toFloat(),
                        onValueChange = {
                            held = true
                            if (it.roundToInt() != playback.volume) playback.turnTo(it.roundToInt())
                        },
                        onValueChangeFinished = { held = false },
                        valueRange = 0f..playback.volumeMax.coerceAtLeast(1).toFloat(),
                        colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.accent.copy(alpha = 0.24f)),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private val PANEL = RoundedCornerShape(28.dp)
private val SCRIM = Color.Black.copy(alpha = 0.4f)
private val SHADE = Color.Black.copy(alpha = 0.35f)
private const val SHOWN_MS = 2500L
