package dev.glowcow.mnt.ui.theme

import android.content.Context
import android.content.res.AssetManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * Material Icon Theme (MIT), the icons of VS Code's explorer: a file by its name or the longest ending it has,
 * a folder by its name, each in its own colours and never tinted. The shapes come from `assets/material-icons.json`.
 */
object FileIcons {
    private var assets: AssetManager? = null
    private var data: JSONObject? = null
    private val made = HashMap<String, ImageVector>()

    /** Reads the icons on a thread of their own, so the first list need not wait for them. */
    fun init(context: Context) {
        assets = context.applicationContext.assets
        thread(name = "file-icons") { load() }
    }

    @Synchronized
    private fun load(): JSONObject {
        data?.let { return it }
        val text = assets!!.open("material-icons.json").use { it.readBytes().decodeToString() }
        return JSONObject(text).also { data = it }
    }

    private fun lookup(table: String, key: String): String? = load().optJSONObject(table)?.optString(key)?.takeIf { it.isNotEmpty() }

    /** The icon of a file called [name]; [light] picks the variant drawn for a light ground where there is one. */
    fun file(name: String, light: Boolean): ImageVector {
        val lower = name.lowercase()
        (if (light) lookup("lightNames", lower) else null)?.let { return named(it) }
        lookup("names", lower)?.let { return named(it) }
        // `a.test.ts` is tried as `test.ts`, then as `ts`.
        var dot = lower.indexOf('.')
        while (dot >= 0) {
            val ending = lower.substring(dot + 1)
            if (ending.isNotEmpty()) {
                (if (light) lookup("lightExt", ending) else null)?.let { return named(it) }
                lookup("ext", ending)?.let { return named(it) }
            }
            dot = lower.indexOf('.', dot + 1)
        }
        return named("file")
    }

    fun folder(name: String, light: Boolean): ImageVector {
        val lower = name.lowercase()
        return named((if (light) lookup("lightFolders", lower) else null) ?: lookup("folders", lower) ?: "folder")
    }

    /** An icon by its name in the theme: `folder-download`, `pdf`. */
    @Synchronized
    fun named(icon: String): ImageVector = made.getOrPut(icon) {
        val spec = load().getJSONObject("icons").optJSONObject(icon) ?: load().getJSONObject("icons").getJSONObject("file")
        val box = spec.getJSONArray("v")
        val parts = spec.getJSONArray("p")
        ImageVector.Builder(icon, 24.dp, 24.dp, box.getDouble(0).toFloat(), box.getDouble(1).toFloat()).apply {
            for (i in 0 until parts.length()) {
                val part = parts.getJSONArray(i)
                addPath(
                    PathParser().parsePathString(part.getString(3)).toNodes(),
                    pathFillType = if (part.getInt(4) == 1) PathFillType.EvenOdd else PathFillType.NonZero,
                    fill = colour(part.opt(0))?.let { SolidColor(it) },
                    stroke = colour(part.opt(1))?.let { SolidColor(it) },
                    strokeLineWidth = part.getDouble(2).toFloat(),
                )
            }
        }.build()
    }

    // `RRGGBBAA`, or null for none.
    private fun colour(value: Any?): Color? {
        val hex = (value as? String) ?: return null
        val rgba = hex.toLong(16)
        return Color(((rgba and 0xFF) shl 24 or (rgba ushr 8)).toInt())
    }
}
