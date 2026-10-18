package dev.glowcow.mnt.ui.photo

import android.content.Intent
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.glowcow.mnt.R
import dev.glowcow.mnt.container
import dev.glowcow.mnt.files.Node
import dev.glowcow.mnt.ui.components.Group
import dev.glowcow.mnt.ui.components.GroupDivider
import dev.glowcow.mnt.ui.components.GroupRow
import dev.glowcow.mnt.ui.components.GroupSheet
import dev.glowcow.mnt.ui.theme.MntIcons
import dev.glowcow.mnt.ui.theme.MntTheme
import dev.glowcow.mnt.view.image.Hdr
import dev.glowcow.mnt.view.image.PictureFacts
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

/** What a picture tells of itself: its size and kind, how and where it was taken, the file it is. */
@Composable
fun DetailsSheet(vm: PhotoViewModel, node: Node, onTags: () -> Unit, onDismiss: () -> Unit) {
    val facts = rememberFacts(vm, node)
    val settings by vm.settings.collectAsStateWithLifecycle()
    GroupSheet(node.name, onDismiss) { pick ->
        val f = facts ?: return@GroupSheet Text(stringResource(R.string.details_counting), color = MntTheme.colors.muted, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        val context = LocalContext.current
        val locale = LocalConfiguration.current.locales[0]
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Rows(
                listOfNotNull(
                    (f.width > 0).takeIf { it }?.let { stringResource(R.string.photo_dimensions) to "${f.width} × ${f.height} · ${megapixels(f.width, f.height, locale)}" },
                    stringResource(R.string.photo_format) to listOfNotNull(f.format, f.colour).joinToString(" · "),
                    f.hdr?.let { stringResource(R.string.photo_hdr) to hdr(it, locale) },
                ),
            )
            Rows(
                listOfNotNull(
                    f.taken?.let { stringResource(R.string.photo_taken) to it.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)) },
                    f.camera?.let { stringResource(R.string.photo_camera) to it },
                    f.lens?.let { stringResource(R.string.photo_lens) to it },
                    exposure(f, locale)?.let { stringResource(R.string.photo_exposure) to it },
                    f.focal?.let { mm ->
                        val focal = "${number(mm, locale)} mm"
                        stringResource(R.string.photo_focal) to (f.focal35?.let { stringResource(R.string.photo_focal_35, focal, "$it mm") } ?: focal)
                    },
                    f.bias?.let { stringResource(R.string.photo_bias) to "${if (it > 0) "+" else "−"}${number(kotlin.math.abs(it), locale)} EV" },
                    f.flash?.let { stringResource(R.string.photo_flash) to stringResource(if (it) R.string.photo_flash_fired else R.string.photo_flash_off) },
                    f.autoWhite?.let { stringResource(R.string.photo_white) to stringResource(if (it) R.string.photo_white_auto else R.string.photo_white_manual) },
                ),
            )
            val lat = f.latitude
            val lon = f.longitude
            if (lat != null && lon != null) {
                val place = String.format(Locale.ROOT, "%.5f, %.5f", lat, lon)
                // The map app the phone has takes the spot.
                val toMaps = {
                    pick {
                        val geo = Intent(Intent.ACTION_VIEW, "geo:$place?q=$place".replace(" ", "").toUri())
                        if (runCatching { context.startActivity(geo) }.isFailure) Toast.makeText(context, R.string.open_none, Toast.LENGTH_SHORT).show()
                    }
                }
                if (settings?.photoMap == true) PhotoMap(lat, lon, toMaps)
                Group {
                    GroupRow(stringResource(R.string.photo_coordinates), value = place, icon = MntIcons.MapPin, onClick = toMaps, trailing = {})
                    f.altitude?.let {
                        GroupDivider()
                        GroupRow(stringResource(R.string.photo_altitude), value = "${it.roundToInt()} m")
                    }
                }
            }
            Rows(
                listOfNotNull(
                    stringResource(R.string.photo_file_size) to Formatter.formatFileSize(context, f.node.size),
                    (f.node.modified > 0).takeIf { it }?.let {
                        stringResource(R.string.details_modified) to DateUtils.formatDateTime(context, f.node.modified, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
                    },
                    stringResource(R.string.details_location) to context.container.roots.display(dev.glowcow.mnt.files.Paths.parent(f.node.path)),
                    f.software?.let { stringResource(R.string.photo_software) to it },
                    f.author?.let { stringResource(R.string.photo_author) to it },
                    f.copyright?.let { stringResource(R.string.photo_copyright) to it },
                ),
            )
            if (f.tags.isNotEmpty()) {
                Group {
                    GroupRow(stringResource(R.string.photo_tags), value = f.tags.size.toString(), onClick = { pick(onTags) })
                }
            }
        }
    }
}

/** Every EXIF tag of the picture, by its name. */
@Composable
fun TagsSheet(vm: PhotoViewModel, node: Node, onDismiss: () -> Unit) {
    val facts = rememberFacts(vm, node)
    GroupSheet(stringResource(R.string.photo_tags), onDismiss) {
        val tags = facts?.tags ?: return@GroupSheet
        Group {
            tags.forEachIndexed { i, (name, value) ->
                if (i > 0) GroupDivider()
                GroupRow(name, subtitle = value)
            }
        }
    }
}

@Composable
private fun rememberFacts(vm: PhotoViewModel, node: Node): PictureFacts? {
    var facts by remember(node.path) { mutableStateOf<PictureFacts?>(null) }
    LaunchedEffect(node.path) { facts = vm.facts(node) }
    return facts
}

@Composable
private fun Rows(rows: List<Pair<String, String>>) {
    if (rows.isEmpty()) return
    Group {
        rows.forEachIndexed { i, (title, value) ->
            if (i > 0) GroupDivider()
            GroupRow(title, value = value)
        }
    }
}

@Composable
private fun hdr(hdr: Hdr, locale: Locale): String = when (hdr) {
    is Hdr.Gained -> stringResource(R.string.photo_hdr_gain, number(hdr.ratio.toDouble(), locale))
    Hdr.Pq -> "PQ"
    Hdr.Hlg -> "HLG"
}

private fun megapixels(w: Int, h: Int, locale: Locale): String = "${number(w.toDouble() * h / 1_000_000, locale)} MP"

// "f/1.8 · 1/120 s · ISO 100", from what the picture says of them.
private fun exposure(f: PictureFacts, locale: Locale): String? = listOfNotNull(
    f.aperture?.let { "f/${number(it, locale)}" },
    f.exposure?.let { if (it < 1) "1/${(1 / it).roundToInt()} s" else "${number(it, locale)} s" },
    f.iso?.let { "ISO $it" },
).joinToString(" · ").ifEmpty { null }

// One decimal at most, none when it is whole.
private fun number(value: Double, locale: Locale): String {
    val rounded = (value * 10).roundToInt() / 10.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else String.format(locale, "%.1f", rounded)
}
