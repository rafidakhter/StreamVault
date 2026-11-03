package com.streamvault.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import com.streamvault.tv.data.AppSettings
import com.streamvault.tv.data.Repo
import com.streamvault.tv.ui.ArtImage
import com.streamvault.tv.ui.PanelItem
import com.streamvault.tv.ui.SV
import com.streamvault.tv.ui.TvButton
import kotlin.math.abs

@Composable
private fun SidePanel(
    title: String,
    alignEnd: Boolean = true,
    width: Dp = 500.dp,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
        Column(
            Modifier
                .align(if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart)
                .fillMaxHeight()
                .width(width)
                .background(Color(0xF2101018))
                .padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(14.dp))
            LazyColumn(
                state = state,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun PanelHeader(text: String) {
    Text(
        text.uppercase(), color = SV.Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
        modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun Chip(label: String, selected: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
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
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(label, color = if (focused) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

private fun offsetText(ms: Long): String = when {
    ms == 0L -> "In sync (no offset)"
    ms > 0 -> "Delayed by %.1fs".format(ms / 1000f)
    else -> "Shown %.1fs earlier".format(abs(ms) / 1000f)
}

@Composable
fun SubtitlesPanel(ctrl: PlayerController, settings: AppSettings, firstFocus: FocusRequester) {
    val search = ctrl.search
    val textTracks = ctrl.trackOptions(C.TRACK_TYPE_TEXT)
    val externalOn = ctrl.subs.active
    SidePanel("Subtitles") {
        item {
            PanelItem(
                "Off",
                onClick = { ctrl.subtitlesOff() },
                selected = !externalOn && textTracks.none { it.selected },
                modifier = Modifier.focusRequester(firstFocus),
            )
        }

        if (externalOn) {
            item { PanelHeader("Timing") }
            item {
                Column(Modifier.padding(horizontal = 4.dp)) {
                    Text(ctrl.subs.label.orEmpty(), color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(offsetText(ctrl.subs.offsetMs), color = SV.Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Text appears before the voice? Press +.  After the voice? Press −.",
                        color = SV.Dim, fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("−1s") { ctrl.adjustOffset(-1000) }
                        Chip("−0.1s") { ctrl.adjustOffset(-100) }
                        Chip("Reset") { ctrl.subs.offsetMs = 0L }
                        Chip("+0.1s") { ctrl.adjustOffset(100) }
                        Chip("+1s") { ctrl.adjustOffset(1000) }
                    }
                }
            }
        }

        if (textTracks.isNotEmpty()) {
            item { PanelHeader("From the stream") }
            itemsIndexed(textTracks, key = { i, t -> "track$i-${t.label}" }) { _, t ->
                PanelItem(t.label, onClick = { ctrl.selectTrack(t) }, selected = t.selected && !externalOn)
            }
        }

        item { PanelHeader("Find on OpenSubtitles") }
        item {
            OutlinedTextField(
                value = search.query,
                onValueChange = { search.query = it },
                singleLine = true,
                label = { Text("Title") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { ctrl.searchSubtitles() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = search.language,
                    onValueChange = { search.language = it },
                    singleLine = true,
                    label = { Text("Lang") },
                    modifier = Modifier.width(110.dp),
                )
                if (search.episode != null) {
                    Text("S${search.season ?: 1} E${search.episode}", color = SV.Dim, fontSize = 14.sp)
                }
                Spacer(Modifier.weight(1f))
                TvButton(if (search.busy) "Working…" else "Search", { ctrl.searchSubtitles() }, icon = Icons.Rounded.Search, primary = true)
            }
        }
        val status = search.status
        if (status != null) {
            item { Text(status, color = SV.Dim, fontSize = 13.sp, modifier = Modifier.padding(4.dp)) }
        }
        itemsIndexed(search.results.take(50), key = { _, r -> "os${r.fileId}" }) { _, r ->
            PanelItem(
                title = r.release.ifBlank { r.fileName },
                subtitle = buildString {
                    append(r.language.uppercase())
                    append(" · ")
                    append(r.downloads)
                    append(" downloads")
                    if (r.hearingImpaired) append(" · SDH")
                },
                onClick = { ctrl.downloadSubtitle(r) },
            )
        }

        item { PanelHeader("Load from a link") }
        item {
            OutlinedTextField(
                value = search.urlInput,
                onValueChange = { search.urlInput = it },
                singleLine = true,
                label = { Text("https://…/subtitle.srt") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { TvButton("Load subtitle file", { ctrl.loadSubtitleUrl(search.urlInput) }, icon = Icons.Rounded.Link) }

        item { PanelHeader("Appearance") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Small" to 0.8f, "Medium" to 1f, "Large" to 1.3f, "XL" to 1.6f).forEach { (label, scale) ->
                    Chip(label, selected = settings.subtitleScale == scale) {
                        Repo.updateSettings { it.copy(subtitleScale = scale) }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                Chip("Background", selected = settings.subtitleBackground) {
                    Repo.updateSettings { it.copy(subtitleBackground = true) }
                }
                Chip("Outline only", selected = !settings.subtitleBackground) {
                    Repo.updateSettings { it.copy(subtitleBackground = false) }
                }
            }
        }
    }
}

@Composable
fun AudioPanel(ctrl: PlayerController, firstFocus: FocusRequester) {
    val options = ctrl.trackOptions(C.TRACK_TYPE_AUDIO)
    SidePanel("Audio") {
        if (options.isEmpty()) {
            item {
                PanelItem("Default audio", onClick = {}, selected = true, modifier = Modifier.focusRequester(firstFocus))
            }
            item { Text("This stream only has one audio track.", color = SV.Dim, fontSize = 13.sp, modifier = Modifier.padding(4.dp)) }
        } else {
            itemsIndexed(options, key = { i, o -> "a$i-${o.label}" }) { i, o ->
                PanelItem(
                    o.label,
                    onClick = { ctrl.selectTrack(o) },
                    selected = o.selected,
                    modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
    }
}

private val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

@Composable
fun SpeedPanel(ctrl: PlayerController, firstFocus: FocusRequester, onDone: () -> Unit) {
    SidePanel("Playback speed", width = 360.dp) {
        itemsIndexed(speeds, key = { _, s -> "speed$s" }) { _, s ->
            PanelItem(
                if (s == 1f) "1x (Normal)" else speedLabel(s),
                onClick = {
                    ctrl.setPlaybackSpeed(s)
                    onDone()
                },
                selected = ctrl.speed == s,
                modifier = if (ctrl.speed == s) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }
    }
}

/** Live channel list on the left (OK switches channel, Back closes). */
@Composable
fun ChannelsPanel(ctrl: PlayerController, firstFocus: FocusRequester, onDone: () -> Unit) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (ctrl.index - 3).coerceAtLeast(0))
    val favList by Repo.favorites.collectAsState()
    SidePanel(ctrl.current.group, alignEnd = false, width = 460.dp, state = state) {
        itemsIndexed(ctrl.queue, key = { _, c -> c.id }) { i, c ->
            PanelItem(
                title = c.name,
                subtitle = if (c.id in favList) "★ Favourite" else null,
                selected = i == ctrl.index,
                onClick = {
                    if (i != ctrl.index) ctrl.playIndex(i)
                    onDone()
                },
                modifier = if (i == ctrl.index) Modifier.focusRequester(firstFocus) else Modifier,
                leading = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", color = SV.Dim, fontSize = 13.sp, modifier = Modifier.width(36.dp))
                        ArtImage(
                            c.logo, c.name,
                            Modifier.size(width = 64.dp, height = 36.dp).clip(RoundedCornerShape(6.dp)).background(SV.Surface2),
                            contentScale = ContentScale.Fit,
                        )
                    }
                },
            )
        }
    }
}
