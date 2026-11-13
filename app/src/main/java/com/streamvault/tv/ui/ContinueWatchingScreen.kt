package com.streamvault.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamvault.tv.data.ContentType
import com.streamvault.tv.data.Library
import com.streamvault.tv.data.Repo

private const val CONTINUE_PREVIEW_LIMIT = 12

fun continueWatchingRow(items: List<BrowseItem>, nav: AppNav, type: ContentType? = null,
    title: String = "Continue Watching") = BrowseRow(
    key = "continue:${type?.name ?: "all"}", title = title,
    items = items.take(CONTINUE_PREVIEW_LIMIT), style = CardStyle.LANDSCAPE,
    onViewAll = { nav.navigate(Route.ContinueWatching(type)) },
)

fun recentlyWatchedLive(lib: Library, recent: List<String>, nav: AppNav): List<BrowseItem> =
    recent.mapNotNull { lib.byId[it]?.takeIf { channel -> channel.type == ContentType.LIVE } }
        .map { channel ->
            val queue = lib.liveQueueFor(channel)
            BrowseItem(
                key = "recent-live:${channel.id}", title = channel.name,
                subtitle = "Watch live · ${channel.group}", image = channel.logo, isLive = true,
                onClick = { nav.play(queue, queue.indexOfFirst { it.id == channel.id }.coerceAtLeast(0)) },
                onLongClick = { Repo.requestListPicker(channel.id) },
                playLabel = "Watch live",
                isFavorite = channel.id in Repo.favorites.value,
                onToggleList = { Repo.requestListPicker(channel.id) },
            )
        }

/** One saved place per show, with completed titles retained in a separate watch-history section. */
@Composable
fun ContinueWatchingScreen(initialType: ContentType? = null) {
    val lib by Repo.library.collectAsState()
    val progress by Repo.progress.collectAsState()
    val recent by Repo.recent.collectAsState()
    val nav = LocalNav.current
    var selected by rememberSaveable(initialType) { mutableStateOf(initialType?.name ?: "ALL") }
    val filters = listOf("ALL" to "All", "MOVIE" to "Movies", "SERIES" to "Series", "LIVE" to "Live TV")
    val movies = remember(lib, progress, nav) { continueWatchingItems(lib, progress, nav, ContentType.MOVIE, includeFinished = true) }
    val series = remember(lib, progress, nav) { continueWatchingItems(lib, progress, nav, ContentType.SERIES, includeFinished = true) }
    val live = remember(lib, recent, nav) { recentlyWatchedLive(lib, recent, nav) }
    val sections = remember(selected, movies, series, live) {
        buildList {
            for ((type, title, entries) in listOf(Triple("MOVIE", "Movies", movies), Triple("SERIES", "Series", series))) {
                if (selected != "ALL" && selected != type) continue
                val (completed, active) = entries.partition { it.subtitle?.startsWith("Completed") == true }
                if (active.isNotEmpty()) add("Continue Watching · $title" to active)
                if (completed.isNotEmpty()) add("Watch Again · $title" to completed)
            }
            if ((selected == "ALL" || selected == "LIVE") && live.isNotEmpty()) add("Recently Watched · Live TV" to live)
        }
    }
    val firstFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { firstFocus.safeRequestFocus() }
    Column(Modifier.fillMaxSize().background(SV.Bg)) {
        Column(Modifier.padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 12.dp)) {
            Text("Continue Watching", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black)
            Text("Pick up where you left off, or revisit something you watched.", color = SV.Dim, fontSize = 15.sp)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                filters.forEachIndexed { index, (type, label) ->
                    TvButton(label, onClick = { selected = type }, primary = selected == type,
                        modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                }
            }
        }
        if (sections.isEmpty()) {
            EmptyState("Nothing watched yet", "Start a movie, episode or live channel and it will appear here.")
        } else key(selected) {
            val state = rememberLazyListState()
            LazyColumn(state = state, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((heading, entries) in sections) {
                    item(key = "heading:$heading") {
                        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(heading, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(12.dp))
                            Text("${entries.size}", color = SV.Dim, fontSize = 14.sp)
                        }
                    }
                    items(entries, key = { "$heading:${it.key}" }, contentType = { "watch-history" }) { item ->
                        WatchHistoryRow(item, Modifier.onPreviewKeyEvent { event ->
                            val first = heading == sections.first().first && item.key == entries.firstOrNull()?.key
                            if (first && event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown) {
                                if (event.nativeKeyEvent.repeatCount == 0) scope.launch { firstFocus.safeRequestFocus() }
                                true
                            } else false
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchHistoryRow(item: BrowseItem, modifier: Modifier = Modifier) {
    FocusCard(item.onClick, onLongClick = item.onLongClick,
        modifier = modifier.fillMaxWidth(), focusedScale = 1.015f) { focused ->
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ArtImage(item.image, item.title, Modifier.width(80.dp).height(108.dp).clip(RoundedCornerShape(6.dp)),
                contentScale = if (item.isLive) ContentScale.Fit else ContentScale.Crop, titlePlaceholder = true)
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(item.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text(item.subtitle.orEmpty(), color = SV.Dim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                item.progress?.let {
                    Spacer(Modifier.height(14.dp))
                    ProgressLine(it, Modifier.widthIn(max = 400.dp).fillMaxWidth())
                }
            }
            Spacer(Modifier.width(16.dp))
            Text(when {
                item.isLive -> "Watch live"
                item.subtitle?.startsWith("Up next") == true -> "Play next"
                item.progress == 1f -> if (item.onLongClick == null) "View series" else "Watch again"
                else -> "Resume"
            },
                color = if (focused) Color.White else SV.Dim, fontSize = 14.sp,
                modifier = Modifier.padding(end = 14.dp))
        }
    }
}
