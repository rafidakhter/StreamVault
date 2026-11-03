package com.streamvault.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A single bounded image behind the page; scrolling never stacks outgoing hero bitmaps. */
@Composable
private fun BrowseBackdrop(item: BrowseItem?) {
    val desired = item?.backdrop ?: item?.image
    var artwork by remember { mutableStateOf(desired) }
    var failedBackdrop by remember(artwork) { mutableStateOf(false) }
    LaunchedEffect(desired) { delay(180); artwork = desired }
    val context = LocalContext.current
    val image = if (failedBackdrop) item?.image else artwork
    val request = remember(context, image) {
        ImageRequest.Builder(context).data(image).size(1280, 720)
            .bitmapConfig(android.graphics.Bitmap.Config.RGB_565).build()
    }
    Box(Modifier.fillMaxSize().background(SV.Bg)) {
        if (image != null) AsyncImage(
            model = request, contentDescription = null,
            contentScale = if (item?.isLive == true) ContentScale.Fit else ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = if (item?.backdrop != null) Modifier.fillMaxSize()
                else Modifier.align(Alignment.TopEnd).fillMaxHeight().fillMaxWidth(0.76f),
            onError = { if (artwork != item?.image) failedBackdrop = true },
        )
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
            0f to SV.Bg.copy(alpha = 0.98f), 0.26f to SV.Bg.copy(alpha = 0.88f),
            0.60f to SV.Bg.copy(alpha = 0.24f), 1f to Color.Transparent)))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to SV.Bg.copy(alpha = 0.58f), 0.17f to Color.Transparent,
            0.48f to SV.Bg.copy(alpha = 0.16f), 0.82f to SV.Bg.copy(alpha = 0.88f), 1f to SV.Bg)))
    }
}

@Composable
private fun BrowseHero(item: BrowseItem?, expanded: Boolean, modifier: Modifier,
    playFocus: FocusRequester, onActionsFocused: (Boolean) -> Unit) {
    if (item == null) return
    Column(modifier.padding(start = 48.dp, top = 32.dp, bottom = 14.dp), verticalArrangement = Arrangement.Center) {
        if (item.isLive) { LiveBadge(); Spacer(Modifier.height(8.dp)) }
        Text(item.title, color = Color.White, fontSize = if (expanded) 40.sp else 28.sp,
            lineHeight = if (expanded) 44.sp else 32.sp, fontWeight = FontWeight.Black,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.60f))
        item.subtitle?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Color.White.copy(alpha = 0.82f), fontSize = 15.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.62f))
        }
        if (expanded) {
            item.facts?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = Color.White.copy(alpha = 0.88f), fontSize = 15.sp, maxLines = 2,
                    modifier = Modifier.fillMaxWidth(0.58f), overflow = TextOverflow.Ellipsis)
            }
            item.summary?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, color = Color.White.copy(alpha = 0.86f), fontSize = 17.sp, lineHeight = 25.sp,
                    maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.58f))
            }
        }
        item.progress?.let { Spacer(Modifier.height(10.dp)); ProgressLine(it, Modifier.width(240.dp)) }
        AnimatedVisibility(expanded) {
            Row(Modifier.padding(top = 18.dp).onFocusChanged { onActionsFocused(it.hasFocus) }.focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                key("play") {
                    TvButton(item.playLabel, onClick = item.onPlay ?: item.onClick,
                        icon = Icons.Rounded.PlayArrow, primary = true, modifier = Modifier.focusRequester(playFocus))
                }
                item.onTrailer?.let { key("trailer") { TvButton("Trailer", onClick = it) } }
                item.onDetails?.let { key("details") { TvButton("Details", onClick = it) } }
                item.onToggleList?.let { key("my-list") { TvButton(if (item.isFavorite) "In My List" else "My List", onClick = it) } }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(if (expanded) "← → to choose an action · ↓ to browse" else "↑ for actions · pause 5 seconds to preview",
            color = Color.White.copy(alpha = 0.70f), fontSize = 12.sp)
    }
}

