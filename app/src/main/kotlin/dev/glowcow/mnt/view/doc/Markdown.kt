package dev.glowcow.mnt.view.doc

import dev.glowcow.mnt.view.code.Languages
import dev.glowcow.mnt.view.code.Plain
import dev.glowcow.mnt.view.html.CodeHtml
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.html.HtmlNodeRendererContext
import org.commonmark.renderer.html.HtmlRenderer

/** Markdown as GitHub shows it: tables, task lists, struck text, bare links; code blocks coloured in their language. */
object Markdown {
    private val extensions = listOf(TablesExtension.create(), StrikethroughExtension.create(), TaskListItemsExtension.create(), AutolinkExtension.create())
    private val parser = Parser.builder().extensions(extensions).build()
    private val renderer = HtmlRenderer.builder()
        .extensions(extensions)
        .sanitizeUrls(true)
        .nodeRendererFactory { Code(it) }
        .build()

    fun html(text: String): String = "<div class=\"md\">" + renderer.render(parser.parse(text)) + "</div>"

    private class Code(private val context: HtmlNodeRendererContext) : NodeRenderer {
        override fun getNodeTypes(): Set<Class<out Node>> = setOf(FencedCodeBlock::class.java, IndentedCodeBlock::class.java)

        override fun render(node: Node) {
            val (code, info) = when (node) {
                is FencedCodeBlock -> node.literal to node.info.orEmpty()
                is IndentedCodeBlock -> node.literal to ""
                else -> return
            }
            val lexer = Languages.byName(info.trim().substringBefore(' ')) ?: Plain
            context.writer.raw(CodeHtml.render(code.removeSuffix("\n"), lexer, file = false))
        }
    }
}
