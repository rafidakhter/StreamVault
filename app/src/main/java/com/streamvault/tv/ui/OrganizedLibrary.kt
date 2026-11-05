package com.streamvault.tv.ui

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.streamvault.tv.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CatalogFilter(val provider: CatalogProvider = CatalogProvider.ALL, val genre: String? = null,
    val language: String = "Any", val quality: Int = 0, val rating: Int = 0, val sort: String = "Library order")

private fun eligible(title: CatalogTitle, filter: CatalogFilter): List<CatalogVersion> {
    // A genre belongs to the title, not to a particular language/quality encoding.
    if (filter.genre != null && title.versions.none { filter.genre in it.genres }) return emptyList()
    if (filter.provider == CatalogProvider.ALL && filter.language == "Any" && filter.quality == 0 && filter.genre == null) return title.versions
    return title.versions.filterTo(ArrayList(title.versions.size)) {
    LibraryOrganization.providerMatches(it.provider, filter.provider) &&
        (filter.language == "Any" || it.language == filter.language) &&
        (filter.quality == 0 || it.quality == filter.quality)
}
}

fun catalogSelection(titles: List<CatalogTitle>, filter: CatalogFilter): List<Pair<CatalogTitle, List<CatalogVersion>>> {
    val selected = titles.asSequence().filter { filter.rating == 0 || (it.rating ?: -1.0) >= filter.rating }
        .map { it to eligible(it, filter) }.filter { it.second.isNotEmpty() }.toList()
    return when(filter.sort) {
        "Title A–Z" -> selected.sortedBy { it.first.name.lowercase() }
        "Rating" -> selected.sortedByDescending { it.first.rating ?: -1.0 }
        "Release year" -> selected.sortedByDescending { it.first.year ?: 0 }
        else -> selected
    }
}

fun playCatalogVersion(version: CatalogVersion, nav: AppNav, startOver: Boolean = false) {
    val queue = version.movie?.let { listOf(it) } ?: version.series!!.episodes
    val latest = if (startOver) null else queue.indices.mapNotNull { index -> Repo.progressFor(queue[index])?.let { index to it } }
        .maxByOrNull { it.second.updatedAt }
    val index = when {
        latest == null -> 0
        latest.second.isFinished && latest.first < queue.lastIndex -> latest.first + 1
        latest.second.isFinished -> 0
        else -> latest.first
    }
    val saved = if (startOver) null else Repo.progressFor(queue[index])?.takeIf { it.isInProgress }
    nav.play(queue, index, saved?.positionMs ?: 0L)
}

fun catalogItem(title: CatalogTitle, version: CatalogVersion, nav: AppNav, settings: AppSettings,
    progress: Progress? = null): BrowseItem {
    val ids = title.versions.map { it.id }
    val favorite = ids.any { it in Repo.favorites.value }
    fun toggle() {
        val existing = ids.filter { it in Repo.favorites.value }
        if (existing.isEmpty()) Repo.toggleFavorite(version.id) else existing.forEach { Repo.toggleFavorite(it) }
    }
    val visual = version.visual
    val details = { nav.navigate(Route.CatalogDetails(title.type, title.key, version.id)) }
    return BrowseItem(key = "catalog:${title.type}:${title.key}", title = if (settings.showOriginalNames) version.sourceName else title.name,
        subtitle = listOfNotNull(title.year?.toString(), if (title.versions.size > 1) "${title.versions.size} versions" else null,
            version.series?.let { "${it.seasons.size} seasons · ${it.episodes.size} episodes" }).joinToString(" · "),
        image = version.poster, backdrop = visual?.backdrop, summary = visual?.summary, facts = visual?.facts,
        isFavorite = favorite, progress = progress?.takeIf { it.isInProgress }?.fraction,
        onClick = details, onLongClick = { toggle() }, onDetails = details,
        onPlay = { playCatalogVersion(version, nav) }, playLabel = if (progress?.isInProgress == true) "Resume" else "Play",
        onToggleList = { toggle() }, onTrailer = visual?.trailer?.let { { nav.trailer(it) } }, channelId = version.movie?.id,
        onVersions = if (title.versions.size > 1) ({ nav.navigate(Route.CatalogDetails(title.type, title.key, version.id, chooseVersions = true)) }) else null)
}

