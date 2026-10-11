package dev.glowcow.mnt.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/** Hands files over to other apps: to show one, or to send some. */
object Opener {
    /** Opens the file in the app that shows its type; false when no app does. */
    fun open(context: Context, path: String): Boolean = launch(
        context,
        Intent(Intent.ACTION_VIEW).setDataAndType(uri(context, path), type(path)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
    )

    fun share(context: Context, paths: List<String>): Boolean {
        val uris = ArrayList(paths.map { uri(context, it) })
        val types = paths.map { type(it) }.distinct()
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.setType(types.singleOrNull() ?: ANY).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return launch(context, Intent.createChooser(send, null))
    }

    private fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    private fun uri(context: Context, path: String): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))

    /** The type a file called [name] is taken for; null when its ending says nothing. */
    fun mime(name: String): String? = MimeTypeMap.getSingleton().getMimeTypeFromExtension(Paths.extension(name))

    private fun type(path: String): String = mime(Paths.name(path)) ?: ANY

    private const val ANY = "*/*"
}
