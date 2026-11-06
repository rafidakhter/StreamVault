package com.streamvault.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamvault.tv.BuildConfig
import com.streamvault.tv.data.Playlist
import com.streamvault.tv.data.Repo
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AddPlaylistForm(modifier: Modifier = Modifier, focusRequester: FocusRequester? = null) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(SV.Surface).padding(22.dp)) {
        Text("Add a playlist", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Paste the M3U / M3U8 link from your IPTV provider", color = SV.Dim, fontSize = 14.sp)
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = name, onValueChange = { name = it }, singleLine = true,
            label = { Text("Name (optional)") },
            modifier = Modifier.fillMaxWidth().then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = url, onValueChange = { url = it }, singleLine = true,
            label = { Text("Playlist URL") },
            placeholder = { Text("http://…/get.php?username=…&type=m3u_plus") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        TvButton(
            text = if (busy) "Loading…" else "Add playlist",
            icon = Icons.Rounded.Add,
            primary = true,
            onClick = {
                if (busy) return@TvButton
                busy = true
                message = "Downloading playlist…"
                scope.launch {
                    val result = Repo.addPlaylist(name, url)
                    message = result
                    busy = false
                    if (result.startsWith("Added")) {
                        name = ""
                        url = ""
                    }
                }
            },
        )
        if (message != null) {
            Spacer(Modifier.height(10.dp))
            Text(message.orEmpty(), color = SV.Accent, fontSize = 14.sp)
        }
    }
}

@Composable
fun WelcomeScreen() {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.safeRequestFocus() }
    Column(Modifier.fillMaxSize().background(SV.Bg).padding(horizontal = 64.dp, vertical = 40.dp)) {
        Text("StreamVault", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Black)
        Text(
            "Your IPTV, with a proper TV experience. Add a playlist to get started.",
            color = SV.Dim, fontSize = 18.sp,
        )
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            AddPlaylistForm(Modifier.weight(1f), focusRequester = firstFocus)
            PhoneLinkCard(Modifier.width(320.dp))
        }
    }
}

private val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