@Composable
fun OrganizedLibraryScreen(type: ContentType) {
    val lib by Repo.library.collectAsState()
    val settings by Repo.settings.collectAsState()
    val progress by Repo.progress.collectAsState()
    val favorites by Repo.favorites.collectAsState()
    val nav = LocalNav.current
    var language by rememberSaveable(type) { mutableStateOf("Any") }
    var quality by rememberSaveable(type) { mutableIntStateOf(0) }
    var rating by rememberSaveable(type) { mutableIntStateOf(0) }
    var sort by rememberSaveable(type) { mutableStateOf("Library order") }
    var showCategories by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    val filter = CatalogFilter(language = language, quality = quality, rating = rating, sort = sort)
    val titles by produceState<List<CatalogTitle>?>(null, lib.revision, type) {
        value = withContext(Dispatchers.Default) { LibraryOrganization.catalog(lib, type) }
    }
    val rows by produceState<List<BrowseRow>?>(null, titles, filter, settings, progress, favorites) {
        val catalog = titles ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val started = SystemClock.elapsedRealtime()
            val selection = catalogSelection(catalog, filter)
            val selectedAt = SystemClock.elapsedRealtime()
            val watched = LinkedHashMap<String, Progress>()
            for ((id, saved) in progress.entries.sortedByDescending { it.value.updatedAt }) {
                val channel = lib.byId[id] ?: continue
                if (channel.type != type) continue
                val key = LibraryOrganization.titleKey(channel.seriesName ?: channel.name, channel.visual?.facts)
                if (key !in watched) watched[key] = saved
            }
            fun item(entry: Pair<CatalogTitle, List<CatalogVersion>>) = catalogItem(entry.first,
                LibraryOrganization.choose(entry.second, settings), nav, settings, watched[entry.first.key])
            fun openCategory(provider: CatalogProvider, genre: String?) {
                nav.navigate(Route.CatalogGroup(type, provider.name, genre, "Any", 0, 0, "Library order"))
            }
            val rowsStarted = SystemClock.elapsedRealtime()
            buildList {
                val continued = selection.filter { watched[it.first.key]?.isInProgress == true }
                    .sortedByDescending { watched[it.first.key]?.updatedAt }.map(::item)
                if (continued.isNotEmpty()) add(BrowseRow("organized-continue", "Continue Watching", continued.take(12), CardStyle.LANDSCAPE,
                    onViewAll = { nav.navigate(Route.ContinueWatching(type)) }))
                val providers = listOf(CatalogProvider.NETFLIX, CatalogProvider.HBO, CatalogProvider.PRIME,
                    CatalogProvider.PARAMOUNT, CatalogProvider.STARZPLAY, CatalogProvider.APPLE, CatalogProvider.HULU,
                    CatalogProvider.BET, CatalogProvider.QUIBI, CatalogProvider.HAYU, CatalogProvider.PEACOCK,
                    CatalogProvider.OTHERS)
                add(BrowseRow("organized-providers", "Providers", emptyList(), CardStyle.POSTER,
                    navigation = NavigationSection(providers.map { provider ->
                        NavigationTile("provider:${provider.name}", provider.label) { openCategory(provider, null) }
                    }, columns = 4)))
                val genres = catalog.asSequence().flatMap { it.versions.asSequence() }
                    .flatMap { it.genres.asSequence() }.distinct().sorted().toList()
                if (genres.isNotEmpty()) add(BrowseRow("organized-genres", "Genres", emptyList(), CardStyle.POSTER,
                    navigation = NavigationSection(genres.map { genre ->
                        NavigationTile("genre:$genre", genre) { openCategory(CatalogProvider.ALL, genre) }
                    }, columns = 6, compact = true)))
                if (selection.isNotEmpty()) add(BrowseRow("organized-all", "All · ${selection.size} titles",
                    selection.take(30).map(::item), CardStyle.POSTER,
                    onViewAll = { nav.navigate(Route.CatalogGroup(type, CatalogProvider.ALL.name, null, language, quality, rating, sort)) }))
            }.also {
                Log.i("CatalogTiming", "$type rows: selection=${selectedAt - started}ms progress=${rowsStarted - selectedAt}ms rows=${SystemClock.elapsedRealtime() - rowsStarted}ms total=${SystemClock.elapsedRealtime() - started}ms")
            }
        }
    }
    val listState = rememberLazyListState()
    var lastFilter by rememberSaveable(type) { mutableStateOf(filter.toString()) }
    LaunchedEffect(filter) {
        if (lastFilter != filter.toString()) { listState.scrollToItem(0); lastFilter = filter.toString() }
    }
    val controls: @Composable () -> Unit = {
        TvButton("Categories", onClick = { showCategories = true })
        TvButton("Filters & sort", onClick = { showFilters = true })
        ScopedSearchButton(type)
    }
    if (titles == null || rows == null) {
        Box(Modifier.fillMaxSize()) { EmptyState("Organizing your library…", "Grouping titles and available versions") }
    } else if (rows!!.isEmpty()) {
        Box(Modifier.fillMaxSize()) {
            EmptyState("No matching titles", "Choose another provider or clear your filters.")
            Row(Modifier.align(Alignment.TopEnd).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { controls() }
        }
    } else key(filter) {
        BrowseScreen(rows!!, listState, "No titles", topControls = controls)
    }
    if (showCategories) CatalogCategories(titles.orEmpty(), filter, onDismiss = { showCategories = false }) { p, g ->
        showCategories = false
        nav.navigate(Route.CatalogGroup(type, p.name, g, "Any", 0, 0, "Library order"))
    }
    if (showFilters) CatalogFilters(titles.orEmpty(), filter, onDismiss = { showFilters = false }) {
        language = it.language; quality = it.quality; rating = it.rating; sort = it.sort; showFilters = false
    }
}

