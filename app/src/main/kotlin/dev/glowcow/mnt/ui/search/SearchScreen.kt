package dev.glowcow.mnt.ui.search

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.glowcow.mnt.AppContainer
import dev.glowcow.mnt.MntApp
import dev.glowcow.mnt.R
import dev.glowcow.mnt.audio.Sounds
import dev.glowcow.mnt.archive.Archives
import dev.glowcow.mnt.files.Node
import dev.glowcow.mnt.files.Opener
import dev.glowcow.mnt.files.Paths
import dev.glowcow.mnt.ui.components.EmptyState
import dev.glowcow.mnt.ui.components.NodeRow
import dev.glowcow.mnt.ui.components.rememberThumbnail
import dev.glowcow.mnt.ui.components.StorageGate
import dev.glowcow.mnt.ui.components.TabScreen
import dev.glowcow.mnt.ui.components.TopTab
import dev.glowcow.mnt.ui.theme.AppFont
import dev.glowcow.mnt.ui.theme.MntIcons
import dev.glowcow.mnt.ui.theme.MntTheme
import dev.glowcow.mnt.view.Format
import dev.glowcow.mnt.view.image.Readers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import dev.glowcow.mnt.video.Videos

/** What a search has found so far; [running] while it still looks, [capped] when it stopped at the limit. */
data class SearchState(val found: List<Node> = emptyList(), val running: Boolean = false, val capped: Boolean = false)

@OptIn(FlowPreview::class)
class SearchViewModel(private val c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state

    init {
        viewModelScope.launch {
            // A new letter starts the search over, once the typing has paused.
            query.debounce(DEBOUNCE_MS).collectLatest { typed ->
                val needle = typed.trim()
                _state.value = SearchState(running = needle.length >= MIN_LETTERS)
                if (needle.length < MIN_LETTERS) return@collectLatest
                val hidden = c.settings.settings.first().showHidden
                val found = ArrayList<Node>()
                c.search.find(c.volumes.list.map { it.path }, needle, hidden).takeWhile { found.size < LIMIT }.collect {
                    found += it
                    _state.value = SearchState(found.toList(), running = true)
                }
                _state.value = SearchState(found.toList(), capped = found.size >= LIMIT)
            }
        }
    }

    /** Whether rows show thumbnails, and of the pictures on servers too. */
    val thumbnails: StateFlow<Triple<Boolean, Boolean, Boolean>> = c.settings.settings.map { Triple(it.thumbnails, it.thumbnailsFar, it.thumbnailsVideo) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Triple(false, false, false))

    /** What was searched for before, the latest first. */
    val recent: StateFlow<List<String>> = c.settings.settings.map { it.searches }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Keeps what is typed now as a search worth coming back to: one whose result was opened, or that was sent from the keyboard. */
    fun remember() {
        val typed = query.value.trim()
        if (typed.length >= MIN_LETTERS) viewModelScope.launch { c.settings.rememberSearch(typed) }
    }

    fun display(path: String): String = c.roots.display(path)

    companion object {
        const val LIMIT = 300
        const val MIN_LETTERS = 2
        private const val DEBOUNCE_MS = 300L
    }
}

@Composable
fun SearchScreen(
    onTab: (TopTab) -> Unit,
    onOpen: (String) -> Unit,
    onView: (String) -> Unit,
    vm: SearchViewModel = viewModel { SearchViewModel((this[APPLICATION_KEY] as MntApp).container) },
) {
    val c = MntTheme.colors
    val context = LocalContext.current
    val query by vm.query.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val thumbnails by vm.thumbnails.collectAsStateWithLifecycle()
    val none = stringResource(R.string.open_none)
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    TabScreen(
        TopTab.SEARCH,
        // A tap on the tab that is already open goes straight to typing.
        onTab = {
            if (it == TopTab.SEARCH) {
                focus.requestFocus()
                keyboard?.show()
            } else {
                onTab(it)
            }
        },
        ground = c.bg,
        header = {
            Row(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(CircleShape)
                    .background(c.chip)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(MntIcons.Search, null, tint = c.muted, modifier = Modifier.size(22.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(stringResource(R.string.search_hint), color = c.muted, fontSize = 15.sp)
                    BasicTextField(
                        value = query,
                        onValueChange = { vm.query.value = it },
                        singleLine = true,
                        textStyle = TextStyle(color = c.text, fontFamily = AppFont, fontSize = 15.sp),
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { vm.remember() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
            }
        },
    ) { top, bottom ->
        StorageGate(top + 8.dp) {
            when {
                query.trim().length < SearchViewModel.MIN_LETTERS -> if (recent.isEmpty()) {
                    Centered(top, bottom) { EmptyState(stringResource(R.string.search_start), stringResource(R.string.search_start_text)) }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = top, bottom = bottom)) {
                        items(recent, key = { it }) { past -> RecentRow(past) { vm.query.value = past } }
                    }
                }
                state.found.isEmpty() && !state.running -> Centered(top, bottom) { EmptyState(stringResource(R.string.search_empty)) }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = top, bottom = bottom)) {
                    items(state.found, key = { it.path }) { node ->
                        // A long press on a file leads to the folder it lies in.
                        NodeRow(node, vm.display(Paths.parent(node.path)), thumbnail = rememberThumbnail(node, thumbnails.first, thumbnails.second, thumbnails.third), onLongClick = { onOpen(Paths.parent(node.path)) }) {
                            vm.remember()
                            when {
                                node.directory -> onOpen(node.path)
                                Archives.opens(node.name) -> onOpen(Archives.root(node.path))
                                Readers.shows(node.name) || Sounds.plays(node.name) || Videos.plays(node.name) || Format.of(node.name) != null -> onView(node.path)
                                !Opener.open(context, node.path) -> Toast.makeText(context, none, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    if (state.capped || state.running) {
                        item {
                            Text(
                                if (state.running) stringResource(R.string.search_running) else stringResource(R.string.search_capped, SearchViewModel.LIMIT),
                                color = c.muted,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A search made before: a tap runs it again. */
@Composable
private fun RecentRow(query: String, onClick: () -> Unit) {
    val c = MntTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(MntIcons.Recent, null, tint = c.muted, modifier = Modifier.size(22.dp)) }
        Text(query, color = c.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Centered(top: Dp, bottom: Dp, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(top = top, bottom = bottom), contentAlignment = Alignment.Center) { content() }
}
