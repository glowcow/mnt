package dev.glowcow.mnt.backup

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BackupSettings(
    val theme: String,
    val palette: String,
    val appUpdate: Boolean = false,
    val showHidden: Boolean = false,
    val sortBy: String = "NAME",
    val sortDescending: Boolean = false,
    val pinned: List<String> = emptyList(),
    val hiddenPlaces: List<String> = emptyList(),
    val searches: List<String> = emptyList(),
    val binExternal: Boolean = false,
    val codeLight: String? = null,
    val codeDark: String? = null,
    val wrapLines: Boolean = true,
    val lineNumbers: Boolean = true,
    val linksInApp: Boolean = true,
    val pages: Boolean = true,
)

/** `backup.json` of an archive. */
@Serializable
data class BackupManifest(val version: Int = 1, val createdAt: Long, val app: String, val settings: BackupSettings)

/** A backup is a zip that holds `backup.json`: the settings, the pinned folders and the search history. */
object BackupArchive {
    const val MANIFEST = "backup.json"

    private val json = Json { ignoreUnknownKeys = true }

    private const val MAX_MANIFEST = 4L * 1024 * 1024
    private const val MAX_ENTRIES = 1_000

    /** Writes the archive to [out]; the caller closes the stream. */
    fun write(out: OutputStream, manifest: BackupManifest) {
        val zip = ZipOutputStream(out)
        zip.putNextEntry(ZipEntry(MANIFEST))
        zip.write(json.encodeToString(manifest).toByteArray())
        zip.closeEntry()
        zip.finish()
    }

    /** Reads the manifest of a backup; whatever else the archive holds is passed over. */
    fun read(input: InputStream): BackupManifest {
        val zip = ZipInputStream(input)
        var entries = 0
        while (true) {
            val entry = zip.nextEntry ?: break
            if (++entries > MAX_ENTRIES) throw IOException("too many entries")
            if (entry.name != MANIFEST) continue
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = zip.read(buffer)
                if (n < 0) break
                if (bytes.size() + n > MAX_MANIFEST) throw IOException("the backup is too large")
                bytes.write(buffer, 0, n)
            }
            return json.decodeFromString<BackupManifest>(bytes.toString(Charsets.UTF_8.name()))
        }
        throw IOException("no $MANIFEST in the archive")
    }
}
