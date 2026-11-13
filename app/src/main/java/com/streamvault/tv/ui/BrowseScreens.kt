package com.streamvault.tv.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamvault.tv.data.Channel
import com.streamvault.tv.data.ContentType
import com.streamvault.tv.data.Library
import com.streamvault.tv.data.Progress
import com.streamvault.tv.data.Repo
import com.streamvault.tv.data.Series
import com.streamvault.tv.data.LibraryOrganization
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class BrowseRow(
    val key: String,
    val title: String,
    val items: List<BrowseItem>,
    val style: CardStyle,
    val onViewAll: (() -> Unit)? = null,
    val navigation: NavigationSection? = null,
)

data class NavigationTile(val key: String, val label: String, val onClick: () -> Unit)
data class NavigationSection(val tiles: List<NavigationTile>, val columns: Int, val compact: Boolean = false)

private const val ROW_LIMIT = 30

// ------------------------------------------------------------------ item builders

fun movieItem(ch: Channel, nav: AppNav, lib: Library, progress: Map<String, Progress>, favs: Set<String>) = BrowseItem(
    key = ch.id,
    title = if (Repo.settings.value.optimizeLibrary && !Repo.settings.value.showOriginalNames) LibraryOrganization.clean(ch.name) else ch.name,
    subtitle = ch.group,
    image = ch.artwork,
    progress = progress[ch.id]?.takeIf { it.isInProgress }?.fraction,
    isFavorite = ch.id in favs,
    onClick = { nav.openChannel(ch, lib) },
    onLongClick = { Repo.requestListPicker(ch.id) },
    backdrop = ch.visual?.backdrop, gallery = ch.visual?.gallery.orEmpty(),
    onPlay = { nav.play(listOf(ch), 0, progress[ch.id]?.takeIf { it.isInProgress }?.positionMs ?: 0L) },
    playLabel = if (progress[ch.id]?.isInProgress == true) "Resume" else "Play",
    onDetails = { nav.openChannel(ch, lib) },
    onTrailer = ch.visual?.trailer?.let { { nav.trailer(it) } },
    onToggleList = { Repo.requestListPicker(ch.id) },
    channelId = ch.id,
    summary = ch.visual?.summary, facts = ch.visual?.facts,
)

fun seriesItem(s: Series, nav: AppNav, favs: Set<String>): BrowseItem {
    val seasons = s.seasons.size
    val progress = Repo.progress.value
    val latest = s.episodes.indices.filter { progress[s.episodes[it].id] != null }
        .maxByOrNull { progress[s.episodes[it].id]!!.updatedAt }
    val resumeIndex = when {
        latest == null -> 0
        progress[s.episodes[latest].id]?.isFinished == true && latest < s.episodes.lastIndex -> latest + 1
        else -> latest
    }
    val saved = progress[s.episodes[resumeIndex].id]?.takeIf { it.isInProgress }
    return BrowseItem(
        key = "series:" + s.key,
        title = if (Repo.settings.value.optimizeLibrary && !Repo.settings.value.showOriginalNames) LibraryOrganization.clean(s.name) else s.name,
        subtitle = (if (seasons > 1) "$seasons seasons · " else "") + "${s.episodes.size} episodes",
        image = s.poster,
        isFavorite = s.favoriteId in favs,
        onClick = { if (Repo.settings.value.optimizeLibrary) nav.navigate(Route.CatalogDetails(ContentType.SERIES, LibraryOrganization.titleKey(s.name, s.visual?.facts), s.favoriteId)) else nav.navigate(Route.SeriesDetails(s.key)) },
        onLongClick = { Repo.requestListPicker(s.favoriteId) },
        backdrop = s.visual?.backdrop, gallery = s.visual?.gallery.orEmpty(),
        summary = s.visual?.summary, facts = s.visual?.facts,
        onPlay = { nav.play(s.episodes, resumeIndex, saved?.positionMs ?: 0L) },
        playLabel = if (saved != null) "Resume" else "Play",
        onDetails = { if (Repo.settings.value.optimizeLibrary) nav.navigate(Route.CatalogDetails(ContentType.SERIES, LibraryOrganization.titleKey(s.name, s.visual?.facts), s.favoriteId)) else nav.navigate(Route.SeriesDetails(s.key)) },
        onTrailer = s.visual?.trailer?.let { { nav.trailer(it) } },
        onToggleList = { Repo.requestListPicker(s.favoriteId) },
    )
}

