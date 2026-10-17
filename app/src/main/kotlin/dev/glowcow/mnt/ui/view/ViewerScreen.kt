package dev.glowcow.mnt.ui.view

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.glowcow.mnt.R
import dev.glowcow.mnt.data.AppSettings
import dev.glowcow.mnt.files.Opener
import dev.glowcow.mnt.ui.components.ChoiceSheet
import dev.glowcow.mnt.ui.components.DetailHeader
import dev.glowcow.mnt.ui.components.DetailPage
import dev.glowcow.mnt.ui.components.EmptyState
import dev.glowcow.mnt.ui.components.Group
import dev.glowcow.mnt.ui.components.GroupDivider
import dev.glowcow.mnt.ui.components.GroupRow
import dev.glowcow.mnt.ui.components.GroupSheet
import dev.glowcow.mnt.ui.components.IconButton48
import dev.glowcow.mnt.ui.components.IslandIcons
import dev.glowcow.mnt.ui.components.LocalIsland
import dev.glowcow.mnt.ui.components.LocalPageDark
import dev.glowcow.mnt.ui.components.PillButton
import dev.glowcow.mnt.ui.components.ProgressLine
import dev.glowcow.mnt.ui.components.SwitchRow
import dev.glowcow.mnt.ui.theme.AppFont
import dev.glowcow.mnt.ui.theme.MntColors
import dev.glowcow.mnt.ui.theme.MntColorsProvider
import dev.glowcow.mnt.ui.theme.MntIcons
import dev.glowcow.mnt.ui.theme.MntTheme
import dev.glowcow.mnt.ui.theme.colorsOf
import dev.glowcow.mnt.ui.theme.inked
import dev.glowcow.mnt.view.Texts
import dev.glowcow.mnt.view.code.CodeScheme
import dev.glowcow.mnt.view.doc.Epub
import dev.glowcow.mnt.view.html.Look
import dev.glowcow.mnt.view.html.Spot
import kotlin.math.roundToInt

private enum class ViewerSheet { MORE, ENCODING, PARTS }

/**
 * A file shown inside the app: text and code coloured as the settings say, documents, books, tables and pages.
 * A link goes to [onLink]; a link to a file beside it opens that file in [onFile].
 */
@Composable
fun ViewerScreen(
    path: String,
    onBack: () -> Unit,
    onLink: (String) -> Unit,
    onFile: (String) -> Unit,
    vm: ViewerViewModel = viewModel(key = path) { ViewerViewModel(path, this[APPLICATION_KEY]!!) },
) {
    val context = LocalContext.current
    val settings = vm.settings.collectAsStateWithLifecycle().value ?: return
    val app = MntTheme.colors
    val scheme = if (app.isDark) settings.codeDark else settings.codeLight
    // A page of code takes the colours of its scheme, and the app's colours of the same darkness go round it.
    val dark = if (vm.code) scheme.dark ?: app.isDark else app.isDark
    val chrome = colorsOf(settings.palette, dark, LocalConfiguration.current.isScreenWideColorGamut)
    val bars = LocalPageDark.current
    DisposableEffect(dark) {
        bars.value = dark
        onDispose { bars.value = null }
    }
    val look = remember(chrome, scheme, settings.wrapLines, settings.lineNumbers, settings.pages, vm.code, settings.palette) {
        look(chrome, colorsOf(settings.palette, scheme.dark ?: dark, false), scheme, vm.code, settings.wrapLines, settings.lineNumbers, settings.pages)
    }
    MntColorsProvider(chrome) {
        Viewer(vm, settings, look, onBack, onLink, onFile)
    }
    when (val s = vm.shown) {
        Shown.Elsewhere -> LaunchedEffect(s) {
            if (!Opener.open(context, path)) Toast.makeText(context, R.string.open_none, Toast.LENGTH_SHORT).show()
            onBack()
        }
        is Shown.Link -> LaunchedEffect(s) {
            onBack()
            onLink(s.url)
        }
        else -> Unit
    }
}

