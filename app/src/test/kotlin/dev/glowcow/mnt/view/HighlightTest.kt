package dev.glowcow.mnt.view

import dev.glowcow.mnt.view.code.Languages
import dev.glowcow.mnt.view.code.Lexer
import dev.glowcow.mnt.view.code.Tone
import dev.glowcow.mnt.view.html.CodeHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The colouring of code: which stretch of a line takes which tone, and the HTML made of it. */
class HighlightTest {
    private fun tones(lexer: Lexer, text: String): Map<String, Tone> = lexer.lex(text).associate { text.substring(it.start, it.end) to it.tone }

    @Test
    fun kotlin() {
        val t = tones(Languages.of("a.kt")!!.lexer, "fun main() {\n    // say hi\n    val n = 42\n    println(\"hi \$n\")\n}\n")
        assertEquals(Tone.KEYWORD, t["fun"])
        assertEquals(Tone.FUNC, t["main"])
        assertEquals(Tone.COMMENT, t["// say hi"])
        assertEquals(Tone.NUMBER, t["42"])
        assertEquals(Tone.STRING, t["\"hi \$n\""])
    }

    @Test
    fun python() {
        val t = tones(Languages.of("a.py")!!.lexer, "@cache\ndef f(x=None):\n    return f'{x}' + \"\"\"doc\nmore\"\"\"\n")
        assertEquals(Tone.META, t["@cache"])
        assertEquals(Tone.FUNC, t["f"])
        assertEquals(Tone.LITERAL, t["None"])
        assertEquals(Tone.STRING, t["f'{x}'"])
        assertEquals(Tone.STRING, t["\"\"\"doc\nmore\"\"\""])
    }

    @Test
    fun shell() {
        val t = tones(Languages.of("run.sh")!!.lexer, "#!/bin/sh\nif [ -n \"\$HOME\" ]; then echo \$1 # done\nfi\n")
        assertEquals(Tone.COMMENT, t["#!/bin/sh"])
        assertEquals(Tone.KEYWORD, t["if"])
        assertEquals(Tone.STRING, t["\"\$HOME\""])
        assertEquals(Tone.VARIABLE, t["\$1"])
        assertEquals(Tone.COMMENT, t["# done"])
    }

    @Test
    fun json() {
        val t = tones(Languages.of("a.json")!!.lexer, "{\"name\": \"mnt\", \"n\": 1.5e3, \"ok\": true, \"none\": null}")
        assertEquals(Tone.ATTR, t["\"name\""])
        assertEquals(Tone.STRING, t["\"mnt\""])
        assertEquals(Tone.NUMBER, t["1.5e3"])
        assertEquals(Tone.LITERAL, t["true"])
        assertEquals(Tone.LITERAL, t["null"])
    }

    @Test
    fun yaml() {
        val t = tones(Languages.of("ci.yml")!!.lexer, "# ci\nimage: alpine:3.24\nlist:\n  - name: \"x\"\n    on: true\nscript: |\n  echo one\n  echo two\nport: 8080\n")
        assertEquals(Tone.COMMENT, t["# ci"])
        assertEquals(Tone.ATTR, t["image"])
        assertEquals(Tone.ATTR, t["name"])
        assertEquals(Tone.STRING, t["\"x\""])
        assertEquals(Tone.LITERAL, t["true"])
        assertEquals(Tone.STRING, t["  echo one"])
        assertEquals(Tone.NUMBER, t["8080"])
    }

    @Test
    fun xml() {
        val t = tones(Languages.of("a.xml")!!.lexer, "<?xml version=\"1.0\"?>\n<!-- c --><root id=\"1\">&amp;</root>")
        assertEquals(Tone.META, t["<?xml version=\"1.0\"?>"])
        assertEquals(Tone.COMMENT, t["<!-- c -->"])
        assertEquals(Tone.TAG, t["root"])
        assertEquals(Tone.ATTR, t["id"])
        assertEquals(Tone.STRING, t["\"1\""])
        assertEquals(Tone.LITERAL, t["&amp;"])
    }

    @Test
    fun html() {
        val t = tones(Languages.of("i.html")!!.lexer, "<script>const a = 1;</script><style>p { color: red; }</style>")
        assertEquals(Tone.KEYWORD, t["const"])
        assertEquals(Tone.ATTR, t["color"])
        assertEquals(Tone.TAG, t["p"])
    }

