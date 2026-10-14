package dev.glowcow.mnt.view

import dev.glowcow.mnt.files.Paths
import dev.glowcow.mnt.view.code.Languages

/** How the app shows a file of its own: as text, as a document of some kind, or as pages. */
enum class Format {
    TEXT, MARKDOWN, TABLE, HTML, PDF, DJVU, DOCX, XLSX, PPTX, ODT, ODS, ODP, RTF, DOC, XLS, PPT, EPUB, LINK,

    /** A name that says nothing: shown as text when the file turns out to be text. */
    UNKNOWN;

    companion object {
        private val byExtension: Map<String, Format> = buildMap {
            fun put(format: Format, vararg extensions: String) = extensions.forEach { put(it, format) }
            put(MARKDOWN, "md", "markdown", "mdown", "mkd")
            put(TABLE, "csv", "tsv")
            put(HTML, "html", "htm", "xhtml")
            put(PDF, "pdf")
            put(DJVU, "djvu", "djv")
            put(DOCX, "docx", "docm", "dotx", "dotm")
            put(XLSX, "xlsx", "xlsm", "xltx", "xltm")
            put(PPTX, "pptx", "pptm", "ppsx", "potx")
            put(ODT, "odt", "ott")
            put(ODS, "ods", "ots")
            put(ODP, "odp", "otp")
            put(RTF, "rtf")
            put(DOC, "doc", "dot")
            put(XLS, "xls", "xlt")
            put(PPT, "ppt", "pps", "pot")
            put(EPUB, "epub")
            put(LINK, "url", "webloc")
        }

        // Kinds of file that are someone else's to show: pictures, sound, video, archives, programs.
        private val foreign = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "bmp", "tif", "tiff", "dng", "raw", "ico", "arw", "cr2", "cr3", "nef", "raf", "orf", "rw2", "pef", "srw",
            "mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "mpg", "mpeg", "wmv", "flv",
            "mp3", "flac", "ogg", "opus", "m4a", "aac", "wav", "wma", "aiff", "ape", "mid", "amr",
            "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "iso", "jar", "apk", "apks", "xapk", "aab",
            "exe", "dll", "so", "bin", "img", "dmg", "deb", "rpm", "msi", "class", "dex", "o", "a", "db", "sqlite", "ttf", "otf", "woff", "woff2",
        )

        /** How a file called [name] is shown in the app; null when another app shows it. */
        fun of(name: String): Format? {
            val extension = Paths.extension(name)
            byExtension[extension]?.let { return it }
            if (Languages.of(name) != null) return TEXT
            return if (extension in foreign) null else UNKNOWN
        }
    }
}
