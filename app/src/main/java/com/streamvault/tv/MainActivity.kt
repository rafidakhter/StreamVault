package com.streamvault.tv

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamvault.tv.data.Repo
import com.streamvault.tv.data.UserProfile
import com.streamvault.tv.remote.PhoneRemote
import com.streamvault.tv.ui.*
import kotlinx.coroutines.delay

class StreamVaultApplication : Application(), coil.ImageLoaderFactory {
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(0.12).build() }
        .crossfade(false)
        .build()
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        com.streamvault.tv.remote.PhoneRemote.initialize(this)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(applicationContext)
        setContent {
            StreamVaultTheme {
                AppRoot()
            }
        }
    }
}

enum class Section(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    CONTINUE("Continue Watching", Icons.Rounded.History),
    LIVE("Live TV", Icons.Rounded.LiveTv),
    MOVIES("Movies", Icons.Rounded.Movie),
    SERIES("Series", Icons.Rounded.Tv),
    MY_LIST("My List", Icons.Rounded.Favorite),
    PLAYLISTS("Playlists", Icons.AutoMirrored.Rounded.PlaylistPlay),
    SEARCH("Search", Icons.Rounded.Search),
    PHONE_REMOTE("Phone remote", Icons.Rounded.Smartphone),
    SETTINGS("Settings", Icons.Rounded.Settings),
}

private val RailCollapsed = 64.dp

@Composable
fun AppRoot() {
    val profile by Repo.activeProfile.collectAsState()
    LaunchedEffect(profile.id) { PhoneRemote.disconnectPhones() }
    key(profile.id) { ProfileAppRoot() }
}

