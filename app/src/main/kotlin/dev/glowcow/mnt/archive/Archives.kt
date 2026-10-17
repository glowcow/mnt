package dev.glowcow.mnt.archive

import dev.glowcow.mnt.files.FileStore
import dev.glowcow.mnt.files.Kind
import dev.glowcow.mnt.files.Paths
import dev.glowcow.mnt.files.Remote
import java.io.File
import java.util.Base64

/**
 * Archives opened as folders. Each is the place `//@<its path>` — the path written in URL-safe Base64 — so an
 * archive inside an archive is one more place, and a route to a folder in one outlives the app's process.
 * [files] reads the archives themselves, wherever they lie; a decoder may take [spare] bytes of memory at most;
 * what an archive holds is written down in [cache] when it took long to learn.
 */
class Archives(private val files: () -> FileStore, private val spare: () -> Long, private val cache: File) {
    // The archives last opened, each with what it holds.
    private val stores = object : LinkedHashMap<String, ArchiveStore>(KEEP, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArchiveStore>): Boolean = size > KEEP
    }

    /** The store of the place [id]; null when [id] is not an archive's. */
    @Synchronized
    fun store(id: String): ArchiveStore? {
        val archive = archive(id) ?: return null
        return stores.getOrPut(archive) { ArchiveStore(archive, files(), cache, open = ::walk) }
    }

    private fun walk(archive: String, size: Long, raw: Boolean, heard: ((Float) -> Unit)?): Walk {
        ArchiveReader.limitMemory(spare())
        val source = if (Remote.id(archive) == null) FileSource(File(archive)) else StoreSource(files(), archive, size)
        source.heard = heard
        return ArchiveReader(source, raw)
    }

    companion object {
        private const val MARK = '@'
        private const val KEEP = 8

        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()

        /** Whether the place [id] is an archive. */
        fun owns(id: String): Boolean = id.startsWith(MARK)

        /** The folder the archive at [archive] opens as. */
        fun root(archive: String): String = Remote.root(MARK + encoder.encodeToString(archive.toByteArray()))

        /** The archive the place [id] is, or null for any other place. */
        fun archive(id: String): String? =
            if (owns(id)) runCatching { String(decoder.decode(id.substring(1))) }.getOrNull() else null

        /** The archive [path] lies in, or null for a path in no archive. */
        fun of(path: String): String? = Remote.id(path)?.let(::archive)

        /** Whether a file called [name] opens as a folder. */
        fun opens(name: String): Boolean = Kind.of(name) == Kind.ARCHIVE

        /** Whether a file called [name] may be one file compressed, with nothing packed. */
        fun raw(name: String): Boolean = Paths.extension(name) in COMPRESSED

        /** Whether what a file called [name] holds is learnt only by reading all of it: a compressed stream. */
        fun streamed(name: String): Boolean = Paths.extension(name) in COMPRESSED || Paths.extension(name) in TARS

        /** About how many bytes of a stream called [name] are read a second on a phone. */
        fun rate(name: String): Long = when (Paths.extension(name)) {
            "zst", "tzst" -> 300L shl 20
            "xz", "txz", "lz", "tlz", "lzma" -> 60L shl 20
            "bz2", "tbz", "tbz2" -> 25L shl 20
            else -> 150L shl 20
        }

        /** [name] without what marks it an archive: `a.tar.gz` and `a.zip` are both `a`. */
        fun stem(name: String): String {
            val extension = Paths.extension(name)
            if (extension.isEmpty()) return name
            var stem = name.dropLast(extension.length + 1)
            if (extension in COMPRESSED && Paths.extension(stem) == "tar") stem = stem.dropLast(TAR.length)
            return stem.ifEmpty { name }
        }

        private val COMPRESSED = setOf("gz", "bz2", "xz", "zst", "lz", "lzma", "z")
        private val TARS = setOf("tgz", "tbz", "tbz2", "txz", "tzst", "tlz")
        private const val TAR = ".tar"
    }
}