@Composable
private fun Viewer(vm: ViewerViewModel, settings: AppSettings, look: Look, onBack: () -> Unit, onLink: (String) -> Unit, onFile: (String) -> Unit) {
    val c = MntTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val finder = remember { Finder() }
    val sheets = remember { Sheets() }
    val pdfSearch = remember(c.accent) { PdfSearch(scope, matchColor(c.accent, false), matchColor(c.accent, true)) }
    val pdfPages = remember { PdfPages(scope) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sheet by rememberSaveable { mutableStateOf<ViewerSheet?>(null) }
    var scrolled by remember { mutableStateOf(false) }
    val shown = vm.shown
    val search: Search = if (shown is Shown.Pdf) pdfSearch else finder
    val web = shown as? Shown.Web
    val book = web?.doc as? Epub

    BackHandler(enabled = searching) {
        searching = false
        query = ""
        search.clear()
    }

    // Sheets, a PDF's pages and the parts of a long text: where the screen is, of how many, and a step either way.
    // A book has its island from the start: its sheets are cut to what the bars leave.
    val bar: (@Composable () -> Unit)? = if (shown is Shown.Web && (sheets.count > 1 || book != null)) ({
        Steps(sheets.page, maxOf(1, sheets.count), R.string.viewer_page_previous, R.string.viewer_page_next) { sheets.go(it) }
    }) else if (vm.code && vm.parts > 1 && shown is Shown.Web) ({
        Steps(vm.part, vm.parts, R.string.viewer_part_previous, R.string.viewer_part_next) { vm.step(it > vm.part) }
    }) else if (shown is Shown.Pdf && pdfPages.count > 1) ({
        val page by remember { derivedStateOf { pdfPages.page } }
        Steps(page, pdfPages.count, R.string.viewer_page_previous, R.string.viewer_page_next) { pdfPages.go(it) }
    }) else null

    DetailPage(
        ground = Color(look.ground),
        scrolled = scrolled,
        header = {
            if (searching) {
                SearchRow(query, search, onQuery = {
                    query = it
                    search.find(it)
                }, onClose = {
                    searching = false
                    query = ""
                    search.clear()
                })
            } else {
                DetailHeader(vm.name, onBack) {
                    if (shown is Shown.Web || shown is Shown.Pdf) IconButton48(MntIcons.Search, stringResource(R.string.viewer_search)) { searching = true }
                    if (book != null || web?.doc?.parts?.isNotEmpty() == true) {
                        IconButton48(MntIcons.Contents, stringResource(if (book != null) R.string.viewer_contents else R.string.viewer_sheets)) { sheet = ViewerSheet.PARTS }
                    }
                    if (vm.twoWays) {
                        IconButton48(if (vm.source) MntIcons.Eye else MntIcons.Code, stringResource(if (vm.source) R.string.viewer_rendered else R.string.viewer_source)) { vm.toggleSource() }
                    }
                    IconButton48(MntIcons.More, stringResource(R.string.action_more)) { sheet = ViewerSheet.MORE }
                }
            }
        },
        bar = bar,
        // A web view drawn a second time under the glass redraws itself without end: it flickers. The app's pages draw the glass themselves.
        glass = shown !is Shown.Web,
        clear = web?.doc?.own == true,
    ) { top, bottom ->
        // A page is made once the bars are measured; a bar that comes later only moves its edges.
        var placed by remember(shown) { mutableStateOf(false) }
        if (top > 0.dp && (bar == null || bottom > 0.dp)) placed = true
        when (shown) {
            Shown.Loading, Shown.Elsewhere, is Shown.Link -> Unit
            is Shown.Fetching -> Centered(top, bottom) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 48.dp)) {
                    androidx.compose.foundation.layout.Column {
                        Text(stringResource(R.string.viewer_fetching, (shown.fraction * 100).toInt()), color = c.muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 10.dp))
                        ProgressLine(shown.fraction)
                    }
                }
            }
            is Shown.Failed -> Centered(top, bottom) {
                androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(stringResource(R.string.viewer_failed), shown.reason ?: stringResource(R.string.viewer_failed_text))
                    Box(Modifier.padding(top = 16.dp)) { PillButton(stringResource(R.string.viewer_open_with)) { open(context, vm.path) } }
                }
            }
            // The page is laid out round the bars once their heights are known, so it is not laid out twice.
            is Shown.Web -> if (placed || top > 0.dp && (bar == null || bottom > 0.dp)) WebPage(
                doc = shown.doc,
                look = if (shown.doc.own) look.copy(top = top.value.roundToInt(), bottom = bottom.value.roundToInt(), island = island(LocalIsland.current.value)) else look,
                start = shown.start,
                finder = finder,
                sheets = sheets,
                onLink = onLink,
                onFile = onFile,
                onPath = vm::at,
                onScrolled = { scrolled = it },
                modifier = if (shown.doc.own) Modifier.fillMaxSize() else Modifier.fillMaxSize().padding(top = top, bottom = bottom),
            )
            is Shown.Pdf -> PdfPage(shown.uri, pdfSearch, pdfPages, onLink, onFailed = { vm.failed(it) }, onScrolled = { scrolled = it }, top = top, bottom = bottom)
        }
    }

    when (sheet) {
        ViewerSheet.MORE -> GroupSheet(vm.name, onDismiss = { sheet = null }) { pick ->
            Group {
                GroupRow(stringResource(R.string.viewer_open_with), icon = MntIcons.External, onClick = { pick { open(context, vm.path) } }, trailing = {})
                GroupDivider()
                GroupRow(stringResource(R.string.action_share), icon = MntIcons.Share, onClick = {
                    pick { if (!Opener.share(context, listOf(vm.path))) Toast.makeText(context, R.string.open_none, Toast.LENGTH_SHORT).show() }
                }, trailing = {})
            }
            if (vm.paged) {
                Group(Modifier.padding(top = 12.dp)) {
                    SwitchRow(stringResource(R.string.settings_pages), stringResource(R.string.settings_pages_hint), settings.pages) { vm.setPages(it) }
                }
            }
            if (vm.code) {
                Group(Modifier.padding(top = 12.dp)) {
                    SwitchRow(stringResource(R.string.settings_wrap), stringResource(R.string.settings_wrap_hint), settings.wrapLines) { vm.setWrap(it) }
                    GroupDivider()
                    SwitchRow(stringResource(R.string.settings_numbers), stringResource(R.string.settings_numbers_hint), settings.lineNumbers) { vm.setNumbers(it) }
                    GroupDivider()
                    GroupRow(
                        stringResource(R.string.viewer_encoding),
                        value = if (vm.encoding == null) stringResource(R.string.viewer_encoding_auto, vm.reading.orEmpty()) else vm.encoding,
                        onClick = { pick { sheet = ViewerSheet.ENCODING } },
                    )
                }
            }
        }
        ViewerSheet.ENCODING -> ChoiceSheet(
            title = stringResource(R.string.viewer_encoding),
            options = listOf<Pair<String?, String>>(null to stringResource(R.string.viewer_encoding_auto, vm.reading.orEmpty())) + Texts.choices.map { it to it },
            selected = vm.encoding,
            onSelect = vm::pickEncoding,
            onDismiss = { sheet = null },
        )
        ViewerSheet.PARTS -> GroupSheet(stringResource(if (book != null) R.string.viewer_contents else R.string.viewer_sheets), onDismiss = { sheet = null }) { pick ->
            val accent = c.accent
            Group {
                if (book != null && book.contents.isNotEmpty()) {
                    book.contents.forEachIndexed { i, (title, target, depth) ->
                        if (i > 0) GroupDivider()
                        GroupRow(title.ifBlank { "—" }, modifier = Modifier.padding(start = (16 * depth).dp), onClick = { pick { vm.go(book.link(target)) } }, trailing = {})
                    }
                } else {
                    web?.doc?.parts.orEmpty().forEachIndexed { i, name ->
                        if (i > 0) GroupDivider()
                        GroupRow(name.ifBlank { "${i + 1}" }, onClick = { pick { vm.go(web!!.doc.path(i)) } }, trailing = {
                            if (i == vm.current) Icon(MntIcons.Check, null, tint = accent, modifier = Modifier.size(20.dp).inked(accent))
                        })
                    }
                }
            }
        }
        null -> Unit
    }
}