@Composable
private fun ProfileAppRoot() {
    val activeProfile by Repo.activeProfile.collectAsState()
    var showProfiles by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val playlists by Repo.playlists.collectAsState()
    val lib by Repo.library.collectAsState()
    val loading by Repo.loading.collectAsState()
    val status by Repo.status.collectAsState()
    val favList by Repo.favorites.collectAsState()
    val progress by Repo.progress.collectAsState()
    val recent by Repo.recent.collectAsState()
    val librarySettings by Repo.settings.collectAsState()

    var section by rememberSaveable { mutableStateOf(Section.HOME) }
    var settingsFocusRequest by remember { mutableIntStateOf(0) }
    var phoneFocusRequest by remember { mutableIntStateOf(0) }
    val stack = remember { mutableStateListOf<Route>() }
    val nav = remember(stack) { AppNav(context, stack) }
    val searchStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val listStates = remember { HashMap<String, LazyListState>() }
    fun listState(key: String) = listStates.getOrPut(key) { LazyListState() }

    BackHandler(enabled = stack.isNotEmpty()) { nav.back() }
    BackHandler(enabled = stack.isEmpty() && section != Section.HOME && playlists.isNotEmpty()) { section = Section.HOME }

    LaunchedEffect(status) {
        if (status != null) {
            delay(4000)
            Repo.clearStatus()
        }
    }

    CompositionLocalProvider(LocalNav provides nav) {
        Box(Modifier.fillMaxSize().background(SV.Ambient)) {
            val top = stack.lastOrNull()
            when {
                playlists.isEmpty() -> WelcomeScreen()
                top != null -> when (top) {
                    is Route.UserPlaylist -> searchStates.SaveableStateProvider("user-playlist:${top.id}") { UserPlaylistsScreen(top.id) }
                    is Route.MovieDetails -> MovieDetailsScreen(top.id)
                    is Route.SeriesDetails -> SeriesDetailsScreen(top.key)
                    is Route.Group -> GroupScreen(top)
                    is Route.ContinueWatching -> ContinueWatchingScreen(top.type)
                    is Route.Search -> searchStates.SaveableStateProvider("search:${top.sessionId}") {
                        SearchScreen(lib, favList, top.type, top.filter)
                    }
                    is Route.CatalogDetails -> searchStates.SaveableStateProvider("catalog-details:$top") { CatalogDetailsScreen(top) }
                    is Route.CatalogGroup -> searchStates.SaveableStateProvider("catalog-group:$top") { CatalogGroupScreen(top) }
                }
                else -> {
                    Box(Modifier.fillMaxSize().padding(start = RailCollapsed)) {
                        if (lib.isEmpty && loading && section != Section.SETTINGS) {
                            EmptyState("Loading your library…", "Large playlists can take a few seconds the first time.")
                        } else {
                            when (section) {
                                Section.HOME -> key(section) {
                                    val rows = remember(lib, favList, progress, recent, librarySettings) { homeRows(lib, favList, progress, recent, nav) }
                                    BrowseScreen(rows, listState("home"), "Nothing here yet", "Your playlist didn't contain anything playable.")
                                }
                                Section.LIVE -> LiveScreen(lib, favList, recent)
                                Section.CONTINUE -> key(section) { ContinueWatchingScreen() }
                                Section.MOVIES -> key(section) {
                                    if (librarySettings.optimizeLibrary) searchStates.SaveableStateProvider("organized-movies") { OrganizedLibraryScreen(com.streamvault.tv.data.ContentType.MOVIE) }
                                    else {
                                    val rows = remember(lib, favList, progress) { movieRows(lib, favList, progress, nav) }
                                    BrowseScreen(rows, listState("movies"), "No movies found", "Movies are detected from group names like \"Movies\" or \"VOD\" and from video file links.", showCategoryPicker = true, searchType = com.streamvault.tv.data.ContentType.MOVIE)
                                    }
                                }
                                Section.SERIES -> key(section) {
                                    if (librarySettings.optimizeLibrary) searchStates.SaveableStateProvider("organized-series") { OrganizedLibraryScreen(com.streamvault.tv.data.ContentType.SERIES) }
                                    else {
                                    val rows = remember(lib, favList, progress) { seriesRows(lib, favList, progress, nav) }
                                    BrowseScreen(rows, listState("series"), "No series found", "Series are detected from group names and S01E01-style titles.", showCategoryPicker = true, searchType = com.streamvault.tv.data.ContentType.SERIES)
                                    }
                                }
                                Section.MY_LIST -> key(section) {
                                    val rows = remember(lib, favList, progress, recent) { myListRows(lib, favList, progress, recent, nav) }
                                    BrowseScreen(rows, listState("mylist"), "Your list is empty", "Hold OK on any movie, series or channel to add it here.")
                                }
                                Section.PLAYLISTS -> searchStates.SaveableStateProvider("user-playlists") { UserPlaylistsScreen() }
                                Section.SEARCH -> searchStates.SaveableStateProvider("global-search") {
                                    SearchScreen(lib, favList)
                                }
                                Section.PHONE_REMOTE -> PhoneRemoteScreen(phoneFocusRequest)
                                Section.SETTINGS -> SettingsScreen(settingsFocusRequest)
                            }
                        }
                    }
                    NavRail(section, activeProfile, onProfiles = { showProfiles = true }, onSelect = {
                        section = it
                        if (it == Section.SETTINGS) settingsFocusRequest++
                        if (it == Section.PHONE_REMOTE) phoneFocusRequest++
                    })
                }
            }

            if (showProfiles) ProfilePickerDialog(onDismiss = { showProfiles = false }) { selected ->
                showProfiles = false
                if (selected.id != activeProfile.id) {
                    Repo.switchProfile(selected.id)
                }
            }

            if (loading) {
                Row(
                    Modifier.align(Alignment.TopEnd).padding(20.dp).clip(RoundedCornerShape(50))
                        .background(SV.Surface2).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = SV.Accent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Updating library", color = Color.White, fontSize = 13.sp)
                }
            }
            AnimatedVisibility(
                visible = status != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
            ) {
                Text(
                    status.orEmpty(),
                    color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(SV.Surface2).padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** Collapsed icon rail that expands with labels when it has focus (Disney+ / Google TV style). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun NavRail(selected: Section, profile: UserProfile, onProfiles: () -> Unit, onSelect: (Section) -> Unit) {
    var hasFocus by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (hasFocus) 224.dp else RailCollapsed, label = "railWidth")
    Column(
        Modifier
            .fillMaxHeight()
            .width(width)
            .background(
                if (hasFocus) Brush.horizontalGradient(0f to SV.Surface.copy(alpha = 0.97f), 0.85f to SV.Bg.copy(alpha = 0.92f), 1f to SV.Bg.copy(alpha = 0.82f))
                else Brush.horizontalGradient(0f to SV.Bg.copy(alpha = 0.94f), 1f to SV.Bg.copy(alpha = 0.62f))
            )
            .onFocusChanged { hasFocus = it.hasFocus }
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val avatarInteraction = remember { MutableInteractionSource() }
        val avatarFocused by avatarInteraction.collectIsFocusedAsState()
        Row(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(14.dp))
            .background(if (avatarFocused) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .then(if (avatarFocused) Modifier.border(2.dp, Color.White, RoundedCornerShape(14.dp)) else Modifier)
            .clickable(interactionSource = avatarInteraction, indication = null, onClick = onProfiles)
            .padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            ProfileAvatar(profile, Modifier.size(34.dp))
            if (hasFocus) {
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(profile.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text("Switch profile", color = SV.Dim, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Section.entries.forEach { s ->
            NavItem(s, s == selected, hasFocus) { onSelect(s) }
        }
    }
}

@Composable
private fun NavItem(section: Section, selected: Boolean, expanded: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(when {
                focused -> Color.White
                selected -> SV.Accent.copy(alpha = 0.18f)
                else -> Color.Transparent
            })
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            section.icon, section.label,
            tint = when {
                focused -> Color.Black
                selected -> Color.White
                else -> SV.Dim
            },
            modifier = Modifier.size(22.dp),
        )
        if (expanded) {
            Spacer(Modifier.width(12.dp))
            Text(
                section.label,
                color = if (focused) Color.Black else if (selected) Color.White else SV.Dim,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 14.sp,
                maxLines = 1,
            )
        }
    }
}
