package dev.glowcow.mnt.files

/** Arithmetic on absolute paths written with forward slashes. */
object Paths {
    fun name(path: String): String = path.trimEnd('/').substringAfterLast('/')

    /** The folder [path] lies in; the root is its own parent. */
    fun parent(path: String): String = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }

    fun child(folder: String, name: String): String = if (folder.endsWith('/')) folder + name else "$folder/$name"

    /** True when [path] is [folder] itself or lies anywhere inside it. */
    fun within(path: String, folder: String): Boolean = path == folder || path.startsWith(folder.trimEnd('/') + "/")

    /** What follows the last dot, in lower case; none for a name that only starts with a dot. */
    fun extension(name: String): String = name.substringAfterLast('.', "").takeIf { name.lastIndexOf('.') > 0 }.orEmpty().lowercase()

    /** A name a file system takes: not empty, not a step up or down, and no slash or zero in it. */
    fun valid(name: String): Boolean = name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\u0000' }

    /** [name], or `name (2).ext`, `name (3).ext`… — the first that is not [taken]. */
    fun free(name: String, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val tail = name.substring(dot)
        var n = 2
        while (taken("$stem ($n)$tail")) n++
        return "$stem ($n)$tail"
    }
}
