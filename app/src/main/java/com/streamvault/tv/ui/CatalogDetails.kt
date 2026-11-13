package com.streamvault.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.streamvault.tv.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CatalogDetailsScreen(route: Route.CatalogDetails) {
    val lib by Repo.library.collectAsState()
    val settings by Repo.settings.collectAsState()
    val progress by Repo.progress.collectAsState()
    val favorites by Repo.favorites.collectAsState()
    val nav = LocalNav.current
    val title by produceState<CatalogTitle?>(null, lib.revision, route.key) {
        value = withContext(Dispatchers.Default) { LibraryOrganization.catalog(lib, route.type).firstOrNull { it.key == route.key } }
    }
    val current = title
    if (current == null) { EmptyState("Loading title…"); return }
    var selectedId by rememberSaveable(route.key) { mutableStateOf(route.versionId) }
    val version = current.versions.firstOrNull { it.id == selectedId } ?: LibraryOrganization.choose(current.versions, settings)
    val item = catalogItem(current, version, nav, settings)
    var versionsOpen by remember { mutableStateOf(route.chooseVersions) }
    var detail by remember(version.id) { mutableStateOf<TitleArtwork?>(version.visual) }
    LaunchedEffect(version.id) { version.movie?.let { detail = Repo.heroArtwork(it.id) ?: version.visual } }
    val episodes = version.series?.episodes.orEmpty()
    if (version.series != null) {
        key(version.id) {
            CompactSeriesDetails(version.id, if (settings.showOriginalNames) version.sourceName else current.name,
                version.poster, listOf(version.language, LibraryOrganization.qualityLabel(version.quality),
                    version.provider.label).joinToString(" · "), detail?.facts, detail?.summary, episodes, progress, autoFocus = !versionsOpen) {
                TvButton("Versions (${current.versions.size})", small = true, onClick = { versionsOpen = true })
                item.onToggleList?.let { TvButton(if (item.isFavorite) "In My List" else "My List", small = true, onClick = it) }
                detail?.trailer?.let { TvButton("Trailer", small = true, onClick = { nav.trailer(it) }) }
            }
        }
        if (versionsOpen) VersionsDialog(current, version.id, onDismiss = { versionsOpen = false }) {
            selectedId = it.id; versionsOpen = false
        }
        return
    }
    val watched = remember(progress, version.id) { (version.movie?.let { listOf(it) } ?: episodes).mapNotNull { Repo.progressFor(it) }.maxByOrNull { it.updatedAt } }
    val listState = rememberLazyListState()
    val playFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var headerMove by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { headerMove?.cancel() } }
    LaunchedEffect(version.id) {
        if (!versionsOpen) playFocus.safeRequestFocus()
    }
    fun revealHeader() {
        if (headerMove?.isActive == true) return
        headerMove = scope.launch {
            listState.animateScrollToItem(0)
        }
    }
    Box(Modifier.fillMaxSize().background(SV.Bg)) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    ArtImage(version.poster, current.name, Modifier.width(190.dp).height(285.dp).clip(RoundedCornerShape(12.dp)), titlePlaceholder = true)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (settings.showOriginalNames) version.sourceName else current.name, color = Color.White,
                            fontSize = 36.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOf(version.language, LibraryOrganization.qualityLabel(version.quality), version.provider.label,
                            "${current.versions.size} versions").joinToString(" · "), color = SV.Dim, fontSize = 15.sp)
                        detail?.facts?.let { Text(it, color = Color.White, fontSize = 15.sp) }
                        detail?.summary?.let { Text(it, color = SV.Dim, fontSize = 16.sp, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                        Row(Modifier.onFocusChanged {
                            if (it.hasFocus && (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0)) {
                                revealHeader()
                            }
                        }.focusGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TvButton(if (watched?.isInProgress == true) "Resume" else "Play", primary = true,
                                modifier = Modifier.focusRequester(playFocus), onClick = { playCatalogVersion(version, nav) })
                            TvButton("Versions (${current.versions.size})", onClick = { versionsOpen = true })
                            item.onToggleList?.let { TvButton(if (item.isFavorite) "In My List" else "My List", onClick = it) }
                            detail?.trailer?.let { TvButton("Trailer", onClick = { nav.trailer(it) }) }
                        }
                    }
                }
            }
        }
    }
    if (versionsOpen) VersionsDialog(current, version.id, onDismiss = { versionsOpen = false }) { selectedId = it.id; versionsOpen = false }
}

