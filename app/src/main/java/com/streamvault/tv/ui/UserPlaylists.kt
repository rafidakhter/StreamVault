package com.streamvault.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.streamvault.tv.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private fun PlaylistDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(620.dp).heightIn(max = 500.dp).glassPanel(RoundedCornerShape(24.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                TvButton("Close", small = true, onClick = onDismiss)
            }
            content()
        }
    }
}

@Composable
private fun PlaylistNameDialog(initial: String = "", onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.safeRequestFocus() }
    PlaylistDialog(if (initial.isEmpty()) "New playlist" else "Rename playlist", onDismiss) {
        OutlinedTextField(name, { name = it.take(48); error = null }, label = { Text("Playlist name") },
            singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus))
        error?.let { Text(it, color = SV.Live) }
        TvButton(if (initial.isEmpty()) "Create playlist" else "Save name", primary = true, small = true, onClick = {
            runCatching { onSave(name) }.onFailure { error = it.message ?: "Unable to save playlist." }
        })
    }
}

/** Only the resumed Activity owns the picker, including when playback has its own Activity. */
@Composable
fun MyListPickerHost() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = (context as? androidx.lifecycle.LifecycleOwner)?.lifecycle ?: return
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val request by Repo.listPicker.collectAsState()
    if (resumed) request?.let { selected ->
        key(selected) { UserPlaylistPicker(selected.id, selected.relatedIds) { Repo.dismissListPicker() } }
    }
}

@Composable
private fun UserPlaylistPicker(id: String, relatedIds: List<String>, onDismiss: () -> Unit) {
    val lists by Repo.userPlaylists.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.safeRequestFocus() }
    PlaylistDialog("Add to My List", onDismiss) {
        Text("Select a playlist to add or remove this title.", color = SV.Dim, fontSize = 14.sp)
        TvButton("New playlist", small = true, onClick = { creating = true })
        error?.let { Text(it, color = SV.Live) }
        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(lists, key = { it.id }) { list ->
                val saved = list.itemIds.any { it in relatedIds || it == id }
                TvButton((if (saved) "✓  " else "+  ") + list.name, primary = saved, small = true,
                    modifier = Modifier.fillMaxWidth().then(if (list.id == Repo.LIKED_PLAYLIST_ID) Modifier.focusRequester(focus) else Modifier), onClick = {
                        runCatching { Repo.setUserPlaylistItem(list.id, id, relatedIds, !saved) }
                            .onSuccess { error = null; Repo.postStatus(if (saved) "Removed from ${list.name}" else "Added to ${list.name}") }
                            .onFailure { error = it.message }
                    })
            }
        }
    }
    if (creating) PlaylistNameDialog(onDismiss = { creating = false }) { name ->
        val list = Repo.createUserPlaylist(name)
        Repo.setUserPlaylistItem(list.id, id, relatedIds, true)
        creating = false
        Repo.postStatus("Added to ${list.name}")
    }
}

private data class PlaylistTitle(val id: String, val name: String, val type: ContentType, val image: String?, val info: String, val relatedIds: List<String> = listOf(id))

private fun playlistTitle(lib: Library, id: String): PlaylistTitle? {
    if (id.startsWith("series:")) return lib.series[id.removePrefix("series:")]?.let {
        PlaylistTitle(id, LibraryOrganization.clean(it.name), ContentType.SERIES, it.poster, it.group)
    }
    return lib.byId[id]?.let { PlaylistTitle(id, if (it.type == ContentType.LIVE) it.name else LibraryOrganization.clean(it.name), it.type, it.artwork, it.group) }
}

