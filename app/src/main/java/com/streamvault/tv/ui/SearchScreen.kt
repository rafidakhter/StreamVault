package com.streamvault.tv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
fun ScopedSearchButton(type: ContentType, filter: CatalogFilter? = null) {
    val nav = LocalNav.current
    TvButton("Search", icon = Icons.Rounded.Search, onClick = { nav.navigate(Route.Search(type, filter = filter)) })
}

/** Search the relevant catalog off the UI thread; series are searched once per show. */
@Composable
fun SearchScreen(lib: Library, favList: List<String>, type: ContentType? = null, categoryFilter: CatalogFilter? = null) {
    val nav = LocalNav.current
    val progress by Repo.progress.collectAsState()
    val settings by Repo.settings.collectAsState()
    var query by rememberSaveable(type) { mutableStateOf("") }
    var hasBrowsed by rememberSaveable(type) { mutableStateOf(false) }
    val resultStates = rememberSaveableStateHolder()
    val normalized = query.trim()
    val favs = remember(favList) { favList.toHashSet() }
    val inputFocus = remember { FocusRequester() }
    val resultsFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val label = when (type) {
        ContentType.MOVIE -> "Movies"
        ContentType.SERIES -> "Series"
        ContentType.LIVE -> "Live TV"
        null -> null
    }
    val categoryLabel = categoryFilter?.let { filter ->
        listOfNotNull(filter.provider.takeUnless { it == com.streamvault.tv.data.CatalogProvider.ALL }?.label,
            filter.genre).joinToString(" · ").ifBlank { "All" }
    }
    val searchLabel = listOfNotNull(categoryLabel, label).joinToString(" · ")
    val organized = settings.optimizeLibrary || categoryFilter != null
    val rows by produceState<List<BrowseRow>?>(null, normalized, lib, favs, progress, type, settings, categoryFilter) {
        value = null
        if (normalized.length < 2) { value = emptyList(); return@produceState }
        delay(200)
        value = withContext(Dispatchers.Default) {
            val live = if (type == null || type == ContentType.LIVE)
                lib.liveGroups.asSequence().flatMap { it.second.asSequence() }
                    .filter { it.name.contains(normalized, ignoreCase = true) }.take(60).toList()
                else emptyList()
            val movies = if (!organized && (type == null || type == ContentType.MOVIE))
                lib.movieGroups.asSequence().flatMap { it.second.asSequence() }
                    .filter { it.name.contains(normalized, ignoreCase = true) }.take(60).toList()
                else emptyList()
            val series = if (!organized && (type == null || type == ContentType.SERIES))
                lib.series.values.asSequence().filter { it.name.contains(normalized, ignoreCase = true) }.take(60).toList()
                else emptyList()
            buildList {
                if (organized) {
                    for (contentType in listOf(ContentType.MOVIE, ContentType.SERIES)) {
                        if (type != null && type != contentType) continue
                        val matches = com.streamvault.tv.data.LibraryOrganization.catalog(lib, contentType).asSequence()
                            .filter { it.name.contains(normalized, ignoreCase = true) }.toList()
                        val selected = if (categoryFilter != null) catalogSelection(matches, categoryFilter)
                            else matches.map { it to it.versions }
                        val titles = selected.take(60).map { (title, versions) ->
                            val version = com.streamvault.tv.data.LibraryOrganization.choose(versions, settings)
                            catalogItem(title, version, nav, settings, version.movie?.let { Repo.progressFor(it) })
                        }
                        if (titles.isNotEmpty()) add(BrowseRow("search-${contentType.name}", if (contentType == ContentType.MOVIE) "Movies" else "Series", titles, CardStyle.POSTER))
                    }
                } else {
                if (movies.isNotEmpty()) add(BrowseRow("search-movies", "Movies", movies.map {
                    movieItem(it, nav, lib, progress, favs)
                }, CardStyle.POSTER))
                if (series.isNotEmpty()) add(BrowseRow("search-series", "Series", series.map {
                    seriesItem(it, nav, favs)
                }, CardStyle.POSTER))
                }
                if (live.isNotEmpty()) add(BrowseRow("search-live", "Live TV", live.mapIndexed { i, channel ->
                    liveItem(channel, live, i, nav, favs)
                }, CardStyle.CHANNEL))
            }
        }
    }
    LaunchedEffect(type) { if (!hasBrowsed) inputFocus.safeRequestFocus() }
    Column(Modifier.fillMaxSize().focusGroup()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        OutlinedTextField(query, onValueChange = { query = it; hasBrowsed = false }, singleLine = true,
            placeholder = { Text(if (label == null) "Search movies, series, live TV…" else "Search $searchLabel…") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                if (!rows.isNullOrEmpty()) {
                    keyboard?.hide()
                    scope.launch { resultsFocus.safeRequestFocus() }
                }
            }),
            modifier = Modifier.weight(1f)
                .focusProperties { if (!rows.isNullOrEmpty()) down = resultsFocus }
                .focusRequester(inputFocus))
            if (type != null) TvButton("Back", onClick = { nav.back() })
        }
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
                BrowseScreen(resultRows, resultScroll, "No matching titles", showHero = false,
                    autoFocusResults = hasBrowsed, initialFocusRequester = resultsFocus,
                    onBrowseFocused = { hasBrowsed = true; keyboard?.hide() },
                    onUpFromFirstRow = { scope.launch { inputFocus.safeRequestFocus() } })
            }
        }
    }
}