@Composable
fun SettingsScreen(focusRequest: Int = 0) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(focusRequest) { firstFocus.safeRequestFocus() }
    val playlists by Repo.playlists.collectAsState()
    val settings by Repo.settings.collectAsState()
    val loading by Repo.loading.collectAsState()

    var apiKey by remember(settings.osApiKey) { mutableStateOf(settings.osApiKey) }
    var username by remember { mutableStateOf(settings.osUsername) }
    var password by remember { mutableStateOf(settings.osPassword) }
    var language by remember { mutableStateOf(settings.subtitleLang) }
    var saved by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text("Settings", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black) }
        item { SectionTitle("Optimize library organization") }
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SV.Surface).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Clean title names, group language and quality versions, and browse by provider and genre. Original streams, favourites and history are retained.", color = SV.Dim, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton("Optimized", modifier = Modifier.focusRequester(firstFocus), primary = settings.optimizeLibrary, onClick = { Repo.updateSettings { it.copy(optimizeLibrary = true) } })
                    TvButton("Original categories", primary = !settings.optimizeLibrary, onClick = { Repo.updateSettings { it.copy(optimizeLibrary = false) } })
                }
                CategoryPickerButton(listOf("Any", "English", "French", "Spanish", "German", "Italian", "Arabic", "Hindi", "Portuguese", "Turkish").map { CategoryChoice(it, it) }, settings.preferredLanguage,
                    buttonLabel = "Preferred language", dialogTitle = "Choose preferred language") {
                    Repo.updateSettings { s -> s.copy(preferredLanguage = it.key) }
                }
                Text("Preferred language: ${settings.preferredLanguage}", color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Maximum preferred quality", color = Color.White, modifier = Modifier.padding(top = 14.dp))
                    listOf(720, 1080, 2160).forEach { quality -> TvButton(com.streamvault.tv.data.LibraryOrganization.qualityLabel(quality), primary = settings.maximumQuality == quality,
                        onClick = { Repo.updateSettings { it.copy(maximumQuality = quality) } }) }
                }
                Text("Preferences choose a default version; every available version remains selectable. Language labels come from the playlist and may not describe every audio track.", color = SV.Dim, fontSize = 13.sp)
                TvButton(if (settings.showOriginalNames) "Original names: shown" else "Original names: hidden", onClick = { Repo.updateSettings { it.copy(showOriginalNames = !it.showOriginalNames) } })
            }
        }

        item { SectionTitle("Playlists") }
        items(playlists, key = { it.id }) { pl -> PlaylistRow(pl, loading) }
        if (playlists.size > 1) {
            item {
                TvButton("Refresh all playlists", icon = Icons.Rounded.Refresh, onClick = { Repo.refresh() })
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                AddPlaylistForm(Modifier.weight(1f))
                PhoneLinkCard(Modifier.width(320.dp))
            }
        }

        item { SectionTitle("Subtitles") }
        item {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SV.Surface).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Search and download subtitles from OpenSubtitles.com while you watch. " +
                        "Get a free API key at opensubtitles.com → Profile → API consumers. " +
                        "Logging in is optional and raises the daily download limit.",
                    color = SV.Dim, fontSize = 14.sp,
                )
                OutlinedTextField(apiKey, { apiKey = it; saved = false }, singleLine = true, label = { Text("OpenSubtitles API key") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(username, { username = it; saved = false }, singleLine = true, label = { Text("Username (optional)") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(
                        password, { password = it; saved = false }, singleLine = true,
                        label = { Text("Password (optional)") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(language, { language = it; saved = false }, singleLine = true, label = { Text("Language (en, fr, es…)") }, modifier = Modifier.width(200.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TvButton(
                        text = "Save subtitle settings",
                        primary = true,
                        onClick = {
                            Repo.updateSettings {
                                it.copy(
                                    osApiKey = apiKey.trim(),
                                    osUsername = username.trim(),
                                    osPassword = password,
                                    subtitleLang = language.trim().lowercase().ifBlank { "en" },
                                )
                            }
                            saved = true
                        },
                    )
                    if (saved) {
                        Spacer(Modifier.width(14.dp))
                        Text("Saved", color = SV.Accent)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Subtitle size", color = Color.White, modifier = Modifier.width(160.dp))
                listOf("Small" to 0.8f, "Medium" to 1f, "Large" to 1.3f, "Extra large" to 1.6f).forEach { (label, scale) ->
                    TvButton(
                        text = label,
                        primary = settings.subtitleScale == scale,
                        onClick = { Repo.updateSettings { it.copy(subtitleScale = scale) } },
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Subtitle background", color = Color.White, modifier = Modifier.width(160.dp))
                TvButton("On", primary = settings.subtitleBackground, onClick = { Repo.updateSettings { it.copy(subtitleBackground = true) } })
                TvButton("Off (outline only)", primary = !settings.subtitleBackground, onClick = { Repo.updateSettings { it.copy(subtitleBackground = false) } })
            }
        }

        item { SectionTitle("Playback") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Autoplay next episode", color = Color.White, modifier = Modifier.width(220.dp))
                TvButton("On", primary = settings.autoplayNext, onClick = { Repo.updateSettings { it.copy(autoplayNext = true) } })
                TvButton("Off", primary = !settings.autoplayNext, onClick = { Repo.updateSettings { it.copy(autoplayNext = false) } })
            }
        }

        item { SectionTitle("About") }
        item {
            Text(
                "StreamVault ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}). " +
                    "Updates: install the newest APK from your GitHub releases over this one.",
                color = SV.Dim, fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
}

@Composable
private fun PlaylistRow(pl: Playlist, loading: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SV.Surface).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(pl.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(pl.url, color = SV.Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${pl.channelCount} items" + if (pl.updatedAt > 0) " · updated ${dateFormat.format(Date(pl.updatedAt))}" else "",
                color = SV.Dim, fontSize = 13.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        TvButton(if (loading) "Updating…" else "Refresh", icon = Icons.Rounded.Refresh, compact = true, onClick = { Repo.refresh(pl.id) })
        Spacer(Modifier.width(10.dp))
        var confirm by remember { mutableStateOf(false) }
        TvButton(
            text = if (confirm) "Press again to delete" else "Delete",
            icon = Icons.Rounded.Delete,
            compact = !confirm,
            onClick = { if (confirm) Repo.removePlaylist(pl.id) else confirm = true },
        )
    }
}
