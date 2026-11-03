package com.streamvault.tv.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/** One tile in a row or grid. */
data class BrowseItem(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val image: String? = null,
    val progress: Float? = null,
    val isLive: Boolean = false,
    val isFavorite: Boolean = false,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null,
)

enum class CardStyle { POSTER, LANDSCAPE, CHANNEL }

/** Focus requests fail if the target isn't laid out yet, so retry a few times. */
suspend fun FocusRequester.safeRequestFocus() {
    // Allow the first layout to finish before focus starts bring-into-view work.
    androidx.compose.runtime.withFrameNanos { }
    androidx.compose.runtime.withFrameNanos { }
    repeat(5) { attempt ->
        delay(if (attempt == 0) 60L else 150L)
        if (runCatching { requestFocus() }.isSuccess) return
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    focusedScale: Float = 1.08f,
    onFocused: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    background: Color = SV.Surface,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, label = "cardScale")
    LaunchedEffect(focused) { if (focused) onFocused() }
    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(background)
            .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
    ) {
        content(focused)
    }
}

private val placeholderGradients = listOf(
    listOf(Color(0xFF312E81), Color(0xFF6D28D9)),
    listOf(Color(0xFF831843), Color(0xFFBE185D)),
    listOf(Color(0xFF134E4A), Color(0xFF0F766E)),
    listOf(Color(0xFF7C2D12), Color(0xFFC2410C)),
    listOf(Color(0xFF1E3A8A), Color(0xFF2563EB)),
    listOf(Color(0xFF3F3F46), Color(0xFF52525B)),
)

fun initials(text: String): String {
    val words = text.split(' ', '-', '_', '|', '.').filter { it.isNotBlank() && it[0].isLetterOrDigit() }
    return words.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "TV" }
}

@Composable
fun Placeholder(title: String, modifier: Modifier = Modifier, showTitle: Boolean = false) {
    val colors = placeholderGradients[(title.hashCode() and 0x7fffffff) % placeholderGradients.size]
    Box(modifier.background(Brush.linearGradient(colors)).padding(10.dp), contentAlignment = Alignment.Center) {
        if (showTitle) {
            Text(
                title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(initials(title), color = Color.White.copy(alpha = 0.92f), fontWeight = FontWeight.Black, fontSize = 26.sp)
        }
    }
}

@Composable
fun ArtImage(
    url: String?,
    title: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    titlePlaceholder: Boolean = false,
) {
    var loaded by remember(url) { mutableStateOf(false) }
    Box(modifier) {
        if (!loaded) Placeholder(title, Modifier.matchParentSize(), showTitle = titlePlaceholder)
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .size(600, 900)
                    .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                    .build(),
                contentDescription = title,
                modifier = Modifier.matchParentSize(),
                contentScale = contentScale,
                onState = { loaded = it is AsyncImagePainter.State.Success },
            )
        }
    }
}

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.3f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(SV.Accent))
    }
}

@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(4.dp)).background(SV.Live).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
        Spacer(Modifier.width(4.dp))
        Text("LIVE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FavoriteBadge(modifier: Modifier) {
    Box(
        modifier.size(26.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Favorite, null, tint = SV.Accent2, modifier = Modifier.size(15.dp))
    }
}

/** 2:3 poster: movies and series. Pass Modifier.width(..) in rows, fillMaxWidth() in grids. */
@Composable
fun PosterCard(item: BrowseItem, modifier: Modifier = Modifier, onFocused: (BrowseItem) -> Unit = {}) {
    FocusCard(
        onClick = item.onClick,
        onLongClick = item.onLongClick,
        onFocused = { onFocused(item) },
        modifier = modifier.aspectRatio(2f / 3f),
    ) { _ ->
        ArtImage(item.image, item.title, Modifier.fillMaxSize(), titlePlaceholder = true)
        if (item.isFavorite) FavoriteBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
        if (item.progress != null) {
            ProgressLine(item.progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp))
        }
    }
}

