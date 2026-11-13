package com.streamvault.tv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamvault.tv.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Provider landing uses the same hero, idle preview and remote navigation as browsing. */
@Composable
fun ProviderBrowseScreen(
    type: ContentType,
    entries: List<Pair<CatalogTitle, List<CatalogVersion>>>?,
    watched: Map<String, Progress>,
    filter: CatalogFilter,
    settings: AppSettings,
    favorites: List<String>,
    controls: @Composable () -> Unit,
    autoFocusResults: Boolean = true,
) {
    val nav = LocalNav.current
    val listState = rememberLazyListState()
    var lastFilter by rememberSaveable { mutableStateOf(filter.toString()) }
    LaunchedEffect(filter) {
        if (lastFilter != filter.toString()) {
            listState.scrollToItem(0)
            lastFilter = filter.toString()
        }
    }
    val rows by produceState<List<BrowseRow>?>(null, entries, watched, filter, settings, favorites) {
        val selected = entries ?: return@produceState
        value = withContext(Dispatchers.Default) {
            fun open(genre: String? = null, continued: Boolean = false) {
                nav.navigate(Route.CatalogGroup(type, filter.provider.name, genre, filter.languages,
                    filter.qualities, filter.ratings, filter.sort, gridOnly = true, continueOnly = continued))
            }
            fun item(entry: Pair<CatalogTitle, List<CatalogVersion>>) = catalogItem(entry.first,
                LibraryOrganization.choose(entry.second, settings), nav, settings, watched[entry.first.key])
            // Store references per genre; build cards only for the short row previews.
            val genres = linkedMapOf<String, MutableList<Pair<CatalogTitle, List<CatalogVersion>>>>()
            for (entry in selected) {
                val labels = catalogGenres(entry.first)
                for (genre in labels) genres.getOrPut(genre) { ArrayList() }.add(entry)
            }
            buildList {
                val continued = selected.filter { watched[it.first.key]?.isInProgress == true }
                    .sortedByDescending { watched[it.first.key]?.updatedAt }
                if (continued.isNotEmpty()) add(BrowseRow("provider-continue", "Continue Watching", continued.take(12).map(::item),
                    CardStyle.LANDSCAPE, onViewAll = if (continued.size > 12) ({ open(continued = true) }) else null))
                for ((genre, matches) in genres.toSortedMap()) {
                    add(BrowseRow("provider-genre:$genre", genre, matches.take(15).map(::item), CardStyle.POSTER,
                        onViewAll = if (matches.size > 15) ({ open(genre) }) else null))
                }
                if (selected.isNotEmpty()) add(BrowseRow("provider-all", "All · ${selected.size} titles",
                    selected.take(15).map(::item), CardStyle.POSTER, onViewAll = if (selected.size > 15) ({ open() }) else null))
            }
        }
    }
    val topControls: @Composable () -> Unit = {
        Text(filter.provider.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp,
            modifier = Modifier.padding(top = 10.dp, end = 8.dp))
        controls()
    }
    when {
        entries == null || rows == null -> Box(Modifier.fillMaxSize()) {
            EmptyState("Loading ${filter.provider.label}…")
            Row(Modifier.align(Alignment.TopEnd).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { topControls() }
        }
        rows!!.isEmpty() -> Box(Modifier.fillMaxSize()) {
            EmptyState("No matching titles", "Try another language or clear your filters.")
            Row(Modifier.align(Alignment.TopEnd).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { topControls() }
        }
        else -> key(filter) {
            BrowseScreen(rows!!, listState, "No matching titles", topControls = topControls, autoFocusResults = autoFocusResults)
        }
    }
}