@Composable
fun BrowseScreen(rows: List<BrowseRow>, listState: LazyListState, emptyTitle: String,
    emptyMessage: String? = null, showHero: Boolean = true, showCategoryPicker: Boolean = false,
    searchType: com.streamvault.tv.data.ContentType? = null,
    autoFocusResults: Boolean = true, initialFocusRequester: FocusRequester? = null,
    onBrowseFocused: () -> Unit = {}) {
    if (rows.isEmpty()) { EmptyState(emptyTitle, emptyMessage); return }
    var rowKey by rememberSaveable { mutableStateOf(rows.getOrNull(listState.firstVisibleItemIndex)?.key ?: rows.first().key) }
    var itemKey by rememberSaveable { mutableStateOf(rows.first().items.firstOrNull()?.key) }
    val selectedRow = rows.firstOrNull { it.key == rowKey } ?: rows.first()
    val hero = selectedRow.items.firstOrNull { it.key == itemKey } ?: selectedRow.items.firstOrNull()
    val nav = LocalNav.current
    var detailVisual by remember(hero?.key) { mutableStateOf<com.streamvault.tv.data.TitleArtwork?>(null) }
    val localFirstFocus = remember { FocusRequester() }
    val firstFocus = initialFocusRequester ?: localFirstFocus
    val playFocus = remember { FocusRequester() }
    var returnFocus by remember { mutableStateOf<FocusRequester?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var previewReady by remember { mutableStateOf(false) }
    var actionsFocused by remember { mutableStateOf(false) }
    var browserFocused by remember { mutableStateOf(false) }
    var activity by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val categoryChoices = remember(rows) { rows.map { CategoryChoice(it.key, it.title) } }
    val scrolling = listState.isScrollInProgress
    LaunchedEffect(hero?.key) {
        val id = hero?.channelId
        if (id != null && (hero.summary == null || hero.backdrop == null || hero.onTrailer == null)) {
            delay(350)
            detailVisual = com.streamvault.tv.data.Repo.heroArtwork(id)
        }
    }
    val displayedHero = hero?.copy(backdrop = detailVisual?.backdrop ?: hero.backdrop,
        summary = detailVisual?.summary ?: hero.summary, facts = detailVisual?.facts ?: hero.facts,
        onTrailer = detailVisual?.trailer?.let { { nav.trailer(it) } } ?: hero.onTrailer)
    LaunchedEffect(Unit) { if (autoFocusResults) firstFocus.safeRequestFocus() }
    LaunchedEffect(activity, hero?.key, browserFocused, scrolling, actionsFocused, expanded) {
        if (showHero && browserFocused && !scrolling && !actionsFocused && !expanded) {
            delay(5_000)
            expanded = true
        }
    }
    LaunchedEffect(expanded) {
        if (expanded) playFocus.safeRequestFocus() else previewReady = false
    }
    fun browseAgain() {
        val target = returnFocus ?: firstFocus
        val selectedRowIndex = rows.indexOfFirst { it.key == rowKey }
        expanded = false
        actionsFocused = false
        activity++
        scope.launch {
            if (selectedRowIndex >= 0) listState.scrollToItem(selectedRowIndex)
            target.safeRequestFocus()
        }
    }
    BackHandler(enabled = actionsFocused) { browseAgain() }
    BoxWithConstraints(Modifier.fillMaxSize()
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            activity++
            if (actionsFocused && event.key == Key.DirectionDown) {
                browseAgain()
                true
            } else {
                if (!actionsFocused && event.key in listOf(Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)) expanded = false
                false
            }
        }
        .pointerInput(Unit) { awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press || event.type == PointerEventType.Scroll || event.changes.any { it.pressed }) {
                    activity++
                    if (!actionsFocused) expanded = false
                }
            }
        } }
        .onFocusChanged { browserFocused = it.hasFocus }.focusGroup()) {
        if (showHero) BrowseBackdrop(displayedHero)
        // Transfer focus before removing tiles from placement; disabling the focused tile
        // first lets Android move focus to the sidebar before the actions are ready.
        val hideRows = expanded && previewReady
        val compactHeight = if (showHero) (maxHeight * 0.24f).coerceAtLeast(172.dp) else 0.dp
        val heroHeight by animateDpAsState(
            if (expanded) maxHeight else compactHeight,
            animationSpec = tween(320), label = "browseHeroHeight")
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(compactHeight))
            // Keep the browsing layout measured so Down can restore the exact horizontal tile.
            // Unplaced rows are neither drawn nor clickable while the full-screen preview is open.
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f).layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { if (!hideRows) placeable.placeRelative(0, 0) }
            },
                contentPadding = PaddingValues(top = if (showHero) 0.dp else 24.dp, bottom = 48.dp)) {
                itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                    ContentRow(row, onFocused = {
                        onBrowseFocused()
                        if (itemKey != it.key || rowKey != row.key) expanded = false
                        rowKey = row.key
                        itemKey = it.key
                        activity++
                    }, firstFocus = if (row.key == selectedRow.key) firstFocus else null,
                        restoreItemKey = if (row.key == selectedRow.key) itemKey else null,
                        onFocusTarget = { returnFocus = it },
                        onActivity = { activity++ },
                        navigationEnabled = !hideRows,
                        onUp = if (showHero && index == listState.firstVisibleItemIndex) ({
                            expanded = true
                        }) else null)
                }
            }
        }
        if (showHero) BrowseHero(displayedHero, expanded, Modifier.fillMaxWidth().height(heroHeight), playFocus) {
            actionsFocused = it
            if (it) previewReady = true
        }
        if (showCategoryPicker) Row(Modifier.align(Alignment.TopEnd).padding(end = 32.dp, top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CategoryPickerButton(categoryChoices, selectedRow.key) { choice ->
                val index = rows.indexOfFirst { it.key == choice.key }
                if (index >= 0) scope.launch {
                    activity++
                    expanded = false
                    rowKey = choice.key
                    itemKey = rows[index].items.firstOrNull()?.key
                    listState.scrollToItem(index)
                    firstFocus.safeRequestFocus()
                }
            }
            searchType?.let { ScopedSearchButton(it) }
        }
    }
}
