package dev.glowcow.mnt.files

enum class SortBy { NAME, DATE, SIZE, TYPE }

/** The order of a folder's rows: folders first, then by the chosen column, names breaking a tie. The bin is never among them. */
object Listing {
    fun arrange(nodes: List<Node>, by: SortBy, descending: Boolean, hidden: Boolean): List<Node> {
        val column: Comparator<Node> = when (by) {
            SortBy.NAME -> NAMES
            SortBy.DATE -> compareBy { it.modified }
            SortBy.SIZE -> compareBy { it.size }
            SortBy.TYPE -> compareBy { Paths.extension(it.name) }
        }
        val ordered = if (descending) column.reversed() else column
        return nodes.filter { (hidden || !it.hidden) && it.name != Trash.FOLDER }.sortedWith(compareByDescending<Node> { it.directory }.then(ordered).then(NAMES))
    }

    /** Names as a person reads them: case aside, and `file2` before `file10`. */
    val NAMES: Comparator<Node> = Comparator { a, b -> natural(a.name, b.name) }

    internal fun natural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                val x = a.digits(i)
                val y = b.digits(j)
                val byValue = x.trimStart('0').let { p -> y.trimStart('0').let { q -> compareValuesBy(p, q, { it.length }, { it }) } }
                if (byValue != 0) return byValue
                i += x.length
                j += y.length
            } else {
                val byLetter = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (byLetter != 0) return byLetter
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j).takeIf { it != 0 } ?: a.compareTo(b)
    }

    private fun String.digits(from: Int): String = substring(from).takeWhile { it.isDigit() }
}