fun liveItem(ch: Channel, queue: List<Channel>, index: Int, nav: AppNav, favs: Set<String>) = BrowseItem(
    key = ch.id,
    title = ch.name,
    subtitle = ch.group,
    image = ch.logo,
    isLive = true,
    isFavorite = ch.id in favs,
    onClick = { nav.play(queue, index) },
    onLongClick = { Repo.requestListPicker(ch.id) },
    playLabel = "Watch live",
    onToggleList = { Repo.requestListPicker(ch.id) },
)

fun liveRow(key: String, title: String, channels: List<Channel>, nav: AppNav, favs: Set<String>, onViewAll: (() -> Unit)? = null) =
    BrowseRow(
        key = key,
        title = title,
        items = channels.take(ROW_LIMIT).mapIndexed { i, ch -> liveItem(ch, channels, i, nav, favs) },
        style = CardStyle.CHANNEL,
        onViewAll = if (channels.size > ROW_LIMIT) onViewAll else null,
    )

fun continueWatchingItems(lib: Library, progress: Map<String, Progress>, nav: AppNav,
    type: ContentType? = null, includeFinished: Boolean = false): List<BrowseItem> {
    val out = ArrayList<BrowseItem>()
    val seenSeries = HashSet<String>()
    val seenMovies = HashSet<String>()
    val optimized = Repo.settings.value.optimizeLibrary
    for ((id, p) in progress.entries.sortedByDescending { it.value.updatedAt }) {
        // Opening an episode without playing must not hide a show's previous saved place.
        if (p.positionMs < 1_000 && !p.isFinished) continue
        val ch = lib.byId[id] ?: continue
        if (type != null && ch.type != type) continue
        val seriesKey = ch.seriesKey
        if (ch.type == ContentType.SERIES && seriesKey != null) {
            if (!seenSeries.add(if (optimized) LibraryOrganization.titleKey(ch.seriesName ?: ch.name, ch.visual?.facts) else seriesKey)) continue
            val s = lib.series[seriesKey] ?: continue
            val idx = s.episodes.indexOfFirst { it.id == ch.id }
            if (p.isFinished) {
                if (idx < 0) continue
                if (idx >= s.episodes.size - 1) {
                    if (includeFinished) out.add(BrowseItem(
                        key = "cw:" + s.key, title = s.name, subtitle = "Completed · ${ch.episodeLabel.orEmpty()}",
                        image = s.poster, progress = 1f,
                        onClick = { nav.navigate(Route.SeriesDetails(s.key)) },
                    ))
                    continue
                }
                val next = s.episodes[idx + 1]
                out.add(
                    BrowseItem(
                        key = "cw:" + s.key, title = s.name,
                        subtitle = "Up next · ${next.episodeLabel ?: next.name}",
                        image = s.poster ?: next.artwork,
                        onClick = { nav.play(s.episodes, idx + 1, 0L) },
                        onLongClick = { nav.navigate(Route.SeriesDetails(s.key)) },
                    )
                )
            } else if (p.isInProgress || (includeFinished && p.positionMs > 0)) {
                out.add(
                    BrowseItem(
                        key = "cw:" + s.key, title = s.name,
                        subtitle = "${ch.episodeLabel ?: ch.name} · ${formatRemaining(p.remainingMs)}",
                        image = s.poster ?: ch.artwork,
                        progress = p.fraction,
                        onClick = { nav.play(s.episodes, idx.coerceAtLeast(0), p.positionMs) },
                        onLongClick = { nav.navigate(Route.SeriesDetails(s.key)) },
                    )
                )
            }
        } else if (ch.type == ContentType.MOVIE && (p.isInProgress || (includeFinished && p.positionMs > 0))) {
            if (!seenMovies.add(if (optimized) LibraryOrganization.titleKey(ch.name, ch.visual?.facts) else ch.id)) continue
            out.add(
                BrowseItem(
                    key = "cw:" + ch.id, title = ch.name,
                    subtitle = if (p.isFinished) "Completed · Watch again" else formatRemaining(p.remainingMs),
                    image = ch.artwork,
                    progress = p.fraction,
                    onClick = { nav.play(listOf(ch), 0, if (p.isFinished) 0L else p.positionMs) },
                    onLongClick = { nav.openChannel(ch, lib) },
                )
            )
        }
    }
    for (i in out.indices) {
        val item = out[i]
        val id = item.key.removePrefix("cw:")
        val series = lib.series[id]
        val movie = lib.byId[id]
        val visual = series?.visual ?: movie?.visual
        val favoriteId = series?.favoriteId ?: movie?.favoriteId
        val catalogKey = if (series != null) LibraryOrganization.titleKey(series.name, series.visual?.facts)
            else movie?.let { LibraryOrganization.titleKey(it.name, it.visual?.facts) }
        val contentType = if (series != null) ContentType.SERIES else ContentType.MOVIE
        val details = if (optimized && catalogKey != null) ({ nav.navigate(Route.CatalogDetails(contentType, catalogKey, favoriteId)) }) else null
        out[i] = item.copy(
            title = if (optimized && !Repo.settings.value.showOriginalNames) LibraryOrganization.clean(item.title) else item.title,
            backdrop = visual?.backdrop, gallery = visual?.gallery.orEmpty(),
            onPlay = if (item.progress == 1f) null else item.onClick,
            playLabel = if (item.subtitle?.startsWith("Up next") == true) "Play next" else "Resume",
            onDetails = details ?: if (series != null) ({ nav.navigate(Route.SeriesDetails(series.key)) })
                else movie?.let { { nav.openChannel(it, lib) } },
            onLongClick = details ?: item.onLongClick,
            onTrailer = visual?.trailer?.let { { nav.trailer(it) } },
            onToggleList = favoriteId?.let { { Repo.requestListPicker(it) } },
            isFavorite = favoriteId in Repo.favorites.value,
            channelId = movie?.id,
            summary = visual?.summary, facts = visual?.facts,
            onVersions = if (optimized && catalogKey != null) ({ nav.navigate(Route.CatalogDetails(contentType, catalogKey, favoriteId, chooseVersions = true)) }) else null,
        )
    }
    return out
}