    @Test
    fun ini() {
        val t = tones(Languages.of("app.service")!!.lexer, "[Unit]\n; note\nDescription=Mnt \${HOME}\nRestart=on\n")
        assertEquals(Tone.SECTION, t["[Unit]"])
        assertEquals(Tone.COMMENT, t["; note"])
        assertEquals(Tone.ATTR, t["Description"])
        assertEquals(Tone.VARIABLE, t["\${HOME}"])
        assertEquals(Tone.LITERAL, t["on"])
    }

    @Test
    fun routeros() {
        val t = tones(Languages.of("export.rsc")!!.lexer, "# by RouterOS\n/ip firewall filter\nadd action=accept chain=input comment=\"ok\" dst-port=22\n:local x \$y\n")
        assertEquals(Tone.COMMENT, t["# by RouterOS"])
        assertEquals(Tone.SECTION, t["/ip"])
        assertEquals(Tone.SECTION, t["firewall"])
        assertEquals(Tone.FUNC, t["add"])
        assertEquals(Tone.ATTR, t["action"])
        assertEquals(Tone.STRING, t["\"ok\""])
        assertEquals(Tone.NUMBER, t["22"])
        assertEquals(Tone.KEYWORD, t[":local"])
        assertEquals(Tone.VARIABLE, t["\$y"])
    }

    @Test
    fun diffAndLog() {
        val d = tones(Languages.of("a.diff")!!.lexer, "--- a\n+++ b\n@@ -1 +1 @@\n-old\n+new\n")
        assertEquals(Tone.REMOVED, d["-old"])
        assertEquals(Tone.ADDED, d["+new"])
        assertEquals(Tone.SECTION, d["@@ -1 +1 @@"])
        val l = tones(Languages.of("app.log")!!.lexer, "2026-10-14 22:33:12 ERROR boom\n10:00:01 WARN slow")
        assertEquals(Tone.NUMBER, l["2026-10-14 22:33:12"])
        assertEquals(Tone.ERROR, l["ERROR"])
        assertEquals(Tone.WARNING, l["WARN"])
    }

    @Test
    fun namesAndScripts() {
        assertEquals("Dockerfile", Languages.of("Dockerfile")!!.name)
        assertEquals("Makefile", Languages.of("Makefile")!!.name)
        assertEquals("Shell", Languages.of(".bashrc")!!.name)
        assertEquals("Properties", Languages.of(".env.local")!!.name)
        assertEquals("Python", Languages.ofScript("#!/usr/bin/env python3")!!.name)
        assertNull(Languages.of("photo.jpg"))
        assertEquals(Format.TEXT, Format.of("main.go"))
        assertEquals(Format.UNKNOWN, Format.of("NOTES"))
        assertNull(Format.of("movie.mkv"))
        assertEquals(Format.DJVU, Format.of("book.djvu"))
    }

    @Test
    fun codeHtml() {
        val html = CodeHtml.render("a = \"\"\"x\ny\"\"\"\n\nb", Languages.PYTHON, first = 7)
        // A string over a line break is closed before it and opened again after; the breaks stay in the text.
        assertTrue(html, html.contains("<span class=\"s\">\"\"\"x</span>\n</span><span class=\"r\"><span class=\"s\">y\"\"\"</span>"))
        assertEquals(4, Regex("class=\"r\"").findAll(html).count())
        assertTrue(html.contains("--first:6;--gw:2ch"))
        assertTrue(CodeHtml.render("<b>&", Languages.PYTHON).contains("&lt;b&gt;&amp;"))
    }

    @Test
    fun encodings() {
        val cyrillic = "Привет, мир".toByteArray(charset("windows-1251"))
        assertEquals("windows-1251", Texts.charset(cyrillic).name())
        assertEquals("UTF-8", Texts.charset("Привет".toByteArray()).name())
        // A character cut at the end of what was read does not turn UTF-8 into something else.
        val cut = "Привет".toByteArray().copyOf(5)
        assertEquals("UTF-8", Texts.charset(cut).name())
        assertTrue(Texts.isText("plain text\n".toByteArray()))
        assertTrue(!Texts.isText(byteArrayOf(0x50, 0x4B, 3, 4, 0, 0, 0)))
    }
}
