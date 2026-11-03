package com.streamvault.tv.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamvault.tv.data.Channel
import com.streamvault.tv.data.Repo

@Composable
private fun Backdrop(image: String?) {
    Box(Modifier.fillMaxSize().background(SV.Bg)) {
        if (!image.isNullOrBlank()) {
            AsyncImage(
                model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(image).size(960, 540)
                    .bitmapConfig(android.graphics.Bitmap.Config.RGB_565).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(24.dp) else Modifier)
                    .alpha(0.35f),
            )
        }
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to SV.Bg, 0.6f to SV.Bg.copy(alpha = 0.7f), 1f to Color.Transparent)))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to SV.Bg)))
    }
}

@Composable
fun MovieDetailsScreen(id: String) {
    val nav = LocalNav.current
    val lib by Repo.library.collectAsState()
    val progressMap by Repo.progress.collectAsState()
    val favList by Repo.favorites.collectAsState()
    val ch = lib.byId[id]
    if (ch == null) {
        EmptyState("Title not available", "It may have been removed from your playlist.")
        return
    }
    val progress = progressMap[id]
    val canResume = progress?.isInProgress == true
    val inList = ch.id in favList
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { playFocus.safeRequestFocus() }
    val favs = remember(favList) { favList.toHashSet() }
    val similar = remember(lib, ch, favs) {
        lib.movieGroups.firstOrNull { it.first == ch.group }?.second.orEmpty()
            .filter { it.id != ch.id }.take(24)
            .map { movieItem(it, nav, lib, progressMap, favs) }
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(ch.artwork)
        Column(Modifier.fillMaxSize().padding(top = 48.dp)) {
            Row(Modifier.padding(horizontal = 56.dp).height(330.dp)) {
                ArtImage(
                    ch.artwork, ch.name,
                    Modifier.width(220.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)),
                    titlePlaceholder = true,
                )
                Spacer(Modifier.width(40.dp))
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    Text(ch.name, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 44.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Movie · ${ch.group}", color = SV.Dim, fontSize = 16.sp)
                    if (canResume && progress != null) {
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ProgressLine(progress.fraction, Modifier.width(240.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(formatRemaining(progress.remainingMs), color = SV.Dim, fontSize = 14.sp)
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        TvButton(
                            text = if (canResume) "Resume" else "Play",
                            icon = Icons.Rounded.PlayArrow,
                            primary = true,
                            onClick = { nav.play(listOf(ch), 0, if (canResume) progress!!.positionMs else 0L) },
                            modifier = Modifier.focusRequester(playFocus),
                        )
                        if (canResume) {
                            TvButton("Start over", icon = Icons.Rounded.Replay, onClick = { nav.play(listOf(ch), 0, 0L) })
                        }
                        TvButton(
                            text = if (inList) "In My List" else "My List",
                            icon = if (inList) Icons.Rounded.Check else Icons.Rounded.Add,
                            onClick = { Repo.toggleFavorite(ch.id) },
                        )
                    }
                }
            }
            if (similar.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                ContentRow(BrowseRow("similar", "More in ${ch.group}", similar, CardStyle.POSTER), onFocused = {})
            }
        }
    }
}

@Composable
fun SeriesDetailsScreen(key: String) {
    val nav = LocalNav.current
    val lib by Repo.library.collectAsState()
    val progressMap by Repo.progress.collectAsState()
    val favList by Repo.favorites.collectAsState()
    val series = lib.series[key]
    if (series == null) {
        EmptyState("Series not available", "It may have been removed from your playlist.")
        return
    }
    val episodes = series.episodes
    // Netflix logic: resume the last episode you touched, or move on to the next if you finished it.
    val resumeIndex = remember(series, progressMap) {
        val last = episodes.withIndex()
            .filter { progressMap[it.value.id] != null }
            .maxByOrNull { progressMap[it.value.id]!!.updatedAt }
        when {
            last == null -> 0
            progressMap[last.value.id]!!.isFinished && last.index < episodes.size - 1 -> last.index + 1
            else -> last.index
        }
    }
    val resumeEp = episodes[resumeIndex]
    val resumeProgress = progressMap[resumeEp.id]?.takeIf { it.isInProgress }
    val started = progressMap.keys.any { k -> episodes.any { it.id == k } }
    val seasons = series.seasons
    var season by remember(series.key) { mutableIntStateOf(resumeEp.season ?: seasons.first()) }
    val seasonEpisodes = episodes.withIndex().filter { (it.value.season ?: 1) == season }
    val inList = series.favoriteId in favList
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { playFocus.safeRequestFocus() }

    Box(Modifier.fillMaxSize()) {
        Backdrop(series.poster)
        Row(Modifier.fillMaxSize().padding(start = 56.dp, top = 44.dp, end = 32.dp)) {
            Column(Modifier.width(380.dp)) {
                ArtImage(
                    series.poster, series.name,
                    Modifier.width(170.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)),
                    titlePlaceholder = true,
                )
                Spacer(Modifier.height(18.dp))
                Text(series.name, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 36.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    (if (seasons.size > 1) "${seasons.size} seasons · " else "") + "${episodes.size} episodes · ${series.group}",
                    color = SV.Dim, fontSize = 15.sp, maxLines = 2,
                )
                Spacer(Modifier.height(22.dp))
                TvButton(
                    text = when {
                        resumeProgress != null -> "Resume ${resumeEp.episodeLabel ?: ""}".trim()
                        started -> "Play ${resumeEp.episodeLabel ?: "next"}"
                        else -> "Play ${resumeEp.episodeLabel ?: ""}".trim()
                    },
                    icon = Icons.Rounded.PlayArrow,
                    primary = true,
                    onClick = { nav.play(episodes, resumeIndex, resumeProgress?.positionMs ?: 0L) },
                    modifier = Modifier.focusRequester(playFocus),
                )
                Spacer(Modifier.height(12.dp))
                TvButton(
                    text = if (inList) "In My List" else "My List",
                    icon = if (inList) Icons.Rounded.Check else Icons.Rounded.Add,
                    onClick = { Repo.toggleFavorite(series.favoriteId) },
                )
            }
            Spacer(Modifier.width(32.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (seasons.size > 1) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 6.dp),
                    ) {
                        itemsIndexed(seasons, key = { _, s -> s }) { _, s ->
                            SeasonChip("Season $s", selected = s == season, onSelect = { season = s })
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                } else {
                    Text("Episodes", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                }
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 48.dp, end = 16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(seasonEpisodes, key = { _, it -> it.value.id }) { _, indexed ->
                        EpisodeRow(
                            ep = indexed.value,
                            progress = progressMap[indexed.value.id]?.let { if (it.isFinished) 1f else it.fraction.takeIf { f -> f > 0.01f } },
                            onClick = {
                                val p = progressMap[indexed.value.id]?.takeIf { it.isInProgress }
                                nav.play(episodes, indexed.index, p?.positionMs ?: 0L)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SeasonChip(label: String, selected: Boolean, onSelect: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onSelect() }
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    focused -> Color.White
                    selected -> SV.Accent
                    else -> Color.White.copy(alpha = 0.12f)
                }
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onSelect)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(label, color = if (focused) Color.Black else Color.White, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EpisodeRow(ep: Channel, progress: Float?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Color.White else SV.Surface.copy(alpha = 0.7f))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(170.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
            ArtImage(ep.artwork, ep.name, Modifier.fillMaxSize())
            if (progress != null) {
                ProgressLine(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp))
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (ep.episode != null) "Episode ${ep.episode}" else "Episode",
                color = if (focused) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp,
            )
            Text(
                ep.name, color = if (focused) Color.DarkGray else SV.Dim, fontSize = 14.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
