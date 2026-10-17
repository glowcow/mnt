package dev.glowcow.mnt.files

import dev.glowcow.mnt.view.Format

/** A file or a folder as a listing shows it. */
data class Node(
    val path: String,
    val name: String,
    val directory: Boolean,
    /** Bytes; 0 for a folder. */
    val size: Long,
    /** Milliseconds since the epoch. */
    val modified: Long,
) {
    val hidden: Boolean get() = name.startsWith('.')
    val kind: Kind get() = if (directory) Kind.FOLDER else Kind.of(name)
}

/** What a store tells of a file beyond its listing, each null when it does not know; times in milliseconds since the epoch. */
data class Facts(
    val created: Long? = null,
    /** The nine permission bits, owner first. */
    val mode: Int? = null,
    val owner: String? = null,
    val group: String? = null,
)

/** How many files and how many folders a folder holds directly. */
data class Holds(val files: Int, val folders: Int)

/** What a file is, told by the end of its name. */
enum class Kind {
    FOLDER, IMAGE, VIDEO, AUDIO, TEXT, ARCHIVE, PACKAGE, OTHER;

    companion object {
        private val byExtension: Map<String, Kind> = buildMap {
            fun put(kind: Kind, vararg extensions: String) = extensions.forEach { put(it, kind) }
            put(IMAGE, "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "bmp", "svg", "tif", "tiff", "dng", "raw", "ico", "arw", "cr2", "cr3", "nef", "raf", "orf", "rw2", "pef", "srw")
            put(VIDEO, "mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "ts", "mpg", "mpeg", "wmv", "flv")
            put(AUDIO, "mp3", "flac", "ogg", "opus", "m4a", "aac", "wav", "wma", "aiff", "ape", "mid", "amr")
            put(TEXT, "txt", "md", "pdf", "doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp", "epub", "fb2", "json", "xml", "html", "htm", "log", "ini", "yaml", "yml")
            put(ARCHIVE, "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "tbz", "tbz2", "xz", "txz", "zst", "tzst", "lz", "tlz", "lzma", "z", "iso", "jar", "cab", "cpio", "lzh", "lha")
            put(PACKAGE, "apk", "apks", "xapk", "apkm")
        }

        // What the app's viewer shows as text or as a document is drawn as one.
        fun of(name: String): Kind = byExtension[Paths.extension(name)] ?: Format.of(name)?.takeIf { it != Format.UNKNOWN }?.let { TEXT } ?: OTHER
    }
}
