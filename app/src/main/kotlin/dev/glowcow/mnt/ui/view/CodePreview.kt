package dev.glowcow.mnt.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glowcow.mnt.ui.theme.MntColors
import dev.glowcow.mnt.ui.theme.MonoFont
import dev.glowcow.mnt.view.code.CodeScheme
import dev.glowcow.mnt.view.code.Languages

private const val SAMPLE = "class Door:\n    def open(self, key=None):\n        # unlock it first\n        if key is None:\n            return False\n        return \"open\" * 2"

/** A few lines of code in [scheme], as the viewer shows them; [colours] are the app's of the scheme's darkness. */
@Composable
fun CodePreview(scheme: CodeScheme, colours: MntColors) {
    val ground = scheme.ground?.let { Color(it) } ?: colours.groupBg
    val text = scheme.text?.let { Color(it) } ?: colours.text
    val gutter = scheme.gutter?.let { Color(it) } ?: colours.muted
    val lines = remember(scheme) {
        val spans = Languages.PYTHON.lex(SAMPLE)
        var at = 0
        SAMPLE.split('\n').map { line ->
            val start = at
            at += line.length + 1
            buildAnnotatedString {
                append(line)
                spans.filter { it.end > start && it.start < start + line.length }.forEach { s ->
                    scheme.tones[s.tone]?.let { addStyle(SpanStyle(color = Color(it)), maxOf(0, s.start - start), minOf(line.length, s.end - start)) }
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth().background(ground).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        lines.forEachIndexed { i, line ->
            Row(Modifier.padding(start = 8.dp, end = 4.dp)) {
                Text("${i + 1}", color = gutter, fontFamily = MonoFont, fontSize = 10.sp, modifier = Modifier.padding(end = 8.dp))
                Text(line, color = text, fontFamily = MonoFont, fontSize = 10.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
            }
        }
    }
}