private fun myListVod(lib: Library, favList: List<String>, progress: Map<String, Progress>, nav: AppNav, favs: Set<String>) =
    favList.mapNotNull { id ->
        if (id.startsWith("series:")) {
            lib.series[id.removePrefix("series:")]?.let { seriesItem(it, nav, favs) }
        } else {
            lib.byId[id]?.takeIf { it.type == ContentType.MOVIE }?.let { movieItem(it, nav, lib, progress, favs) }
        }
    }

// ------------------------------------------------------------------ row sets per section

fun homeRows(lib: Library, favList: List<String>, progress: Map<String, Progress>, recent: List<String>, nav: AppNav): List<BrowseRow> {
    val favs = favList.toHashSet()
    val rows = ArrayList<BrowseRow>()
    val cw = continueWatchingItems(lib, progress, nav)
    if (cw.isNotEmpty()) rows.add(continueWatchingRow(cw, nav))
    val liveHistory = recentlyWatchedLive(lib, recent, nav)
    if (liveHistory.isNotEmpty()) rows.add(continueWatchingRow(liveHistory, nav, ContentType.LIVE, "Continue Watching · Live TV"))

    val myVod = myListVod(lib, favList, progress, nav, favs)
    if (myVod.isNotEmpty()) rows.add(BrowseRow("mylist", "My List", myVod.take(ROW_LIMIT), CardStyle.POSTER))

    val favLive = favList.mapNotNull { lib.byId[it] }.filter { it.type == ContentType.LIVE }
    if (favLive.isNotEmpty()) rows.add(liveRow("favlive", "Favourite Channels", favLive, nav, favs))

    val recentLive = recent.mapNotNull { lib.byId[it] }.filter { it.type == ContentType.LIVE }.take(ROW_LIMIT)
    if (recentLive.isNotEmpty()) {
        rows.add(
            BrowseRow(
                "recentlive", "Recently Watched Channels",
                recentLive.map { ch ->
                    val q = lib.liveQueueFor(ch)
                    liveItem(ch, q, q.indexOfFirst { it.id == ch.id }.coerceAtLeast(0), nav, favs)
                },
                CardStyle.CHANNEL,
            )
        )
    }
    lib.movieGroups.take(6).forEach { (g, items) -> rows.add(movieRow("m:$g", g, items, lib, progress, favs, nav)) }
    lib.seriesGroups.take(6).forEach { (g, items) -> rows.add(seriesRow("s:$g", g, items, favs, nav)) }
    lib.liveGroups.take(4).forEach { (g, items) ->
        rows.add(liveRow("l:$g", g, items, nav, favs) { nav.navigate(Route.Group(g, ContentType.LIVE)) })
    }
    return rows
}

