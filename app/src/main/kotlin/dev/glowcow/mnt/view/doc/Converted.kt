package dev.glowcow.mnt.view.doc

import dev.glowcow.mnt.view.html.Page
import dev.glowcow.mnt.view.html.Paper
import dev.glowcow.mnt.view.html.Running
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

/** A document turned into HTML: one page or several (the sheets of a workbook), and the pictures they show from `/a/<name>`. */
class Converted(val pages: List<Part>, private val asset: (String) -> ByteArray? = { null }, private val closing: () -> Unit = {}) : Closeable {
    /** A page: its name among the others and its body; a text document has the [paper] it is printed on, and maybe a header and a footer on each sheet. */
    class Part(val name: String, val body: String, val paper: Paper? = null, val running: Running? = null)

    fun asset(name: String): ByteArray? = asset.invoke(name)

    override fun close() = closing()
}

/** A ZIP container of a document: its parts by name, read whole. */
class Package(file: File) : Closeable {
    private val zip = ZipFile(file)

    fun has(name: String): Boolean = zip.getEntry(name) != null

    fun bytes(name: String): ByteArray? = zip.getEntry(name)?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } }

    fun xml(name: String): El? = zip.getEntry(name)?.let { entry -> zip.getInputStream(entry).use { Xml.parse(it) } }

    fun stream(name: String) = zip.getEntry(name)?.let { zip.getInputStream(it) }

    val names: List<String> get() = zip.entries().asSequence().map { it.name }.toList()

    override fun close() = zip.close()
}

/** Text written into HTML. */
internal fun esc(text: String): String = Page.escape(text)

/** A path inside a package, from [base]'s folder: `word/` and `media/a.png` give `word/media/a.png`; `..` steps up. */
internal fun resolve(base: String, target: String): String {
    if (target.startsWith("/")) return target.trimStart('/')
    val parts = base.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.toMutableList()
    for (step in target.split('/')) {
        when (step) {
            "", "." -> Unit
            ".." -> parts.removeLastOrNull()
            else -> parts += step
        }
    }
    return parts.joinToString("/")
}

/** The relationships of a part: id to target, and whether it is outside the package. */
internal class Rels(pkg: Package, part: String) {
    class Rel(val target: String, val external: Boolean, val type: String)

    private val byId: Map<String, Rel>

    init {
        val name = part.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } + "_rels/" + part.substringAfterLast('/') + ".rels"
        byId = pkg.xml(name)?.children("Relationship")?.associate {
            val external = it.attr("TargetMode") == "External"
            val target = it.attr("Target").orEmpty()
            it.attr("Id").orEmpty() to Rel(if (external) target else resolve(part, target), external, it.attr("Type").orEmpty().substringAfterLast('/'))
        }.orEmpty()
    }

    operator fun get(id: String?): Rel? = id?.let { byId[it] }

    fun ofType(type: String): List<Rel> = byId.values.filter { it.type == type }
}

/** Whether a picture of this name can be shown in a page: what a browser draws, not EMF or WMF. */
internal fun drawable(name: String): Boolean = name.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg", "avif")
