package com.streamvault.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamvault.tv.data.Channel
import com.streamvault.tv.data.Progress
import com.streamvault.tv.data.Repo
import kotlinx.coroutines.launch

/** Shared by organized and original series details so playback and remote navigation stay consistent. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
fun CompactSeriesDetails(
    contentKey: String,
    title: String,
    poster: String?,
    metadata: String,
    facts: String?,
    summary: String?,
    episodes: List<Channel>,
    progress: Map<String, Progress>,
    autoFocus: Boolean = true,
    actions: @Composable () -> Unit,
) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val seasons = remember(episodes) { episodes.map { it.season ?: 1 }.distinct().sorted() }
    // Merely opening an episode can create a zero-position progress entry; that is not viewing history.
    val lastWatched = remember(episodes, progress) {
        episodes.withIndex().mapNotNull { indexed ->
            Repo.progressFor(indexed.value)?.takeIf { it.positionMs >= 1_000 || it.isFinished }
                ?.let { indexed.index to it }
        }.maxByOrNull { it.second.updatedAt }
    }
    val resumeIndex = lastWatched?.let { (index, saved) ->
        if (!saved.isFinished) index else (index + 1).takeIf { it < episodes.size }
    }
    val resumePosition = resumeIndex?.let { index ->
        Repo.progressFor(episodes[index])?.takeIf { it.isInProgress }?.positionMs ?: 0L
    } ?: 0L
    var selectedSeason by rememberSaveable(contentKey) {
        mutableIntStateOf(resumeIndex?.let { episodes[it].season ?: 1 } ?: seasons.firstOrNull() ?: 1)
    }
    val season = selectedSeason.takeIf { it in seasons } ?: seasons.firstOrNull() ?: 1
    val seasonEpisodes = remember(episodes, season) { episodes.withIndex().filter { (it.value.season ?: 1) == season } }
    var history by rememberSaveable(contentKey) { mutableStateOf(hashMapOf<Int, String>()) }
    val restoreIndex = seasonEpisodes.indexOfFirst { it.value.id == history[season] }.coerceAtLeast(0)
    val requesters = remember(seasonEpisodes) { List(seasonEpisodes.size) { FocusRequester() } }
    val episodeState = rememberLazyListState()
    val episodeEntryFocus = remember { FocusRequester() }
    val leftScroll = rememberScrollState()
    val resumeFocus = remember { FocusRequester() }
    val seasonFocus = remember { FocusRequester() }
    val actionsFocus = remember { FocusRequester() }
    LaunchedEffect(contentKey) {
        if (autoFocus) (when {
            resumeIndex != null -> resumeFocus
            seasons.isNotEmpty() -> seasonFocus
            else -> actionsFocus
        }).safeRequestFocus()
    }
    LaunchedEffect(season) { episodeState.scrollToItem(restoreIndex) }
    fun primaryControl(requester: FocusRequester) = Modifier.focusRequester(requester)
        .onFocusChanged { if (it.isFocused) scope.launch { leftScroll.animateScrollTo(0) } }
        .onPreviewKeyEvent { event ->
            if (event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown && leftScroll.value > 0) {
                if (event.nativeKeyEvent.repeatCount == 0) scope.launch { leftScroll.animateScrollTo(0) }
                true
            } else false
        }

    Box(Modifier.fillMaxSize().background(SV.Bg)) {
        ArtImage(poster, title, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
            0f to SV.Bg.copy(alpha = 0.96f), 0.48f to SV.Bg.copy(alpha = 0.78f),
            1f to SV.Bg.copy(alpha = 0.42f))))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to SV.Bg.copy(alpha = 0.20f), 1f to SV.Bg.copy(alpha = 0.90f))))
        Row(Modifier.fillMaxSize().padding(28.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(0.60f).fillMaxHeight().verticalScroll(leftScroll)
                .focusProperties {
                    exit = { direction ->
                        if (direction == FocusDirection.Right && requesters.isNotEmpty()) episodeEntryFocus
                        else FocusRequester.Default
                    }
                }.focusGroup(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Spacer(Modifier.height(24.dp))
                Text(title, color = Color.White, fontSize = 34.sp, lineHeight = 39.sp,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(metadata, color = Color.White.copy(alpha = 0.70f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                facts?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                summary?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = SV.Dim, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (resumeIndex != null) {
                        TvButton("Resume ${episodes[resumeIndex].episodeLabel.orEmpty()}".trim(), primary = true, small = true,
                            modifier = primaryControl(resumeFocus), onClick = { nav.play(episodes, resumeIndex, resumePosition) })
                    }
                    if (seasons.isNotEmpty()) CategoryPickerButton(
                        seasons.map { number -> CategoryChoice(number.toString(), "Season $number") }, season.toString(),
                        modifier = if (resumeIndex == null) primaryControl(seasonFocus) else Modifier.focusRequester(seasonFocus),
                        buttonLabel = "Season $season", dialogTitle = "Select season", searchLabel = "Search seasons", small = true,
                    ) {
                        selectedSeason = it.key.toInt()
                        scope.launch { seasonFocus.safeRequestFocus() }
                    }
                }
                FlowRow(Modifier.focusRequester(actionsFocus).focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    actions()
                }
            }
            Column(Modifier.weight(0.40f).fillMaxHeight().clip(RoundedCornerShape(18.dp))
                .background(SV.Surface.copy(alpha = 0.82f)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Season $season", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("${seasonEpisodes.size} episodes", color = SV.Dim, fontSize = 13.sp)
                }
                if (seasonEpisodes.isEmpty()) Text("No episodes available", color = SV.Dim)
                else LazyColumn(state = episodeState, modifier = Modifier.weight(1f).fillMaxWidth().onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key in listOf(Key.DirectionUp, Key.DirectionDown) &&
                        event.nativeKeyEvent.repeatCount > 0) true else false
                }, contentPadding = PaddingValues(6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(seasonEpisodes, key = { _, indexed -> indexed.value.id }) { index, indexed ->
                        val episode = indexed.value
                        val saved = Repo.progressFor(episode)
                        FocusCard(onClick = {
                            nav.play(episodes, indexed.index, saved?.takeIf { it.isInProgress }?.positionMs ?: 0L)
                        }, focusedScale = 1.015f, background = SV.Surface2,
                            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionLeft ||
                                        (index == 0 && event.key == Key.DirectionUp))) {
                                    if (event.nativeKeyEvent.repeatCount == 0) scope.launch { seasonFocus.safeRequestFocus() }
                                    true
                                } else false
                            }.focusRequester(requesters[index])
                                .then(if (index == restoreIndex) Modifier.focusRequester(episodeEntryFocus) else Modifier)
                                .onFocusChanged {
                                if (it.isFocused && history[season] != episode.id) {
                                    history = HashMap(history).apply { put(season, episode.id) }
                                }
                            }) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(episode.episodeLabel ?: "Episode ${index + 1}", color = Color.White,
                                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text(com.streamvault.tv.data.LibraryOrganization.clean(episode.name), color = SV.Dim,
                                    fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                saved?.takeIf { it.positionMs >= 1_000 || it.isFinished }?.let { ProgressLine(it.fraction, Modifier.fillMaxWidth()) }
                            }
                        }
                    }
                }
            }
        }
    }
}
