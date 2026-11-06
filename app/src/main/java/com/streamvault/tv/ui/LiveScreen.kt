package com.streamvault.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamvault.tv.data.Channel
import com.streamvault.tv.data.ContentType
import com.streamvault.tv.data.Library
import com.streamvault.tv.data.Repo
import kotlinx.coroutines.launch

/** TiviMate-style live browser: categories on the left, channel grid on the right. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LiveScreen(lib: Library, favList: List<String>, recent: List<String>) {
    val nav = LocalNav.current
    val favs = remember(favList) { favList.toHashSet() }
    val groups: List<Pair<String, List<Channel>>> = remember(lib, favList, recent) {
        val favChannels = favList.mapNotNull { lib.byId[it] }.filter { it.type == ContentType.LIVE }
        val recentChannels = recent.mapNotNull { lib.byId[it] }.filter { it.type == ContentType.LIVE }
        buildList<Pair<String, List<Channel>>> {
            if (favChannels.isNotEmpty()) add("★ Favourites" to favChannels)
            if (recentChannels.isNotEmpty()) add("Recently watched" to recentChannels)
            addAll(lib.liveGroups)
        }
    }
    if (groups.isEmpty()) {
        EmptyState("No live channels", "Your playlists don't contain any live TV channels.")
        return
    }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val current = groups.firstOrNull { it.first == selected } ?: groups.first()
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.safeRequestFocus() }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val categoriesState = androidx.compose.foundation.lazy.rememberLazyListState()
    val channelFocus = remember { FocusRequester() }
    val controlsFocus = remember { FocusRequester() }
    val categoryChoices = remember(groups) {
        groups.mapIndexed { index, group -> CategoryChoice(index.toString(), group.first, group.second.size) }
    }

    val continueItems = remember(lib, recent, nav) { recentlyWatchedLive(lib, recent, nav) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 40.dp, top = 12.dp, bottom = 6.dp)
            .focusRequester(controlsFocus).focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            CategoryPickerButton(categoryChoices, groups.indexOf(current).toString()) { choice ->
                val index = choice.key.toInt()
                selected = groups[index].first
                scope.launch {
                    categoriesState.scrollToItem(index + 1)
                    channelFocus.safeRequestFocus()
                }
            }
            ScopedSearchButton(ContentType.LIVE)
        }
        if (continueItems.isNotEmpty()) {
            ContentRow(continueWatchingRow(continueItems, nav, ContentType.LIVE), onFocused = {}, firstFocus = firstFocus,
                onUp = { scope.launch { controlsFocus.safeRequestFocus() }; Unit })
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                state = categoriesState,
                modifier = Modifier.width(290.dp).fillMaxHeight().background(SV.Surface.copy(alpha = 0.55f)),
                contentPadding = PaddingValues(vertical = 28.dp, horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item {
                    Text(
                        "Live TV", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(start = 12.dp, bottom = 14.dp),
                    )
                }
                itemsIndexed(groups, key = { i, g -> "$i:${g.first}" }) { i, g ->
                    GroupItem(
                        name = g.first,
                        count = g.second.size,
                        selected = g.first == current.first,
                        onFocused = { selected = g.first },
                        onClick = { focusManager.moveFocus(FocusDirection.Right) },
                        modifier = Modifier.onPreviewKeyEvent { event ->
                            if (i == 0 && event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown) {
                                if (event.nativeKeyEvent.repeatCount == 0) scope.launch {
                                    (if (continueItems.isEmpty()) controlsFocus else firstFocus).safeRequestFocus()
                                }
                                true
                            } else false
                        }.then(if (i == 0 && continueItems.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier),
                    )
                }
            }
            val channels = current.second
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Text(
                    current.first, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 28.dp, top = 30.dp, bottom = 2.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${channels.size} channels · hold OK to favourite",
                    color = SV.Dim, fontSize = 14.sp, modifier = Modifier.padding(start = 28.dp),
                )
                val gridState = rememberLazyGridState()
                LaunchedEffect(current.first) { gridState.scrollToItem(0) }
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(190.dp),
                    contentPadding = PaddingValues(start = 28.dp, end = 40.dp, top = 16.dp, bottom = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(channels, key = { _, c -> c.id }) { i, ch ->
                        ChannelCard(liveItem(ch, channels, i, nav, favs), Modifier.fillMaxWidth()
                            .onPreviewKeyEvent { event ->
                                val topRow = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == i }?.row == 0
                                if (topRow && event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown) {
                                    if (event.nativeKeyEvent.repeatCount == 0) scope.launch {
                                        (if (continueItems.isEmpty()) controlsFocus else firstFocus).safeRequestFocus()
                                    }
                                    true
                                } else false
                            }
                            .then(if (i == 0) Modifier.focusRequester(channelFocus) else Modifier))
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupItem(
    name: String,
    count: Int,
    selected: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    focused -> Color.White
                    selected -> SV.Accent.copy(alpha = 0.25f)
                    else -> Color.Transparent
                }
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name, color = if (focused) Color.Black else Color.White, fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Text("$count", color = if (focused) Color.DarkGray else SV.Dim, fontSize = 13.sp)
    }
}

