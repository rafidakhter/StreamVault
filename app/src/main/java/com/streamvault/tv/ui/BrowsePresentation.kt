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
import coil.imageLoader
import coil.request.SuccessResult
import androidx.compose.animation.Crossfade
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class BackdropFrame(val url: String, val poster: String?, val wide: Boolean, val live: Boolean)

/** Keep the outgoing image until the incoming image is decoded, then overlap their fades. */
@Composable
private fun BrowseBackdrop(item: BrowseItem?, expanded: Boolean) {
    var frame by remember { mutableStateOf<BackdropFrame?>(null) }
    val context = LocalContext.current
    LaunchedEffect(item?.key, item?.image, item?.backdrop, item?.gallery, expanded) {
        val current = item ?: return@LaunchedEffect
        val candidates = (listOfNotNull(current.image, current.backdrop) + current.gallery).distinct()
        if (candidates.isEmpty()) return@LaunchedEffect
        var index = if (candidates.size > 1) 1 else 0
        while (true) {
            val url = candidates[index]
            val request = ImageRequest.Builder(context).data(url).size(1280, 720)
                .bitmapConfig(android.graphics.Bitmap.Config.RGB_565).build()
            val loaded = context.imageLoader.execute(request)
            if (loaded is SuccessResult) {
                frame = BackdropFrame(url, current.image, url != current.image, current.isLive)
            } else if (frame == null && current.image != null && url != current.image) {
                val fallback = request.newBuilder().data(current.image).build()
                if (context.imageLoader.execute(fallback) is SuccessResult)
                    frame = BackdropFrame(current.image, current.image, false, current.isLive)
            }
            if (!expanded || candidates.size == 1) break
            delay(5_000)
            index = (index + 1) % candidates.size
        }
    }
    Box(Modifier.fillMaxSize().background(SV.Bg)) {
        Crossfade(targetState = frame, animationSpec = tween(900), label = "backdropCrossfade") { shown ->
            if (shown != null) {
                val request = remember(context, shown.url) {
                    ImageRequest.Builder(context).data(shown.url).size(1280, 720)
                        .bitmapConfig(android.graphics.Bitmap.Config.RGB_565).build()
                }
                Box(Modifier.fillMaxSize()) {
                    AsyncImage(model = request, contentDescription = null,
                        contentScale = if (shown.live) ContentScale.Fit else ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = if (shown.wide) Modifier.fillMaxSize()
                            else Modifier.align(Alignment.TopEnd).fillMaxHeight().fillMaxWidth(0.76f))
                }
            }
        }
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
                item.onVersions?.let { key("versions") { TvButton("Versions", onClick = it) } }
                item.onDetails?.let { key("details") { TvButton("Details", onClick = it) } }
                item.onToggleList?.let { key("my-list") { TvButton(if (item.isFavorite) "In My List" else "My List", onClick = it) } }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(if (expanded) "← → to choose an action · ↓ to browse" else "Pause 5 seconds to preview",
            color = Color.White.copy(alpha = 0.70f), fontSize = 12.sp)
    }
}