/** 16:9 artwork with the title on a gradient: Continue Watching. */
@Composable
fun LandscapeCard(item: BrowseItem, modifier: Modifier = Modifier, onFocused: (BrowseItem) -> Unit = {}) {
    FocusCard(
        onClick = item.onClick,
        onLongClick = item.onLongClick,
        onFocused = { onFocused(item) },
        modifier = modifier.aspectRatio(16f / 9f),
    ) { _ ->
        ArtImage(item.image, item.title, Modifier.fillMaxSize(),
            contentScale = if (item.isLive) ContentScale.Fit else ContentScale.Crop)
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.9f))
            )
        )
        if (item.isLive) LiveBadge(Modifier.align(Alignment.TopStart).padding(10.dp))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp)) {
            Text(item.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.subtitle != null) {
                Text(item.subtitle, color = SV.Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (item.progress != null) {
                Spacer(Modifier.height(6.dp))
                ProgressLine(item.progress, Modifier.fillMaxWidth())
            }
        }
    }
}

/** Live channel tile: logo fitted on a dark plate, name underneath. */
@Composable
fun ChannelCard(item: BrowseItem, modifier: Modifier = Modifier, onFocused: (BrowseItem) -> Unit = {}) {
    FocusCard(
        onClick = item.onClick,
        onLongClick = item.onLongClick,
        onFocused = { onFocused(item) },
        modifier = modifier,
        background = SV.Surface2,
    ) { focused ->
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF1B1B26)).padding(14.dp)) {
                ArtImage(item.image, item.title, Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Fit)
            }
            Row(
                Modifier.fillMaxWidth().background(if (focused) Color.White else Color.Transparent).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.isFavorite) {
                    Icon(Icons.Rounded.Favorite, null, tint = SV.Accent2, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    item.title, color = if (focused) Color.Black else Color.White, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun ViewAllCard(style: CardStyle, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val mod = when (style) {
        CardStyle.POSTER -> Modifier.width(140.dp).aspectRatio(2f / 3f)
        CardStyle.LANDSCAPE -> Modifier.width(260.dp).aspectRatio(16f / 9f)
        CardStyle.CHANNEL -> Modifier.width(200.dp).aspectRatio(1.35f)
    }
    FocusCard(onClick = onClick, modifier = modifier.then(mod), background = SV.Surface2) { focused ->
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.ArrowForward, null, tint = if (focused) SV.Accent else Color.White, modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(8.dp))
            Text("View all", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Pill button with the "white when focused" TV convention. compact = icon only until focused. */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    compact: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = when {
        focused -> Color.White
        primary -> SV.Accent
        else -> Color.White.copy(alpha = 0.14f)
    }
    Row(
        modifier
            .animateContentSize()
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = if (compact && !focused) 14.dp else 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (focused) Color.Black else Color.White
        if (icon != null) Icon(icon, text, tint = fg, modifier = Modifier.size(24.dp))
        if (!compact || focused || icon == null) {
            if (icon != null) Spacer(Modifier.width(10.dp))
            Text(text, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
        }
    }
}

@Composable
fun TvIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.14f))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (focused) Color.Black else Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

/** Row used in side panels and settings lists. */
@Composable
fun PanelItem(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    selected: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color.White else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title, color = if (focused) Color.Black else Color.White, fontSize = 16.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(subtitle, color = if (focused) Color.DarkGray else SV.Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (selected) {
            Icon(Icons.Rounded.Check, null, tint = if (focused) Color.Black else SV.Accent, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
fun EmptyState(title: String, message: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (message != null) {
            Spacer(Modifier.height(10.dp))
            Text(message, color = SV.Dim, fontSize = 16.sp, textAlign = TextAlign.Center)
        }
    }
}

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatRemaining(ms: Long): String {
    val min = (ms / 60_000).coerceAtLeast(1)
    return if (min >= 60) "${min / 60}h ${min % 60}m left" else "${min}m left"
}
