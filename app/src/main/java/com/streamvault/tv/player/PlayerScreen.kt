package com.streamvault.tv.player

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.streamvault.tv.data.AppSettings
import com.streamvault.tv.data.Channel
import com.streamvault.tv.data.Repo
import com.streamvault.tv.ui.ArtImage
import com.streamvault.tv.ui.LiveBadge
import com.streamvault.tv.ui.SV
import com.streamvault.tv.ui.TvButton
import com.streamvault.tv.ui.TvIconButton
import com.streamvault.tv.ui.formatDuration
import com.streamvault.tv.ui.safeRequestFocus
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Panel { SUBTITLES, AUDIO, SPEED, CHANNELS }

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(ctrl: PlayerController, onExit: () -> Unit) {
    val settings by Repo.settings.collectAsState()
    val favList by Repo.favorites.collectAsState()

    var controlsVisible by remember { mutableStateOf(!ctrl.isLive) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var interactions by remember { mutableIntStateOf(0) }
    var focusSeekBar by remember { mutableStateOf(false) }
    var bannerVisible by remember { mutableStateOf(false) }

    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val seekFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    val overlayFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }

    val ch = ctrl.current
    val isLive = ctrl.isLive
    val error = ctrl.error
    val showUpNext = ctrl.ended && ctrl.hasNext && !isLive && settings.autoplayNext
    val nearEnd by remember {
        derivedStateOf {
            !ctrl.isLive && ctrl.hasNext && !ctrl.ended && ctrl.durationMs > 120_000 &&
                (ctrl.durationMs - ctrl.positionMs) in 1L..40_000L
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            ctrl.tick()
            delay(100)
        }
    }

    // Netflix-style auto-hide of the controls.
    LaunchedEffect(controlsVisible, panel, interactions, ctrl.isPlaying) {
        if (controlsVisible && panel == null && ctrl.isPlaying) {
            delay(5000)
            controlsVisible = false
        }
    }

    // Channel banner on every zap.
    LaunchedEffect(ctrl.index) {
        if (ctrl.isLive) {
            bannerVisible = true
            delay(4000)
            bannerVisible = false
        }
    }

    LaunchedEffect(ctrl.ended) {
        if (ctrl.ended && !showUpNext) controlsVisible = true
    }

    // Where the remote's focus should be.
    LaunchedEffect(controlsVisible, panel, error != null, showUpNext, nearEnd) {
        when {
            error != null || showUpNext -> overlayFocus.safeRequestFocus()
            panel != null -> panelFocus.safeRequestFocus()
            controlsVisible && focusSeekBar && !isLive -> {
                focusSeekBar = false
                seekFocus.safeRequestFocus()
            }
            controlsVisible -> playFocus.safeRequestFocus()
            nearEnd -> nextFocus.safeRequestFocus()
            else -> rootFocus.safeRequestFocus()
        }
    }

    BackHandler {
        when {
            panel != null -> {
                panel = null
                controlsVisible = true
            }
            controlsVisible && error == null && !ctrl.ended -> controlsVisible = false
            else -> onExit()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                interactions++
                when (ev.key) {
                    Key.MediaPlayPause -> {
                        ctrl.togglePlay()
                        controlsVisible = true
                        true
                    }
                    Key.MediaPlay -> {
                        ctrl.play()
                        true
                    }
                    Key.MediaPause -> {
                        ctrl.pause()
                        controlsVisible = true
                        true
                    }
                    Key.MediaFastForward -> {
                        if (!isLive) {
                            focusSeekBar = true
                            ctrl.seekBy(30_000)
                            controlsVisible = true
                        }
                        true
                    }
                    Key.MediaRewind -> {
                        if (!isLive) {
                            focusSeekBar = true
                            ctrl.seekBy(-10_000)
                            controlsVisible = true
                        }
                        true
                    }
                    Key.ChannelUp, Key.MediaNext -> {
                        ctrl.next()
                        true
                    }
                    Key.ChannelDown, Key.MediaPrevious -> {
                        ctrl.previous()
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (controlsVisible || panel != null || error != null || showUpNext) return@onKeyEvent false
                when (ev.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        controlsVisible = true
                        true
                    }
                    Key.DirectionUp -> {
                        if (isLive) {
                            ctrl.previous()
                        } else {
                            controlsVisible = true
                        }
                        true
                    }
                    Key.DirectionDown -> {
                        if (isLive) {
                            ctrl.next()
                        } else {
                            controlsVisible = true
                        }
                        true
                    }
                    Key.DirectionLeft -> {
                        if (isLive) {
                            panel = Panel.CHANNELS
                        } else {
                            focusSeekBar = true
                            ctrl.seekBy(-10_000)
                            controlsVisible = true
                        }
                        true
                    }
                    Key.DirectionRight -> {
                        if (isLive) {
                            controlsVisible = true
                        } else {
                            focusSeekBar = true
                            ctrl.seekBy(10_000)
                            controlsVisible = true
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    player = ctrl.player
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    setKeepContentOnPlayerReset(true)
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
            },
            update = { view ->
                view.resizeMode = ctrl.resizeMode
                view.subtitleView?.visibility = if (ctrl.subs.active) View.GONE else View.VISIBLE
                view.subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * settings.subtitleScale)
            },
            modifier = Modifier.fillMaxSize(),
        )

        ExternalSubtitleOverlay(ctrl, settings, raised = controlsVisible)

        if (ctrl.isBuffering && error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = SV.Accent, strokeWidth = 4.dp)
        }

        AnimatedVisibility(
            visible = bannerVisible && isLive && !controlsVisible && panel == null,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            ChannelBanner(ctrl)
        }

        AnimatedVisibility(
            visible = controlsVisible && panel == null && error == null && !showUpNext,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                TopInfo(ctrl, Modifier.align(Alignment.TopStart))
                BottomControls(
                    ctrl = ctrl,
                    isFavorite = ch.favoriteId in favList,
                    playFocus = playFocus,
                    seekFocus = seekFocus,
                    onPanel = { panel = it },
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }

        if (nearEnd && !controlsVisible && panel == null) {
            NextEpisodeButton(
                next = ctrl.queue[ctrl.index + 1],
                focus = nextFocus,
                onClick = { ctrl.next() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(48.dp),
            )
        }

        when (panel) {
            Panel.SUBTITLES -> SubtitlesPanel(ctrl, settings, panelFocus)
            Panel.AUDIO -> AudioPanel(ctrl, panelFocus)
            Panel.SPEED -> SpeedPanel(ctrl, panelFocus) { panel = null; controlsVisible = true }
            Panel.CHANNELS -> ChannelsPanel(ctrl, panelFocus) { panel = null }
            null -> Unit
        }

        if (showUpNext) {
            UpNextOverlay(
                next = ctrl.queue[ctrl.index + 1],
                focus = overlayFocus,
                onPlay = { ctrl.next() },
                onCancel = onExit,
            )
        }

        if (error != null) {
            ErrorOverlay(
                message = error,
                focus = overlayFocus,
                onRetry = { ctrl.retry() },
                onNext = if (ctrl.hasNext) ({ ctrl.next() }) else null,
                onExit = onExit,
            )
        }
    }
}

@Composable
private fun ExternalSubtitleOverlay(ctrl: PlayerController, settings: AppSettings, raised: Boolean) {
    if (!ctrl.subs.active) return
    val text = ctrl.subs.textAt(ctrl.positionMs) ?: return
    Box(Modifier.fillMaxSize().padding(bottom = if (raised) 190.dp else 56.dp, start = 80.dp, end = 80.dp)) {
        Text(
            text = text,
            color = Color.White,
            fontSize = (28 * settings.subtitleScale).sp,
            lineHeight = (34 * settings.subtitleScale).sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            style = TextStyle(shadow = Shadow(Color.Black, Offset(2f, 2f), 6f)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(6.dp))
                .background(if (settings.subtitleBackground) Color.Black.copy(alpha = 0.6f) else Color.Transparent)
                .padding(horizontal = 14.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun TopInfo(ctrl: PlayerController, modifier: Modifier) {
    val ch = ctrl.current
    val time by produceState(initialValue = clockText()) {
        while (true) {
            delay(10_000)
            value = clockText()
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)))
            .padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 48.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            if (ctrl.isLive) {
                LiveBadge()
                Spacer(Modifier.height(8.dp))
            }
            Text(
                ch.seriesName ?: ch.name, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val sub = when {
                ch.episodeLabel != null -> "${ch.episodeLabel} · ${ch.name}"
                ctrl.isLive -> "${ch.group} · ${ctrl.index + 1} of ${ctrl.queue.size}"
                else -> ch.group
            }
            Text(sub, color = SV.Dim, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(time, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun clockText(): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())

@Composable
private fun BottomControls(
    ctrl: PlayerController,
    isFavorite: Boolean,
    playFocus: FocusRequester,
    seekFocus: FocusRequester,
    onPanel: (Panel) -> Unit,
    modifier: Modifier,
) {
    val isLive = ctrl.isLive
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
            .padding(start = 48.dp, end = 48.dp, top = 48.dp, bottom = 32.dp),
    ) {
        if (!isLive) {
            SeekBar(ctrl, seekFocus)
            Spacer(Modifier.height(18.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (ctrl.hasPrevious) TvIconButton(Icons.Rounded.SkipPrevious, "Previous", { ctrl.previous() })
                if (!isLive) TvIconButton(Icons.Rounded.Replay10, "Back 10 seconds", { ctrl.seekBy(-10_000) })
                TvIconButton(
                    if (ctrl.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    "Play or pause",
                    { ctrl.togglePlay() },
                    Modifier.focusRequester(playFocus),
                    size = 66.dp,
                )
                if (!isLive) TvIconButton(Icons.Rounded.Forward10, "Forward 10 seconds", { ctrl.seekBy(10_000) })
                if (ctrl.hasNext) TvIconButton(Icons.Rounded.SkipNext, "Next", { ctrl.next() })
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isLive) TvButton("Channels", { onPanel(Panel.CHANNELS) }, icon = Icons.Rounded.Apps, compact = true)
                TvButton(
                    if (ctrl.subs.active) "Subtitles (on)" else "Subtitles",
                    { onPanel(Panel.SUBTITLES) }, icon = Icons.Rounded.Subtitles, compact = true,
                )
                TvButton("Audio", { onPanel(Panel.AUDIO) }, icon = Icons.Rounded.Audiotrack, compact = true)
                if (!isLive) TvButton("Speed ${speedLabel(ctrl.speed)}", { onPanel(Panel.SPEED) }, icon = Icons.Rounded.Speed, compact = true)
                TvButton("Picture: ${ctrl.resizeLabel}", { ctrl.cycleResize() }, icon = Icons.Rounded.AspectRatio, compact = true)
                TvButton(
                    if (isFavorite) "In My List" else "Add to My List",
                    { Repo.toggleFavorite(ctrl.current.favoriteId) },
                    icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    compact = true,
                )
            }
        }
    }
}

fun speedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"

/** Focusable progress bar: left/right scrub while it has focus (hold to scrub faster). */
@Composable
private fun SeekBar(ctrl: PlayerController, focus: FocusRequester) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pos = ctrl.positionMs
    val dur = ctrl.durationMs
    val fraction = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val buffered = if (dur > 0) (ctrl.bufferedMs.toFloat() / dur).coerceIn(0f, 1f) else 0f
    Column {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(22.dp)
                .focusRequester(focus)
                .onKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val repeat = ev.nativeKeyEvent.repeatCount
                    val step = when {
                        repeat > 20 -> 60_000L
                        repeat > 6 -> 30_000L
                        else -> 10_000L
                    }
                    when (ev.key) {
                        Key.DirectionLeft -> {
                            ctrl.seekBy(-step)
                            true
                        }
                        Key.DirectionRight -> {
                            ctrl.seekBy(step)
                            true
                        }
                        Key.DirectionCenter, Key.Enter -> {
                            ctrl.togglePlay()
                            true
                        }
                        else -> false
                    }
                }
                .focusable(interactionSource = interaction),
            contentAlignment = Alignment.CenterStart,
        ) {
            val barHeight = if (focused) 8.dp else 5.dp
            Box(
                Modifier.fillMaxWidth().height(barHeight).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.25f))
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(buffered).background(Color.White.copy(alpha = 0.35f)))
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(SV.Accent))
            }
            if (focused) {
                val thumb = 18.dp
                Box(
                    Modifier
                        .offset(x = (maxWidth - thumb) * fraction)
                        .size(thumb)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Text(formatDuration(pos), color = Color.White, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            Text(if (dur > 0) "-" + formatDuration(dur - pos) else "", color = SV.Dim, fontSize = 15.sp)
        }
    }
}

@Composable
private fun ChannelBanner(ctrl: PlayerController) {
    val ch = ctrl.current
    Row(
        Modifier
            .padding(40.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.8f))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${ctrl.index + 1}", color = SV.Accent, fontSize = 30.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(16.dp))
        ArtImage(
            ch.logo, ch.name,
            Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(8.dp)).background(SV.Surface2),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(ch.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${ch.group}  ·  ▲▼ change channel  ·  ◀ channel list", color = SV.Dim, fontSize = 13.sp)
        }
    }
}

@Composable
private fun NextEpisodeButton(next: Channel, focus: FocusRequester, onClick: () -> Unit, modifier: Modifier) {
    TvButton(
        text = "Next episode${next.episodeLabel?.let { " · $it" } ?: ""}",
        onClick = onClick,
        icon = Icons.Rounded.SkipNext,
        modifier = modifier.focusRequester(focus),
    )
}

@Composable
private fun UpNextOverlay(next: Channel, focus: FocusRequester, onPlay: () -> Unit, onCancel: () -> Unit) {
    var seconds by remember(next.id) { mutableIntStateOf(10) }
    LaunchedEffect(next.id) {
        while (seconds > 0) {
            delay(1000)
            seconds--
        }
        onPlay()
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f))) {
        Row(Modifier.align(Alignment.BottomEnd).padding(56.dp), verticalAlignment = Alignment.CenterVertically) {
            ArtImage(next.logo, next.name, Modifier.width(260.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.width(24.dp))
            Column(Modifier.widthIn(max = 420.dp)) {
                Text("Next episode in $seconds", color = SV.Dim, fontSize = 16.sp)
                Text(
                    listOfNotNull(next.episodeLabel, next.name).joinToString(" · "),
                    color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton("Play now", onPlay, icon = Icons.Rounded.PlayArrow, primary = true, modifier = Modifier.focusRequester(focus))
                    TvButton("Back to browse", onCancel, icon = Icons.Rounded.Close)
                }
            }
        }
    }
}

@Composable
private fun ErrorOverlay(message: String, focus: FocusRequester, onRetry: () -> Unit, onNext: (() -> Unit)?, onExit: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 620.dp).clip(RoundedCornerShape(20.dp)).background(SV.Surface).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Can't play this stream", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(message, color = SV.Dim, fontSize = 16.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("Try again", onRetry, icon = Icons.Rounded.Refresh, primary = true, modifier = Modifier.focusRequester(focus))
                if (onNext != null) TvButton("Next", onNext, icon = Icons.Rounded.SkipNext)
                TvButton("Close", onExit, icon = Icons.Rounded.Close)
            }
        }
    }
}
