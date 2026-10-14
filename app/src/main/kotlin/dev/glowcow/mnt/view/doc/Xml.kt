package dev.glowcow.mnt.view.doc

import java.io.InputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/** An element of a small XML tree; names keep their prefix, and are looked up by the part after it. */
class El(val name: String, private val attrs: Map<String, String>, val nodes: List<Any>) {
    /** The name without its prefix: `p` of `w:p`. */
    val local: String get() = name.substringAfter(':')

    /** An attribute by its name without the prefix; the first that matches. */
    fun attr(name: String): String? = attrs[name] ?: attrs.entries.firstOrNull { it.key.substringAfter(':') == name }?.value

    /** The id of a relationship: an attribute `<prefix>:id`, beside which a plain `id` may stand. */
    fun rel(): String? = attrs.entries.firstOrNull { it.key.endsWith(":id") }?.value

    /** An attribute by its whole name, prefix and all. */
    fun exact(name: String): String? = attrs[name]

    val elements: List<El> get() = nodes.filterIsInstance<El>()

    fun child(local: String): El? = nodes.firstOrNull { it is El && it.local == local } as El?

    fun children(local: String): List<El> = nodes.filter { it is El && it.local == local }.map { it as El }

    /** The first element anywhere below with this name. */
    fun find(local: String): El? {
        for (node in nodes) {
            if (node !is El) continue
            if (node.local == local) return node
            node.find(local)?.let { return it }
        }
        return null
    }

    /** Every element anywhere below with this name, in document order. */
    fun all(local: String): List<El> = ArrayList<El>().also { collect(local, it) }

    private fun collect(local: String, into: MutableList<El>) {
        for (node in nodes) {
            if (node !is El) continue
            if (node.local == local) into += node
            node.collect(local, into)
        }
    }

    /** All the text below, as it stands. */
    fun text(): String = buildString { gather(this) }

    private fun gather(into: StringBuilder) {
        for (node in nodes) if (node is String) into.append(node) else (node as El).gather(into)
    }
}

object Xml {
    fun parser(input: InputStream): XmlPullParser = XmlPullParserFactory.newInstance().newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(input, null)
    }

    /** Reads a whole document into a tree. */
    fun parse(input: InputStream): El {
        val p = parser(input)
        val stack = ArrayList<Pair<String, Pair<Map<String, String>, MutableList<Any>>>>()
        var root: El? = null
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> {
                    val attrs = HashMap<String, String>(p.attributeCount * 2)
                    for (i in 0 until p.attributeCount) attrs[p.getAttributeName(i)] = p.getAttributeValue(i)
                    stack += p.name to (attrs to ArrayList())
                }
                XmlPullParser.END_TAG -> {
                    val (name, rest) = stack.removeAt(stack.lastIndex)
                    val el = El(name, rest.first, rest.second)
                    if (stack.isEmpty()) root = el else stack.last().second.second += el
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> stack.lastOrNull()?.second?.second?.add(p.text)
                XmlPullParser.ENTITY_REF -> stack.lastOrNull()?.second?.second?.add(p.text ?: "")
                XmlPullParser.END_DOCUMENT -> return root ?: El("", emptyMap(), emptyList())
            }
        }
    }
}