@Composable
fun UserPlaylistsScreen(id: String? = null) {
    val lists by Repo.userPlaylists.collectAsState()
    val lib by Repo.library.collectAsState()
    val nav = LocalNav.current
    val current = lists.firstOrNull { it.id == id }
    var naming by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val initial = remember { FocusRequester() }
    val scroll = androidx.compose.foundation.lazy.rememberLazyListState()
    var focusedItem by rememberSaveable(id) { mutableStateOf<String?>(null) }
    val restoreKey = remember(id) {
        focusedItem?.takeIf { ref -> if (id == null) lists.any { it.id == ref } else current?.itemIds?.contains(ref) == true }
    }
    fun rowFocus(ref: String, request: Boolean = true): Modifier = Modifier.onFocusChanged {
        if (it.isFocused) focusedItem = ref
    }.then(if (request && ref == restoreKey) Modifier.focusRequester(initial) else Modifier)
    val headerFocus = if (restoreKey == null) Modifier.focusRequester(initial) else Modifier
    LaunchedEffect(id) {
        if (restoreKey != null) {
            val index = if (id == null) lists.indexOfFirst { it.id == restoreKey } else current?.itemIds?.indexOf(restoreKey) ?: -1
            if (index >= 0) scroll.scrollToItem(index + 1)
        }
        initial.safeRequestFocus()
    }
    LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().background(SV.Bg),
        contentPadding = PaddingValues(40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (id == null) "My List" else current?.name ?: "Playlist unavailable", color = Color.White,
                    fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(if (id == null) "Liked and your playlists · Movies, series and live TV" else "${current?.itemIds?.size ?: 0} titles · Saved to this profile",
                    color = SV.Dim, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (id == null) TvButton("New playlist", primary = true, small = true,
                        modifier = headerFocus, onClick = { naming = true })
                    else {
                        TvButton("Back", small = true, modifier = headerFocus, onClick = { nav.back() })
                        if (current != null) {
                            TvButton("Add titles", primary = true, small = true, onClick = { adding = true })
                            if (current.id != Repo.LIKED_PLAYLIST_ID) {
                                TvButton("Rename", small = true, onClick = { naming = true })
                                TvButton("Delete", small = true, onClick = { deleting = true })
                            }
                        }
                    }
                }
            }
        }
        if (id == null) {
            if (lists.isEmpty()) item { Text("Create your first playlist, then add titles from here, title details or your phone.", color = SV.Dim) }
            items(lists, key = { it.id }) { list ->
                TvButton("${list.name}  ·  ${list.itemIds.size} titles", modifier = rowFocus(list.id).fillMaxWidth(),
                    onClick = { nav.navigate(Route.UserPlaylist(list.id)) })
            }
        } else if (current != null) {
            if (current.itemIds.isEmpty()) item { Text("This playlist is empty. Choose Add titles to get started.", color = SV.Dim) }
            items(current.itemIds, key = { it }) { ref ->
                val title = playlistTitle(lib, ref)
                Row(Modifier.fillMaxWidth().glassPanel(RoundedCornerShape(16.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ArtImage(title?.image, title?.name ?: "Unavailable", Modifier.width(52.dp).height(72.dp).clip(RoundedCornerShape(8.dp)), titlePlaceholder = true)
                    Column(Modifier.weight(1f)) {
                        Text(title?.name ?: "Title unavailable", color = Color.White, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(title?.let { "${when(it.type) { ContentType.MOVIE -> "Movie"; ContentType.SERIES -> "Series"; ContentType.LIVE -> "Live TV" }} · ${it.info}" }
                            ?: "This title is missing from the current library.", color = SV.Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (title != null) TvButton(if (title.type == ContentType.LIVE) "Watch" else "Open", small = true, modifier = rowFocus(ref), onClick = {
                        if (title.type == ContentType.SERIES) nav.navigate(Route.SeriesDetails(ref.removePrefix("series:")))
                        else lib.byId[ref]?.let { nav.openChannel(it, lib) }
                    })
                    TvButton("Remove", small = true, modifier = rowFocus(ref, request = title == null), onClick = { Repo.setUserPlaylistItem(current.id, ref, listOf(ref), false) })
                }
            }
        }
    }
    if (naming) PlaylistNameDialog(current?.name.orEmpty(), { naming = false }) { name ->
        if (id == null) { Repo.createUserPlaylist(name); naming = false }
        else { Repo.renameUserPlaylist(id, name); naming = false }
    }
    if (adding && current != null) PlaylistAddTitles(current.id) { adding = false }
    if (deleting && current != null) PlaylistDialog("Delete ${current.name}?", { deleting = false }) {
        Text("Only this playlist is removed. Your library, other playlists and watch history are kept.", color = SV.Dim)
        TvButton("Delete playlist", small = true, onClick = { Repo.deleteUserPlaylist(current.id); deleting = false; nav.back() })
    }
}

@Composable
private fun PlaylistAddTitles(playlistId: String, onDismiss: () -> Unit) {
    val lib by Repo.library.collectAsState()
    val lists by Repo.userPlaylists.collectAsState()
    var query by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ContentType.MOVIE) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.safeRequestFocus() }
    val results by produceState<List<PlaylistTitle>>(emptyList(), lib, query, type) {
        value = withContext(Dispatchers.Default) {
            if (type == ContentType.LIVE) lib.all.asSequence().filter { it.type == type && it.name.contains(query.trim(), true) }.take(40)
                .map { PlaylistTitle(it.id, it.name, type, it.artwork, it.group) }.toList()
            else LibraryOrganization.catalog(lib, type).asSequence().filter { it.name.contains(query.trim(), true) }.take(40).map { title ->
                val version = LibraryOrganization.choose(title.versions, Repo.settings.value)
                PlaylistTitle(version.id, title.name, type, version.poster,
                    "${version.language} · ${LibraryOrganization.qualityLabel(version.quality)}", title.versions.map { it.id })
            }.toList()
        }
    }
    PlaylistDialog("Add titles", onDismiss) {
        OutlinedTextField(query, { query = it.take(120) }, singleLine = true, label = { Text("Search titles") },
            modifier = Modifier.fillMaxWidth().focusRequester(focus))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (kind in listOf(ContentType.MOVIE, ContentType.SERIES, ContentType.LIVE))
                TvButton(when(kind) { ContentType.MOVIE -> "Movies"; ContentType.SERIES -> "Series"; ContentType.LIVE -> "Live TV" },
                    primary = kind == type, small = true, onClick = { type = kind })
        }
        error?.let { Text(it, color = SV.Live) }
        Text("Up to 40 matches. Refine your search to find more.", color = SV.Dim, fontSize = 12.sp)
        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results, key = { it.id }) { title ->
                val saved = lists.firstOrNull { it.id == playlistId }?.itemIds?.any { it in title.relatedIds } == true
                TvButton((if(saved) "✓  " else "+  ") + title.name.take(48) + " · " + title.info.take(24), small = true,
                    primary = saved, modifier = Modifier.fillMaxWidth(), onClick = {
                        runCatching { Repo.setUserPlaylistItem(playlistId, title.id, title.relatedIds, !saved) }
                            .onFailure { error = it.message }.onSuccess { error = null }
                    })
            }
            if (results.isEmpty()) item { Text("No matches in this category.", color = SV.Dim) }
        }
    }
}