@Composable
private fun VersionsDialog(title: CatalogTitle, selectedId: String, onDismiss: () -> Unit, onPick: (CatalogVersion) -> Unit) {
    var language by remember { mutableStateOf("Any") }
    var quality by remember { mutableIntStateOf(-1) }
    val versions = title.versions.filter { (language == "Any" || it.language == language) && (quality == -1 || it.quality == quality) }
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.widthIn(max = 950.dp).fillMaxWidth(0.92f).heightIn(max = 640.dp)
            .glassPanel(RoundedCornerShape(22.dp)).padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Choose a version", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                TvButton("Close", onClick = onDismiss)
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                items(listOf("Any") + title.versions.map { it.language }.distinct().sorted()) { l -> TvButton(l, primary = language == l, onClick = { language = l }) }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 14.dp)) {
                item { TvButton("Any quality", primary = quality == -1, onClick = { quality = -1 }) }
                items(title.versions.map { it.quality }.distinct().sortedDescending()) { q -> TvButton(LibraryOrganization.qualityLabel(q), primary = quality == q, onClick = { quality = q }) }
            }
            if (versions.isEmpty()) Text("No versions match these filters", color = SV.Dim)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(versions, key = { it.id }) { version ->
                    FocusCard(onClick = { onPick(version) }, modifier = Modifier.fillMaxWidth(), focusedScale = 1.01f,
                        background = if (version.id == selectedId) SV.Surface2 else SV.Bg) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${version.language} · ${LibraryOrganization.qualityLabel(version.quality)} · ${version.provider.label}" + if (version.id == selectedId) " · Selected" else "",
                                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(version.sourceName, color = SV.Dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(version.sourceGroup, color = SV.Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Text("Audio-track availability is confirmed during playback. Your saved position follows the matching movie or episode.", color = SV.Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
fun CatalogGroupScreen(route: Route.CatalogGroup) {
    val lib by Repo.library.collectAsState()
    val settings by Repo.settings.collectAsState()
    val favorites by Repo.favorites.collectAsState()
    val progress by Repo.progress.collectAsState()
    val nav = LocalNav.current
    var languages by rememberSaveable(route) { mutableStateOf(route.languages) }
    var qualities by rememberSaveable(route) { mutableStateOf(route.qualities) }
    var ratings by rememberSaveable(route) { mutableStateOf(route.ratings) }
    var sort by rememberSaveable(route) { mutableStateOf(route.sort) }
    var filtersOpen by remember { mutableStateOf(false) }
    val filter = CatalogFilter(CatalogProvider.valueOf(route.provider), route.genre, languages, qualities, ratings, sort)
    val scoped by produceState<List<Pair<CatalogTitle, List<CatalogVersion>>>?>(null, lib.revision, route) {
        value = withContext(Dispatchers.Default) {
            catalogSelection(LibraryOrganization.catalog(lib, route.type),
                CatalogFilter(CatalogProvider.valueOf(route.provider), route.genre))
        }
    }
    val watched by produceState<Map<String, Progress>>(emptyMap(), lib.revision, progress, route.type, route.provider) {
        value = withContext(Dispatchers.Default) {
            val latest = HashMap<String, Progress>()
            for ((id, saved) in progress) {
                val channel = lib.byId[id] ?: continue
                if (channel.type != route.type || !LibraryOrganization.providerMatches(
                        LibraryOrganization.provider(channel.group), filter.provider)) continue
                val key = LibraryOrganization.titleKey(channel.seriesName ?: channel.name, channel.visual?.facts)
                if (saved.updatedAt > (latest[key]?.updatedAt ?: Long.MIN_VALUE)) latest[key] = saved
            }
            latest
        }
    }
    val entries by produceState<List<Pair<CatalogTitle, List<CatalogVersion>>>?>(null, scoped, filter, settings, favorites, watched, route.continueOnly) {
        val base = scoped ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val selected = catalogSelection(base.map { it.first }, filter)
            if (route.continueOnly) selected.filter { watched[it.first.key]?.isInProgress == true }
                .sortedByDescending { watched[it.first.key]?.updatedAt } else selected
        }
    }
    val firstFocus = remember { FocusRequester() }
    val controlsFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    var focusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val filterKey = "$languages|$qualities|$ratings|$sort"
    var lastFilterKey by rememberSaveable { mutableStateOf(filterKey) }
    LaunchedEffect(languages, qualities, ratings, sort) {
        if (lastFilterKey != filterKey) {
            gridState.scrollToItem(0)
            focusedKey = null
            lastFilterKey = filterKey
        }
    }
    val titles = entries
    val providerLanding = route.genre == null && filter.provider != CatalogProvider.ALL && !route.gridOnly
    val controls: @Composable () -> Unit = {
        TvButton("Filters & sort", onClick = { filtersOpen = true })
        ScopedSearchButton(route.type, filter)
        TvButton("Back", onClick = { nav.back() })
    }
    LaunchedEffect(titles != null, providerLanding) {
        if (titles != null && !providerLanding) firstFocus.safeRequestFocus()
    }
    if (providerLanding) {
        ProviderBrowseScreen(route.type, entries, watched, filter, settings, favorites, controls, autoFocusResults = !filtersOpen)
    } else Column(Modifier.fillMaxSize().background(SV.Bg)) {
        Row(Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (route.continueOnly) "Continue Watching · ${filter.provider.label}" else route.genre ?: filter.provider.label, color = Color.White, fontSize = 30.sp,
                    fontWeight = FontWeight.Bold)
                Text("${titles?.size ?: 0} titles · ${languages.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "All languages"}",
                    color = SV.Dim, modifier = Modifier.padding(top = 6.dp))
            }
            Row(Modifier.focusRequester(controlsFocus).focusGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                controls()
            }
        }
        if (titles == null) EmptyState("Loading titles…")
        else if (titles.isEmpty()) EmptyState("No matching titles", "Try another language or clear your filters.")
        else LazyVerticalGrid(state = gridState, columns = GridCells.Adaptive(140.dp), modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(48.dp), horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            itemsIndexed(titles, key = { _, entry -> entry.first.key }, contentType = { _, _ -> "catalog-title" }) { index, (title, versions) ->
                PosterCard(catalogItem(title, LibraryOrganization.choose(versions, settings), nav, settings, watched[title.key]),
                    Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                        val topRow = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.row == 0
                        if (topRow && event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown) {
                            if (event.nativeKeyEvent.repeatCount == 0) scope.launch { controlsFocus.safeRequestFocus() }
                            true
                        } else false
                    }.then(if (title.key == focusedKey || (focusedKey == null && index == gridState.firstVisibleItemIndex)) Modifier.focusRequester(firstFocus) else Modifier),
                    onFocused = { focusedKey = title.key })
            }
        }
    }
    if (filtersOpen) CatalogFilters(scoped.orEmpty().map { it.first }, filter, onDismiss = { filtersOpen = false }) {
        languages = it.languages; qualities = it.qualities; ratings = it.ratings; sort = it.sort
    }
}