/** Where the island stands, in the CSS pixels of a page: a dp is a CSS pixel in the viewer's web view. */
@Composable
private fun island(spot: Rect?): Spot {
    spot ?: return Spot()
    val density = LocalDensity.current.density
    return Spot((spot.left / density).roundToInt(), (spot.top / density).roundToInt(), (spot.width / density).roundToInt(), (spot.height / density).roundToInt())
}

/** An island that steps through [count] pages or parts: the one in sight (from 0), of how many, and one before or after. */
@Composable
private fun Steps(at: Int, count: Int, previous: Int, next: Int, onGo: (Int) -> Unit) {
    IslandIcons {
        IconButton48(MntIcons.CaretUp, stringResource(previous), enabled = at > 0) { onGo(at - 1) }
        Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.viewer_part, at + 1, count), color = MntTheme.colors.text, fontSize = 13.sp)
        }
        IconButton48(MntIcons.CaretDown, stringResource(next), enabled = at < count - 1) { onGo(at + 1) }
    }
}

/** The header while searching: the field, how many were found, the way between them. */
@Composable
private fun SearchRow(query: String, search: Search, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val c = MntTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton48(MntIcons.Close, stringResource(R.string.action_cancel), onClick = onClose)
        Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            if (query.isEmpty()) Text(stringResource(R.string.viewer_search), color = c.muted, fontSize = 16.sp)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(color = c.text, fontFamily = AppFont, fontSize = 16.sp),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (query.isNotEmpty()) {
            Text(
                if (search.count == 0) stringResource(R.string.viewer_no_matches) else stringResource(R.string.viewer_matches, search.current, search.count),
                color = c.muted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
        IconButton48(MntIcons.CaretUp, stringResource(R.string.viewer_previous), enabled = search.count > 0) { search.step(false) }
        IconButton48(MntIcons.CaretDown, stringResource(R.string.viewer_next), enabled = search.count > 0) { search.step(true) }
    }
}

@Composable
private fun Centered(top: androidx.compose.ui.unit.Dp, bottom: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(top = top, bottom = bottom), contentAlignment = Alignment.Center) { content() }
}

