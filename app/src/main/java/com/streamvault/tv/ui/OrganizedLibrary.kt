package com.streamvault.tv.ui

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
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

/** Options within a group match any; different groups must match together. Empty means all. */
data class CatalogFilter(val provider: CatalogProvider = CatalogProvider.ALL, val genre: String? = null,
    val languages: List<String> = emptyList(), val qualities: List<Int> = emptyList(),
    val ratings: List<Int> = emptyList(), val sort: String = "Library order")

/** Genre describes a title, even when the provider encoding omits that metadata. */
fun catalogGenres(title: CatalogTitle): List<String> {
    val known = title.versions.asSequence().flatMap { it.genres.asSequence() }
        .filter { it != "Other genres" }.distinct().toList()
    return known.ifEmpty { listOf("Other genres") }
}

private fun eligible(title: CatalogTitle, filter: CatalogFilter): List<CatalogVersion> {
    // A genre belongs to the title, not to a particular language/quality encoding.
    if (filter.genre != null && filter.genre !in catalogGenres(title)) return emptyList()
    if (filter.provider == CatalogProvider.ALL && filter.languages.isEmpty() && filter.qualities.isEmpty() && filter.genre == null) return title.versions
    return title.versions.filterTo(ArrayList(title.versions.size)) {
        LibraryOrganization.providerMatches(it.provider, filter.provider) &&
            (filter.languages.isEmpty() || it.language in filter.languages) &&
            (filter.qualities.isEmpty() || it.quality in filter.qualities)
    }
}