@Composable
private fun ProviderTile(provider: CatalogProvider, selected: Boolean, onClick: () -> Unit) {
    FocusCard(onClick, modifier = Modifier.width(160.dp).height(100.dp),
        background = if (selected) SV.Surface2 else SV.Bg) {
        Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text(provider.label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)

        }
    }
}

@Composable
private fun CatalogCategories(titles: List<CatalogTitle>, filter: CatalogFilter, onDismiss: () -> Unit,
    onPick: (CatalogProvider, String?) -> Unit) {
    var provider by remember { mutableStateOf(filter.provider) }
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.widthIn(max = 1000.dp).fillMaxWidth(0.92f).heightIn(max = 640.dp)
            .clip(RoundedCornerShape(22.dp)).background(SV.Surface.copy(alpha = 0.94f)).padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Explore your library", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                TvButton("Close", onClick = onDismiss)
            }
            Text("Providers", color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(vertical = 12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(6.dp)) {
                items(CatalogProvider.entries.filter { it != CatalogProvider.DISNEY }) { p -> ProviderTile(p, p == provider) { provider = p } }
            }
            Text("Genres · ${provider.label}", color = Color.White, fontSize = 20.sp,
                modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))
            val genres = remember(titles, provider) { titles.asSequence().flatMap { it.versions.asSequence() }
                .filter { LibraryOrganization.providerMatches(it.provider, provider) }.flatMap { it.genres.asSequence() }.distinct().sorted().toList() }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { TvButton("All ${provider.label} titles", primary = true, onClick = { onPick(provider, null) }) }
                items(genres) { g -> TvButton(g, modifier = Modifier.fillMaxWidth(), onClick = { onPick(provider, g) }) }
            }
            Text("Provider labels come from your playlist; unidentified titles appear in Others.", color = SV.Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
fun CatalogFilters(titles: List<CatalogTitle>, filter: CatalogFilter, onDismiss: () -> Unit, onApply: (CatalogFilter) -> Unit) {
    var value by remember { mutableStateOf(filter) }
    val available = remember(titles, filter.provider) { titles.asSequence().flatMap { it.versions.asSequence() }
        .filter { LibraryOrganization.providerMatches(it.provider, filter.provider) }.toList() }
    val languages = remember(available) { listOf("Any") + available.map { it.language }.distinct().sorted() }
    val qualities = remember(available) { listOf(0) + available.map { it.quality }.filter { it > 0 }.distinct().sorted() }
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LazyColumn(Modifier.widthIn(max = 900.dp).fillMaxWidth(0.92f).heightIn(max = 640.dp)
            .clip(RoundedCornerShape(22.dp)).background(SV.Surface).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text("Filters & sort", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold) }
            item { Text("Language", color = SV.Dim) }
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(languages) { language ->
                    TvButton(language, primary = value.language == language, onClick = { value = value.copy(language = language) }) }
            } }
            item { Text("Quality", color = SV.Dim) }
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) { items(qualities) { q ->
                TvButton(if(q == 0) "Any quality" else LibraryOrganization.qualityLabel(q), primary = value.quality == q, onClick = { value = value.copy(quality = q) })
            } } }
            item { Text("Minimum rating", color = SV.Dim) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { listOf(0, 6, 7, 8, 9).forEach { r ->
                TvButton(if(r == 0) "Any rating" else "$r+", primary = value.rating == r, onClick = { value = value.copy(rating = r) })
            } } }
            item { Text("Sort by", color = SV.Dim) }
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) { items(listOf("Library order", "Title A–Z", "Rating", "Release year")) { s ->
                TvButton(s, primary = value.sort == s, onClick = { value = value.copy(sort = s) })
            } } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("Apply", primary = true, onClick = { onApply(value) })
                TvButton("Reset", onClick = { value = CatalogFilter(provider = filter.provider, genre = filter.genre) })
                TvButton("Cancel", onClick = onDismiss)
            } }
        }
    }
}
