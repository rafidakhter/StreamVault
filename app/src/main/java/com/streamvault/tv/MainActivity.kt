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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
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
    LIVE("Live TV", Icons.Rounded.LiveTv),
    MOVIES("Movies", Icons.Rounded.Movie),
    SERIES("Series", Icons.Rounded.Tv),
    MY_LIST("My List", Icons.Rounded.Favorite),
    SEARCH("Search", Icons.Rounded.Search),
    SETTINGS("Settings", Icons.Rounded.Settings),
}

private val RailCollapsed = 80.dp

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val playlists by Repo.playlists.collectAsState()
    val lib by Repo.library.collectAsState()
    val loading by Repo.loading.collectAsState()
    val status by Repo.status.collectAsState()
    val favList by Repo.favorites.collectAsState()
    val progress by Repo.progress.collectAsState()
    val recent by Repo.recent.collectAsState()

    var section by rememberSaveable { mutableStateOf(Section.HOME) }
    val stack = remember { mutableStateListOf<Route>() }
    val nav = remember(stack) { AppNav(context, stack) }
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
        Box(Modifier.fillMaxSize().background(SV.Bg)) {
            val top = stack.lastOrNull()
            when {
                playlists.isEmpty() -> WelcomeScreen()
                top != null -> when (top) {
                    is Route.MovieDetails -> MovieDetailsScreen(top.id)
                    is Route.SeriesDetails -> SeriesDetailsScreen(top.key)
                    is Route.Group -> GroupScreen(top)
                }
                else -> {
                    Box(Modifier.fillMaxSize().padding(start = RailCollapsed)) {
                        if (lib.isEmpty && loading && section != Section.SETTINGS) {
                            EmptyState("Loading your library…", "Large playlists can take a few seconds the first time.")
                        } else {
                            when (section) {
                                Section.HOME -> key(section) {
                                    val rows = remember(lib, favList, progress, recent) { homeRows(lib, favList, progress, recent, nav) }
                                    BrowseScreen(rows, listState("home"), "Nothing here yet", "Your playlist didn't contain anything playable.")
                                }
                                Section.LIVE -> LiveScreen(lib, favList, recent)
                                Section.MOVIES -> key(section) {
                                    val rows = remember(lib, favList, progress) { movieRows(lib, favList, progress, nav) }
                                    BrowseScreen(rows, listState("movies"), "No movies found", "Movies are detected from group names like \"Movies\" or \"VOD\" and from video file links.")
                                }
                                Section.SERIES -> key(section) {
                                    val rows = remember(lib, favList, progress) { seriesRows(lib, favList, progress, nav) }
                                    BrowseScreen(rows, listState("series"), "No series found", "Series are detected from group names and S01E01-style titles.")
                                }
                                Section.MY_LIST -> key(section) {
                                    val rows = remember(lib, favList, progress, recent) { myListRows(lib, favList, progress, recent, nav) }
                                    BrowseScreen(rows, listState("mylist"), "Your list is empty", "Hold OK on any movie, series or channel to add it here.")
                                }
                                Section.SEARCH -> SearchScreen(lib, favList)
                                Section.SETTINGS -> SettingsScreen()
                            }
                        }
                    }
                    NavRail(section, onSelect = { section = it })
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
private fun NavRail(selected: Section, onSelect: (Section) -> Unit) {
    var hasFocus by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (hasFocus) 230.dp else RailCollapsed, label = "railWidth")
    Column(
        Modifier
            .fillMaxHeight()
            .width(width)
            .background(
                if (hasFocus) Brush.horizontalGradient(0f to Color.Black, 0.85f to Color.Black.copy(alpha = 0.92f), 1f to Color.Transparent)
                else Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.75f), 1f to Color.Transparent)
            )
            .onFocusChanged { hasFocus = it.hasFocus }
            .padding(vertical = 28.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.padding(start = 6.dp, bottom = 22.dp).size(44.dp).clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(SV.Accent, SV.Accent2))),
            contentAlignment = Alignment.Center,
        ) {
            Text("SV", color = Color.White, fontWeight = FontWeight.Black, fontSize = 17.sp)
        }
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
            .height(50.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Color.White else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            section.icon, section.label,
            tint = when {
                focused -> Color.Black
                selected -> Color.White
                else -> SV.Dim
            },
            modifier = Modifier.size(24.dp),
        )
        if (expanded) {
            Spacer(Modifier.width(16.dp))
            Text(
                section.label,
                color = if (focused) Color.Black else if (selected) Color.White else SV.Dim,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 16.sp,
                maxLines = 1,
            )
        } else if (selected) {
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(5.dp).clip(CircleShape).background(SV.Accent))
        }
    }
}
