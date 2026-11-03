package com.streamvault.tv.ui

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
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

data class BrowseRow(
    val key: String,
    val title: String,
    val items: List<BrowseItem>,
    val style: CardStyle,
    val onViewAll: (() -> Unit)? = null,
)

private const val ROW_LIMIT = 30

// ------------------------------------------------------------------ item builders

fun movieItem(ch: Channel, nav: AppNav, lib: Library, progress: Map<String, Progress>, favs: Set<String>) = BrowseItem(
    key = ch.id,
    title = ch.name,
    subtitle = ch.group,
    image = ch.artwork,
    progress = progress[ch.id]?.takeIf { it.isInProgress }?.fraction,
    isFavorite = ch.id in favs,
    onClick = { nav.openChannel(ch, lib) },
    onLongClick = { Repo.toggleFavorite(ch.id) },
)

fun seriesItem(s: Series, nav: AppNav, favs: Set<String>): BrowseItem {
    val seasons = s.seasons.size
    return BrowseItem(
        key = "series:" + s.key,
        title = s.name,
        subtitle = (if (seasons > 1) "$seasons seasons · " else "") + "${s.episodes.size} episodes",
        image = s.poster,
        isFavorite = s.favoriteId in favs,
        onClick = { nav.navigate(Route.SeriesDetails(s.key)) },
        onLongClick = { Repo.toggleFavorite(s.favoriteId) },
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
    onLongClick = { Repo.toggleFavorite(ch.id) },
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
    for ((id, p) in progress.entries.sortedByDescending { it.value.updatedAt }) {
        val ch = lib.byId[id] ?: continue
        if (type != null && ch.type != type) continue
        val seriesKey = ch.seriesKey
        if (ch.type == ContentType.SERIES && seriesKey != null) {
            if (!seenSeries.add(seriesKey)) continue
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

@Composable
fun Hero(item: BrowseItem?, modifier: Modifier = Modifier) {
    var artwork by remember { mutableStateOf(item?.image.orEmpty()) }
    LaunchedEffect(item?.image) {
        kotlinx.coroutines.delay(180)
        artwork = item?.image.orEmpty()
    }
    Box(modifier) {
        if (item == null) return@Box
        Box(Modifier.matchParentSize()) {
            val image = artwork
            Box(Modifier.fillMaxSize()) {
                if (image.isNotBlank()) {
                    AsyncImage(
                        model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                            .data(image).size(960, 540)
                            .bitmapConfig(android.graphics.Bitmap.Config.RGB_565).build(),
                        contentDescription = null,
                        contentScale = if (item.isLive) ContentScale.Fit else ContentScale.Crop,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .fillMaxHeight()
                            .fillMaxWidth(if (item.isLive) 0.35f else 0.62f)
                            .padding(if (item.isLive) 40.dp else 0.dp)
                            .then(if (Build.VERSION.SDK_INT >= 31 && !item.isLive) Modifier.blur(2.dp) else Modifier)
                            .alpha(0.85f),
                    )
                }
            }
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.horizontalGradient(0f to SV.Bg, 0.42f to SV.Bg.copy(alpha = 0.88f), 0.75f to Color.Transparent)
            )
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(0.55f to Color.Transparent, 1f to SV.Bg)
            )
        )
        Column(
            Modifier.align(Alignment.CenterStart).padding(start = 48.dp, top = 24.dp).fillMaxWidth(0.55f)
        ) {
            if (item.isLive) {
                LiveBadge()
                Spacer(Modifier.height(10.dp))
            }
            Text(
                item.title, color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Black,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 42.sp,
            )
            if (item.subtitle != null) {
                Spacer(Modifier.height(8.dp))
                Text(item.subtitle, color = SV.Dim, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (item.progress != null) {
                Spacer(Modifier.height(12.dp))
                ProgressLine(item.progress, Modifier.width(220.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (item.isLive) "OK to watch · hold OK to add to favourites" else "OK for details · hold OK to add to My List",
                color = SV.Dim.copy(alpha = 0.7f), fontSize = 13.sp,
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ContentRow(row: BrowseRow, onFocused: (BrowseItem) -> Unit, firstFocus: FocusRequester? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(
            row.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 48.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(row.items, key = { _, it -> it.key }) { i, item ->
                val m = if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier
                when (row.style) {
                    CardStyle.POSTER -> PosterCard(item, m.width(140.dp), onFocused)
                    CardStyle.LANDSCAPE -> LandscapeCard(item, m.width(260.dp), onFocused)
                    CardStyle.CHANNEL -> ChannelCard(item, m.width(200.dp), onFocused)
                }
            }
            val viewAll = row.onViewAll
            if (viewAll != null) {
                item(key = "__view_all") { ViewAllCard(row.style, viewAll) }
            }
        }
    }
}

@Composable
fun BrowseScreen(
    rows: List<BrowseRow>,
    listState: LazyListState,
    emptyTitle: String,
    emptyMessage: String? = null,
    showHero: Boolean = true,
) {
    if (rows.isEmpty()) {
        EmptyState(emptyTitle, emptyMessage)
        return
    }
    var hero by remember { mutableStateOf(rows.firstOrNull()?.items?.firstOrNull()) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.safeRequestFocus() }
    Column(Modifier.fillMaxSize()) {
        if (showHero) Hero(hero, Modifier.fillMaxWidth().height(270.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(top = if (showHero) 0.dp else 24.dp, bottom = 64.dp),
        ) {
            itemsIndexed(rows, key = { _, r -> r.key }) { index, row ->
                ContentRow(row, onFocused = { hero = it }, firstFocus = if (index == 0) firstFocus else null)
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