private fun open(context: android.content.Context, path: String) {
    if (!Opener.open(context, path)) Toast.makeText(context, R.string.open_none, Toast.LENGTH_SHORT).show()
}

/**
 * The colours and the layout of the viewer's pages: [page] are the app's colours round them; [codeColours] those of
 * the scheme's own darkness, which a light scheme's code keeps on a dark page.
 */
private fun look(page: MntColors, codeColours: MntColors, scheme: CodeScheme, code: Boolean, wrap: Boolean, numbers: Boolean, pages: Boolean): Look {
    fun Color.argb() = toArgb().toLong() and 0xFFFFFFFFL
    val codeGround = scheme.ground ?: (if (code) codeColours.groupBg else codeColours.group).argb()
    val codeText = scheme.text ?: codeColours.text.argb()
    return Look(
        ground = if (code) codeGround else page.groupBg.argb(),
        text = if (code) codeText else page.text.argb(),
        muted = page.muted.argb(),
        line = page.line.argb(),
        block = page.group.argb(),
        accent = Color(page.accent.red, page.accent.green, page.accent.blue).argb(),
        dark = page.isDark,
        code = scheme,
        codeGround = codeGround,
        codeText = codeText,
        codeGutter = scheme.gutter ?: codeColours.muted.argb(),
        wrap = wrap,
        numbers = numbers,
        pages = pages,
    )
}
