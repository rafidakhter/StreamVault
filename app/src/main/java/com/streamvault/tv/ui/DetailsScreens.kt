package com.streamvault.tv.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
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
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
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
    val scope = rememberCoroutineScope()
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
                            icon = if (inList) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            onClick = { Repo.requestListPicker(ch.id) },
                        )
                    }
                }
            }
            if (similar.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                ContentRow(BrowseRow("similar", "More in ${ch.group}", similar, CardStyle.POSTER), onFocused = {},
                    onUp = { scope.launch { playFocus.safeRequestFocus() }; Unit })
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
    CompactSeriesDetails(series.key, series.name, series.poster,
        "${series.seasons.size} seasons · ${series.episodes.size} episodes · ${series.group}",
        series.visual?.facts, series.visual?.summary, series.episodes, progressMap) {
        TvButton(if (series.favoriteId in favList) "In My List" else "My List",
            small = true, icon = if (series.favoriteId in favList) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, onClick = { Repo.requestListPicker(series.favoriteId) })
        series.visual?.trailer?.let { TvButton("Trailer", small = true, onClick = { nav.trailer(it) }) }
    }
}
