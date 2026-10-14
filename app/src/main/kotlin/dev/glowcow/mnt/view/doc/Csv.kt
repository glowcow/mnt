package dev.glowcow.mnt.view.doc

/** Comma-, semicolon- or tab-separated values as a table, its first row heading the columns. */
object Csv {
    /** The separator the first lines of [text] use most evenly; a tab for a `.tsv`. */
    fun separator(text: String, tsv: Boolean): Char {
        if (tsv) return '\t'
        val lines = text.lineSequence().filter { it.isNotBlank() }.take(20).toList()
        return listOf(',', ';', '\t', '|').maxByOrNull { sep ->
            val counts = lines.map { line -> line.count { it == sep } }
            // The same count on every line is a stronger sign than many on one.
            if (counts.isEmpty() || counts.first() == 0) -1 else counts.count { it == counts.first() } * 100 + counts.first()
        } ?: ','
    }

    fun parse(text: String, separator: Char): Sheet {
        val sheet = Sheet("")
        var row = 0
        var column = 0
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        fun end() {
            sheet.put(row, column, cell.toString(), number = cell.isNotEmpty() && cell.toString().trim().toDoubleOrNull() != null)
            cell.setLength(0)
            column++
        }
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    cell.append('"')
                    i++
                }
                c == '"' && (quoted || cell.isEmpty()) -> quoted = !quoted
                quoted -> cell.append(c)
                c == separator -> end()
                c == '\n' -> {
                    end()
                    row++
                    column = 0
                }
                c == '\r' -> Unit
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || column > 0) end()
        return sheet
    }

    fun html(text: String, tsv: Boolean): String = Sheets.html(parse(text, separator(text, tsv)), header = true)
}