@Composable
fun BrowseScreen(rows: List<BrowseRow>, listState: LazyListState, emptyTitle: String,
    emptyMessage: String? = null, showHero: Boolean = true, showCategoryPicker: Boolean = false,
    searchType: com.streamvault.tv.data.ContentType? = null,
    autoFocusResults: Boolean = true, initialFocusRequester: FocusRequester? = null,
    onBrowseFocused: () -> Unit = {}, topControls: (@Composable () -> Unit)? = null,
    onUpFromFirstRow: (() -> Unit)? = null) {
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
    var mediaFocused by remember { mutableStateOf(false) }
    var activity by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    var rowHistory by rememberSaveable { mutableStateOf(hashMapOf<String, String>()) }
    val rowEntries = remember(rows.map { it.key }) { rows.associate { it.key to FocusRequester() } }
    val rowEntryActions = remember { mutableMapOf<String, suspend () -> Unit>() }
    var rowMoveJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { rowMoveJob?.cancel() } }
    fun rememberRowItem(key: String, tile: String) {
        if (rowHistory[key] != tile) rowHistory = HashMap(rowHistory).apply { put(key, tile) }
    }
    fun moveToRow(index: Int) {
        val target = rows.getOrNull(index) ?: return
        // Let an accepted row move finish instead of repeatedly cancelling its focus request.
        if (rowMoveJob?.isActive == true) return
        activity++
        rowMoveJob = scope.launch {
            // Keep the original animated row transition; focus brings partially visible
            // rows into view without snapping every selection to the top of the screen.
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                listState.animateScrollToItem(index)
            }
            withFrameNanos { }
            withFrameNanos { }
            val enter = rowEntryActions[target.key]
            if (enter != null) enter() else rowEntries[target.key]?.safeRequestFocus()
        }
    }

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
        gallery = detailVisual?.gallery?.takeIf { it.isNotEmpty() } ?: hero.gallery,
        onTrailer = detailVisual?.trailer?.let { { nav.trailer(it) } } ?: hero.onTrailer)
    LaunchedEffect(Unit) { if (autoFocusResults) firstFocus.safeRequestFocus() }
    LaunchedEffect(activity, hero?.key, browserFocused, scrolling, actionsFocused, expanded, mediaFocused) {
        if (showHero && browserFocused && mediaFocused && !scrolling && !actionsFocused && !expanded) {
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
            val vertical = event.key == Key.DirectionUp || event.key == Key.DirectionDown
            // Filter at the screen boundary so a held key cannot cascade through newly focused rows.
            // Horizontal key repeat is intentionally unchanged.
            if (vertical && event.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent true
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            if (vertical && rowMoveJob?.isActive == true) return@onPreviewKeyEvent true
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
        if (showHero) BrowseBackdrop(displayedHero, expanded)
        // Transfer focus before removing tiles from placement; disabling the focused tile
        // first lets Android move focus to the sidebar before the actions are ready.
        val hideRows = expanded && previewReady
        val inlineDetails = rows.any { it.style == CardStyle.POSTER }
        // Search already has its own header; a disabled hero must not reserve poster-header space.
        val compactHeight = if (!showHero && topControls == null && !showCategoryPicker) 0.dp
            else if (inlineDetails) 72.dp
            else if (showHero && displayedHero != null) (maxHeight * 0.24f).coerceAtLeast(172.dp)
            else if (topControls != null || showCategoryPicker) 64.dp else 0.dp
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
                    val displayRow = if (displayedHero != null && row.key == selectedRow.key)
                        row.copy(items = row.items.map { if (it.key == displayedHero.key) displayedHero else it }) else row
                    ContentRow(displayRow, onFocused = {
                        mediaFocused = true
                        onBrowseFocused()
                        if (itemKey != it.key || rowKey != row.key) expanded = false
                        rowKey = row.key
                        itemKey = it.key
                        activity++
                    }, firstFocus = if (row.key == selectedRow.key) firstFocus else null,
                        restoreItemKey = rowHistory[row.key] ?: if (row.key == selectedRow.key) itemKey else null,
                        rowEntryFocus = rowEntries[row.key],
                        registerRowEntry = { action ->
                            if (action == null) rowEntryActions.remove(row.key)
                            else rowEntryActions[row.key] = action
                        },
                        onItemFocusedKey = { rememberRowItem(row.key, it) },
                        onDown = if (index < rows.lastIndex) ({ moveToRow(index + 1) }) else null,
                        onFocusTarget = { returnFocus = it },
                        onActivity = { activity++ },
                        navigationEnabled = !hideRows,
                        onNavigationFocus = { key, requester ->
                            mediaFocused = false
                            expanded = false
                            rememberRowItem(row.key, key)
                            rowKey = row.key
                            itemKey = key
                            returnFocus = requester
                            activity++
                        },
                        onUp = when {
                            index > 0 -> ({ moveToRow(index - 1) })
                            else -> onUpFromFirstRow
                        })
                }
            }
        }
        if (showHero && (!inlineDetails || expanded)) BrowseHero(displayedHero, expanded, Modifier.fillMaxWidth().height(heroHeight), playFocus) {
            actionsFocused = it
            if (it) previewReady = true
        }
        if (topControls != null) Row(Modifier.align(Alignment.TopEnd).padding(end = 32.dp, top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) { topControls() }
        else if (showCategoryPicker) Row(Modifier.align(Alignment.TopEnd).padding(end = 32.dp, top = 12.dp),
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
