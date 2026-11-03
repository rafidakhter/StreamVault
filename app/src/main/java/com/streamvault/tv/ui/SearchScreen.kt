package com.streamvault.tv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamvault.tv.data.ContentType
import com.streamvault.tv.data.Library
import com.streamvault.tv.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ScopedSearchButton(type: ContentType) {
    val nav = LocalNav.current
    TvButton("Search", icon = Icons.Rounded.Search, onClick = { nav.navigate(Route.Search(type)) })
}

/** Search the relevant catalog off the UI thread; series are searched once per show. */
@Composable
fun SearchScreen(lib: Library, favList: List<String>, type: ContentType? = null) {
    val nav = LocalNav.current
    val progress by Repo.progress.collectAsState()
    var query by rememberSaveable(type) { mutableStateOf("") }
    var hasBrowsed by rememberSaveable(type) { mutableStateOf(false) }
    val resultStates = rememberSaveableStateHolder()
    val normalized = query.trim()
    val favs = remember(favList) { favList.toHashSet() }
    val inputFocus = remember { FocusRequester() }
    val resultsFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val label = when (type) {
        ContentType.MOVIE -> "Movies"
        ContentType.SERIES -> "Series"
        ContentType.LIVE -> "Live TV"
        null -> null
    }
    val rows by produceState<List<BrowseRow>?>(null, normalized, lib, favs, progress, type) {
        value = null
        if (normalized.length < 2) { value = emptyList(); return@produceState }
        delay(200)
        value = withContext(Dispatchers.Default) {
            val live = if (type == null || type == ContentType.LIVE)
                lib.liveGroups.asSequence().flatMap { it.second.asSequence() }
                    .filter { it.name.contains(normalized, ignoreCase = true) }.take(60).toList()
                else emptyList()
            val movies = if (type == null || type == ContentType.MOVIE)
                lib.movieGroups.asSequence().flatMap { it.second.asSequence() }
                    .filter { it.name.contains(normalized, ignoreCase = true) }.take(60).toList()
                else emptyList()
            val series = if (type == null || type == ContentType.SERIES)
                lib.series.values.asSequence().filter { it.name.contains(normalized, ignoreCase = true) }.take(60).toList()
                else emptyList()
            buildList {
                if (live.isNotEmpty()) add(BrowseRow("search-live", "Live TV", live.mapIndexed { i, channel ->
                    liveItem(channel, live, i, nav, favs)
                }, CardStyle.CHANNEL))
                if (movies.isNotEmpty()) add(BrowseRow("search-movies", "Movies", movies.map {
                    movieItem(it, nav, lib, progress, favs)
                }, CardStyle.POSTER))
                if (series.isNotEmpty()) add(BrowseRow("search-series", "Series", series.map {
                    seriesItem(it, nav, favs)
                }, CardStyle.POSTER))
            }
        }
    }
    LaunchedEffect(type) { if (!hasBrowsed) inputFocus.safeRequestFocus() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (label == null) "Search" else "Search $label", color = Color.White,
                fontSize = 32.sp, fontWeight = FontWeight.Black)
            if (type != null) TvButton("Back", onClick = { nav.back() })
        }
        Text(if (label == null) "Find channels, movies and series" else "Searching only in $label",
            color = SV.Dim, fontSize = 15.sp, modifier = Modifier.padding(start = 48.dp, top = 6.dp))
        OutlinedTextField(query, onValueChange = { query = it; hasBrowsed = false }, singleLine = true,
            placeholder = { Text(if (label == null) "Channels, movies, series…" else "Search $label…") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                if (!rows.isNullOrEmpty()) scope.launch { resultsFocus.safeRequestFocus() }
            }),
            modifier = Modifier.padding(horizontal = 48.dp, vertical = 16.dp)
                .widthIn(max = 760.dp).fillMaxWidth().focusRequester(inputFocus))
        val resultRows = rows.orEmpty()
        Text(when {
            normalized.length < 2 -> "Type at least 2 characters to search"
            rows == null -> "Searching…"
            resultRows.isEmpty() -> "No matches for \"$normalized\""
            else -> "${resultRows.sumOf { it.items.size }} results · ↓ to browse"
        }, color = SV.Dim, fontSize = 14.sp, modifier = Modifier.padding(start = 48.dp, bottom = 12.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (resultRows.isNotEmpty()) resultStates.SaveableStateProvider("results:$normalized") {
                val resultScroll = rememberLazyListState()
                BrowseScreen(resultRows, resultScroll, "No matching titles",
                    autoFocusResults = hasBrowsed, initialFocusRequester = resultsFocus,
                    onBrowseFocused = { hasBrowsed = true })
            }
        }
    }
}