fun catalogSelection(titles: List<CatalogTitle>, filter: CatalogFilter): List<Pair<CatalogTitle, List<CatalogVersion>>> {
    val selected = titles.asSequence().filter { filter.ratings.isEmpty() || ratingBucket(it.rating) in filter.ratings }
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
        image = version.poster, backdrop = visual?.backdrop, gallery = visual?.gallery.orEmpty(), summary = visual?.summary, facts = visual?.facts,
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
    var languages by rememberSaveable(type) { mutableStateOf(emptyList<String>()) }
    var qualities by rememberSaveable(type) { mutableStateOf(emptyList<Int>()) }
    var ratings by rememberSaveable(type) { mutableStateOf(emptyList<Int>()) }
    var sort by rememberSaveable(type) { mutableStateOf("Library order") }
    var showCategories by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    val filter = CatalogFilter(languages = languages, qualities = qualities, ratings = ratings, sort = sort)
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
                nav.navigate(Route.CatalogGroup(type, provider.name, genre, languages, qualities, ratings, sort))
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
                    }, columns = 5, compact = true)))
                if (selection.isNotEmpty()) add(BrowseRow("organized-all", "All · ${selection.size} titles",
                    selection.take(30).map(::item), CardStyle.POSTER,
                    onViewAll = { nav.navigate(Route.CatalogGroup(type, CatalogProvider.ALL.name, null, languages, qualities, ratings, sort)) }))
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
        ScopedSearchButton(type, filter)
    }
    if (titles == null || rows == null) {
        Box(Modifier.fillMaxSize()) { EmptyState("Organizing your library…", "Grouping titles and available versions") }
    } else if (rows!!.isEmpty()) {
        Box(Modifier.fillMaxSize()) {
            EmptyState("No matching titles", "Choose another provider or clear your filters.")
            Row(Modifier.align(Alignment.TopEnd).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { controls() }
        }
    } else key(filter) {
        BrowseScreen(rows!!, listState, "No titles", topControls = controls, autoFocusResults = !showFilters)
    }
    if (showCategories) CatalogCategories(titles.orEmpty(), filter, onDismiss = { showCategories = false }) { p, g ->
        showCategories = false
        nav.navigate(Route.CatalogGroup(type, p.name, g, languages, qualities, ratings, sort))
    }
    if (showFilters) CatalogFilters(titles.orEmpty(), filter, onDismiss = { showFilters = false }) {
        languages = it.languages; qualities = it.qualities; ratings = it.ratings; sort = it.sort
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

/** Non-overlapping bands make multiple rating selections meaningful. -1 is unrated. */
private fun ratingBucket(rating: Double?): Int = when {
    rating == null || rating < 0 -> -1
    rating < 6 -> 0
    else -> rating.toInt().coerceAtMost(9)
}

fun catalogRatingLabel(bucket: Int): String = when (bucket) {
    -1 -> "Unrated"
    0 -> "Below 6"
    9 -> "9–10"
    else -> "$bucket–$bucket.9"
}

private fun <T> List<T>.toggle(option: T): List<T> =
    if (option in this) filterNot { it == option } else this + option

@Composable
private fun FilterChip(label: String, checked: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(50)
    val foreground = if (focused) SV.Bg else Color.White
    Row(Modifier
        .semantics {
            role = Role.Checkbox
            toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
        }
        .clip(shape)
        .background(when {
            focused -> Color.White
            checked -> SV.Accent.copy(alpha = 0.28f)
            else -> Color.White.copy(alpha = 0.08f)
        })
        .border(if (focused) 2.dp else 1.dp, when {
            focused -> Color.White
            checked -> SV.Accent
            else -> Color.White.copy(alpha = 0.15f)
        }, shape)
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
        .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Reserve the indicator's space so toggling never shifts neighbouring chips.
        Box(Modifier.size(18.dp)) {
            if (checked) Icon(Icons.Rounded.Check, contentDescription = null,
                tint = foreground, modifier = Modifier.fillMaxSize())
        }
        Text(label, color = foreground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun CatalogFilters(titles: List<CatalogTitle>, filter: CatalogFilter, onDismiss: () -> Unit, onChange: (CatalogFilter) -> Unit) {
    val available = remember(titles, filter.provider, filter.genre) {
        titles.asSequence().filter { filter.genre == null || filter.genre in catalogGenres(it) }
            .flatMap { it.versions.asSequence() }
            .filter { LibraryOrganization.providerMatches(it.provider, filter.provider) }.toList()
    }
    val languages = remember(available) { available.map { it.language }.distinct().sorted() }
    // Keep familiar quality choices visible even when this catalog omits resolution tags.
    val qualities = remember(available, filter.qualities) {
        (listOf(480, 720, 1080, 2160) + available.map { it.quality } + filter.qualities)
            .distinct().sorted()
    }
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LazyColumn(Modifier.widthIn(max = 900.dp).fillMaxWidth(0.92f).heightIn(max = 640.dp)
            .clip(RoundedCornerShape(22.dp)).background(SV.Surface).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Filters & sort", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    TvButton("Close", onClick = onDismiss)
                }
            }
            item { Text("Changes apply automatically · Select multiple options · No selection means all", color = SV.Dim, fontSize = 14.sp) }
            item { Text("Languages", color = SV.Dim) }
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(6.dp)) {
                items(languages, key = { it }) { language ->
                    FilterChip(language, language in filter.languages) {
                        onChange(filter.copy(languages = filter.languages.toggle(language)))
                    }
                }
            } }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Quality", color = SV.Dim)
                    Text("Matches playlist quality labels; some titles have unspecified quality.", color = SV.Dim, fontSize = 12.sp)
                }
            }
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(6.dp)) {
                items(qualities, key = { it }) { quality ->
                    FilterChip(if (quality == 0) "Unknown quality" else LibraryOrganization.qualityLabel(quality), quality in filter.qualities) {
                        onChange(filter.copy(qualities = filter.qualities.toggle(quality)))
                    }
                }
            } }
            item { Text("Rating", color = SV.Dim) }
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(6.dp)) {
                items(listOf(0, 6, 7, 8, 9, -1)) { rating ->
                    FilterChip(catalogRatingLabel(rating), rating in filter.ratings) {
                        onChange(filter.copy(ratings = filter.ratings.toggle(rating)))
                    }
                }
            } }
            item { Text("Sort by", color = SV.Dim) }
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(listOf("Library order", "Title A–Z", "Rating", "Release year")) { sort ->
                    TvButton(sort, primary = filter.sort == sort, onClick = { onChange(filter.copy(sort = sort)) })
                }
            } }
        }
    }
}
