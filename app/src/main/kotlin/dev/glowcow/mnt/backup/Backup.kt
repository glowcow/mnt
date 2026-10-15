package dev.glowcow.mnt.backup

import android.content.Context
import android.net.Uri
import dev.glowcow.mnt.data.AppSettings
import dev.glowcow.mnt.data.Palette
import dev.glowcow.mnt.data.SettingsStore
import dev.glowcow.mnt.data.ThemeMode
import dev.glowcow.mnt.files.Place
import dev.glowcow.mnt.files.SortBy
import dev.glowcow.mnt.view.code.CodeScheme
import java.io.BufferedInputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

enum class RestoreOutcome { RESTORED, WRONG_PASSWORD, INVALID }

/** Saves the settings, the pinned folders and the search history to a file the user picks, and reads them back. */
class Backup(private val context: Context, private val settings: SettingsStore) {

    /** Writes a backup to [uri], encrypted when [password] is given. */
    suspend fun save(uri: Uri, password: String?): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.settings.first()
            val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("no stream")
            (if (password.isNullOrEmpty()) out else BackupCrypto.encrypt(out, password.toCharArray())).use {
                BackupArchive.write(
                    it,
                    BackupManifest(
                        createdAt = System.currentTimeMillis(),
                        app = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty(),
                        settings = BackupSettings(
                            s.theme.name, s.palette.name, s.appUpdate, s.showHidden, s.sortBy.name, s.sortDescending,
                            s.pinned, s.hiddenPlaces.toList(), s.searches, s.binExternal,
                            s.codeLight.name, s.codeDark.name, s.wrapLines, s.lineNumbers, s.linksInApp, s.pages,
                        ),
                    ),
                )
            }
        }.isSuccess
    }

    /** Whether the file at [uri] asks for a password; null when it cannot be read. */
    suspend fun isEncrypted(uri: Uri): Boolean? = withContext(Dispatchers.IO) {
        runCatching { open(uri).use { BackupCrypto.isEncrypted(it.readNBytes(BackupCrypto.MAGIC.size)) } }.getOrNull()
    }

    /**
     * Adds the pinned folders and the searches of the backup at [uri] to the ones here. With
     * [overwrite] everything here is replaced by what the backup holds, the settings too.
     */
    suspend fun restore(uri: Uri, password: String?, overwrite: Boolean): RestoreOutcome = withContext(Dispatchers.IO) {
        try {
            val manifest = open(uri).use { raw ->
                val input = BufferedInputStream(raw)
                input.mark(BackupCrypto.MAGIC.size)
                val encrypted = BackupCrypto.isEncrypted(input.readNBytes(BackupCrypto.MAGIC.size))
                input.reset()
                if (encrypted && password.isNullOrEmpty()) return@withContext RestoreOutcome.WRONG_PASSWORD
                BackupArchive.read(if (encrypted) BackupCrypto.decrypt(input, password!!.toCharArray()) else input)
            }
            val kept = kept(manifest.settings)
            if (overwrite) settings.restore(kept) else settings.add(kept.pinned, kept.searches)
            RestoreOutcome.RESTORED
        } catch (e: WrongPasswordException) {
            RestoreOutcome.WRONG_PASSWORD
        } catch (e: Exception) {
            RestoreOutcome.INVALID
        }
    }

    private fun open(uri: Uri): InputStream = context.contentResolver.openInputStream(uri) ?: error("no stream")

    private companion object {
        const val MAX_LIST = 500
        const val MAX_TEXT = 4_096

        /** What a backup holds as settings of this app: names it does not know give way to the defaults, lists are cut to a sane length. */
        fun kept(b: BackupSettings): AppSettings {
            val d = AppSettings()
            fun List<String>.sane() = filter { it.isNotBlank() && it.length <= MAX_TEXT }.distinct().take(MAX_LIST)
            return AppSettings(
                theme = runCatching { ThemeMode.valueOf(b.theme) }.getOrDefault(d.theme),
                palette = runCatching { Palette.valueOf(b.palette) }.getOrDefault(d.palette),
                appUpdate = b.appUpdate,
                showHidden = b.showHidden,
                binExternal = b.binExternal,
                sortBy = runCatching { SortBy.valueOf(b.sortBy) }.getOrDefault(d.sortBy),
                sortDescending = b.sortDescending,
                pinned = b.pinned.sane().filter { it.startsWith('/') },
                hiddenPlaces = b.hiddenPlaces.filter { name -> Place.entries.any { it.name == name } }.toSet(),
                searches = b.searches.sane(),
                codeLight = b.codeLight?.let { runCatching { CodeScheme.valueOf(it) }.getOrNull() } ?: d.codeLight,
                codeDark = b.codeDark?.let { runCatching { CodeScheme.valueOf(it) }.getOrNull() } ?: d.codeDark,
                wrapLines = b.wrapLines,
                lineNumbers = b.lineNumbers,
                linksInApp = b.linksInApp,
                pages = b.pages,
            )
        }
    }
}