fun movieRow(key: String, group: String, items: List<Channel>, lib: Library, progress: Map<String, Progress>, favs: Set<String>, nav: AppNav) =
    BrowseRow(
        key = key,
        title = group,
        items = items.take(ROW_LIMIT).map { movieItem(it, nav, lib, progress, favs) },
        style = CardStyle.POSTER,
        onViewAll = if (items.size > ROW_LIMIT) ({ nav.navigate(Route.Group(group, ContentType.MOVIE)) }) else null,
    )

fun seriesRow(key: String, group: String, items: List<Series>, favs: Set<String>, nav: AppNav) =
    BrowseRow(
        key = key,
        title = group,
        items = items.take(ROW_LIMIT).map { seriesItem(it, nav, favs) },
        style = CardStyle.POSTER,
        onViewAll = if (items.size > ROW_LIMIT) ({ nav.navigate(Route.Group(group, ContentType.SERIES)) }) else null,
    )

fun movieRows(lib: Library, favList: List<String>, progress: Map<String, Progress>, nav: AppNav): List<BrowseRow> {
    val favs = favList.toHashSet()
    val rows = ArrayList<BrowseRow>()
    val cw = continueWatchingItems(lib, progress, nav, ContentType.MOVIE)
    if (cw.isNotEmpty()) rows.add(continueWatchingRow(cw, nav, ContentType.MOVIE))
    lib.movieGroups.forEach { (g, items) -> rows.add(movieRow("m:$g", g, items, lib, progress, favs, nav)) }
    return rows
}

fun seriesRows(lib: Library, favList: List<String>, progress: Map<String, Progress>, nav: AppNav): List<BrowseRow> {
    val favs = favList.toHashSet()
    val rows = ArrayList<BrowseRow>()
    val cw = continueWatchingItems(lib, progress, nav, ContentType.SERIES)
    if (cw.isNotEmpty()) rows.add(continueWatchingRow(cw, nav, ContentType.SERIES))
    lib.seriesGroups.forEach { (g, items) -> rows.add(seriesRow("s:$g", g, items, favs, nav)) }
    return rows
}

fun myListRows(lib: Library, favList: List<String>, progress: Map<String, Progress>, recent: List<String>, nav: AppNav): List<BrowseRow> {
    val favs = favList.toHashSet()
    val rows = ArrayList<BrowseRow>()
    val vod = myListVod(lib, favList, progress, nav, favs)
    if (vod.isNotEmpty()) rows.add(BrowseRow("mylist", "Movies & Series", vod, CardStyle.POSTER))
    val favLive = favList.mapNotNull { lib.byId[it] }.filter { it.type == ContentType.LIVE }
    if (favLive.isNotEmpty()) {
        rows.add(BrowseRow("favlive", "Channels", favLive.mapIndexed { i, ch -> liveItem(ch, favLive, i, nav, favs) }, CardStyle.CHANNEL))
    }
    val cw = continueWatchingItems(lib, progress, nav)
    if (cw.isNotEmpty()) rows.add(continueWatchingRow(cw, nav))
    return rows
}

// ------------------------------------------------------------------ composables

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ContentRow(row: BrowseRow, onFocused: (BrowseItem) -> Unit, firstFocus: FocusRequester? = null,
    onFocusTarget: (FocusRequester) -> Unit = {}, onActivity: () -> Unit = {}, onUp: (() -> Unit)? = null,
    navigationEnabled: Boolean = true, restoreItemKey: String? = null,
    rowEntryFocus: FocusRequester? = null, onDown: (() -> Unit)? = null,
    onItemFocusedKey: (String) -> Unit = {},
    registerRowEntry: ((suspend () -> Unit)?) -> Unit = {},
    onNavigationFocus: (String, FocusRequester) -> Unit = { _, _ -> }) {
    row.navigation?.let {
        NavigationSectionGrid(row.title, it, firstFocus ?: rowEntryFocus, navigationEnabled, restoreItemKey, onNavigationFocus, onUp, onDown)
        return
    }
    val restoreIndex = if (restoreItemKey == "__view_all" && row.onViewAll != null) row.items.size
        else row.items.indexOfFirst { it.key == restoreItemKey }.takeIf { it >= 0 } ?: 0
    val rowState = rememberLazyListState(initialFirstVisibleItemIndex = restoreIndex)
    val count = row.items.size + if (row.onViewAll != null) 1 else 0
    val requesters = remember(row.key, count) { List(count) { FocusRequester() } }
    val scope = rememberCoroutineScope()
    var focusedIndex by remember(row.key) { mutableIntStateOf(0) }
    var navigationJob by remember { mutableStateOf<Job?>(null) }
    val enterRow by rememberUpdatedState<suspend () -> Unit>({
        // A remembered poster may be outside this LazyRow's composed viewport.
        // Place it first, then request focus; otherwise vertical navigation can stall.
        navigationJob?.cancel()
        if (restoreIndex in requesters.indices) {
            if (rowState.layoutInfo.visibleItemsInfo.none { it.index == restoreIndex }) {
                rowState.scrollToItem(restoreIndex)
            }
            requesters[restoreIndex].safeRequestFocus()
        }
    })
    DisposableEffect(row.key, requesters) {
        registerRowEntry { enterRow() }
        onDispose {
            registerRowEntry(null)
            navigationJob?.cancel()
        }
    }
    LaunchedEffect(rowState.isScrollInProgress) { if (rowState.isScrollInProgress) onActivity() }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(
            row.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 48.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        LazyRow(
            state = rowState,
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                if (!navigationEnabled) return@onPreviewKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.key == Key.DirectionUp && onUp != null) {
                    navigationJob?.cancel()
                    onUp()
                    return@onPreviewKeyEvent true
                }
                if (event.key == Key.DirectionDown && onDown != null) {
                    navigationJob?.cancel()
                    onDown()
                    return@onPreviewKeyEvent true
                }
                val delta = when (event.key) {
                    Key.DirectionRight -> 1
                    Key.DirectionLeft -> -1
                    else -> {
                        navigationJob?.cancel()
                        return@onPreviewKeyEvent false
                    }
                }
                val next = focusedIndex + delta
                if (next !in requesters.indices) {
                    navigationJob?.cancel()
                    // Left from the first card exits to the navigation rail.
                    return@onPreviewKeyEvent delta > 0
                }
                focusedIndex = next
                navigationJob?.cancel()
                navigationJob = scope.launch {
                    if (rowState.layoutInfo.visibleItemsInfo.none { it.index == next }) {
                        rowState.scrollToItem(next)
                        withFrameNanos { }
                        withFrameNanos { }
                    }
                    requesters[next].requestFocus()
                    if (row.style == CardStyle.POSTER && next < row.items.size) {
                        // Let the newly focused card reserve its wider slot before aligning the row.
                        withFrameNanos { }
                        rowState.animateScrollToItem(next)
                    }
                }
                true
            }.focusGroup(),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(row.items, key = { _, it -> it.key }) { i, item ->
                val entry = Modifier.focusProperties { canFocus = navigationEnabled }.onFocusChanged { if (it.isFocused) {
                    focusedIndex = i
                    onItemFocusedKey(item.key)
                    onFocusTarget(requesters[i])
                } }
                    .focusRequester(requesters[i])
                val m = entry
                    .then(if (i == restoreIndex && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
                    .then(if (i == restoreIndex && rowEntryFocus != null) Modifier.focusRequester(rowEntryFocus) else Modifier)
                when (row.style) {
                    CardStyle.POSTER -> PosterCard(item, m, onFocused, expandInRow = true)
                    CardStyle.LANDSCAPE -> LandscapeCard(item, m.width(260.dp), onFocused)
                    CardStyle.CHANNEL -> ChannelCard(item, m.width(200.dp), onFocused)
                }
            }
            val viewAll = row.onViewAll
            if (viewAll != null) {
                item(key = "__view_all") {
                    val entry = Modifier.focusProperties { canFocus = navigationEnabled }.onFocusChanged { if (it.isFocused) {
                        focusedIndex = row.items.size
                        onItemFocusedKey("__view_all")
                        onFocusTarget(requesters[row.items.size])
                    } }
                        .focusRequester(requesters[row.items.size])
                    val m = entry
                        .then(if (restoreIndex == row.items.size && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
                        .then(if (restoreIndex == row.items.size && rowEntryFocus != null) Modifier.focusRequester(rowEntryFocus) else Modifier)
                    ViewAllCard(row.style, viewAll, m)
                }
            }
        }

    }
}

@Composable
fun GridScreen(title: String, items: List<BrowseItem>, style: CardStyle) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.safeRequestFocus() }
    Column(Modifier.fillMaxSize().background(SV.Bg)) {
        Text(
            title, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.padding(start = 48.dp, top = 32.dp, bottom = 4.dp),
        )
        Text("${items.size} titles", color = SV.Dim, modifier = Modifier.padding(start = 48.dp, bottom = 8.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (style == CardStyle.POSTER) 140.dp else 200.dp),
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 16.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(items, key = { _, it -> it.key }) { i, item ->
                val m = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier
                when (style) {
                    CardStyle.POSTER -> PosterCard(item, m.fillMaxWidth())
                    CardStyle.LANDSCAPE -> LandscapeCard(item, m.fillMaxWidth())
                    CardStyle.CHANNEL -> ChannelCard(item, m.fillMaxWidth())
                }
            }
        }
    }
}

/** "View all" page for one group. */
@Composable
fun GroupScreen(route: Route.Group) {
    val nav = LocalNav.current
    val lib by Repo.library.collectAsState()
    val favList by Repo.favorites.collectAsState()
    val progress by Repo.progress.collectAsState()
    val favs = remember(favList) { favList.toHashSet() }
    val (items, style) = remember(lib, favs, progress, route) {
        when (route.type) {
            ContentType.MOVIE -> {
                val list = lib.movieGroups.firstOrNull { it.first == route.title }?.second.orEmpty()
                list.map { movieItem(it, nav, lib, progress, favs) } to CardStyle.POSTER
            }
            ContentType.SERIES -> {
                val list = lib.seriesGroups.firstOrNull { it.first == route.title }?.second.orEmpty()
                list.map { seriesItem(it, nav, favs) } to CardStyle.POSTER
            }
            ContentType.LIVE -> {
                val list = lib.liveByGroup[route.title].orEmpty()
                list.mapIndexed { i, ch -> liveItem(ch, list, i, nav, favs) } to CardStyle.CHANNEL
            }
        }
    }
    GridScreen(route.title, items, style)
}
