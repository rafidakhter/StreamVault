package com.streamvault.tv.ui

import android.content.Context
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import com.streamvault.tv.data.Channel
import com.streamvault.tv.data.ContentType
import com.streamvault.tv.data.Library
import com.streamvault.tv.player.PlayerActivity

sealed interface Route {
    data class MovieDetails(val id: String) : Route
    data class SeriesDetails(val key: String) : Route
    data class Group(val title: String, val type: ContentType) : Route
    data class ContinueWatching(val type: ContentType? = null) : Route
    data class Search(val type: ContentType, val sessionId: String = java.util.UUID.randomUUID().toString(), val filter: CatalogFilter? = null) : Route
    data class CatalogDetails(val type: ContentType, val key: String, val versionId: String? = null, val chooseVersions: Boolean = false) : Route
    data class CatalogGroup(val type: ContentType, val provider: String, val genre: String?, val languages: List<String>, val qualities: List<Int>, val ratings: List<Int>, val sort: String, val gridOnly: Boolean = false, val continueOnly: Boolean = false) : Route
}

class AppNav(private val context: Context, private val stack: SnapshotStateList<Route>) {
    fun navigate(route: Route) {
        stack.add(route)
    }

    fun back() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    fun trailer(url: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(intent) }
        catch (_: android.content.ActivityNotFoundException) { com.streamvault.tv.data.Repo.postStatus("No app is available to open this trailer") }
    }

    fun play(queue: List<Channel>, index: Int, startMs: Long = -1L) {
        if (queue.isEmpty()) return
        PlayerActivity.start(context, queue, index.coerceIn(0, queue.size - 1), startMs)
    }

    /** What a click on any channel tile does: live plays instantly, VOD opens its page. */
    fun openChannel(ch: Channel, lib: Library) {
        when (ch.type) {
            ContentType.LIVE -> {
                val q = lib.liveQueueFor(ch)
                play(q, q.indexOfFirst { it.id == ch.id }.coerceAtLeast(0))
            }
            ContentType.MOVIE -> if (com.streamvault.tv.data.Repo.settings.value.optimizeLibrary)
                navigate(Route.CatalogDetails(ch.type, com.streamvault.tv.data.LibraryOrganization.titleKey(ch.name, ch.visual?.facts), ch.id))
                else navigate(Route.MovieDetails(ch.id))
            ContentType.SERIES -> if (com.streamvault.tv.data.Repo.settings.value.optimizeLibrary)
                navigate(Route.CatalogDetails(ch.type, com.streamvault.tv.data.LibraryOrganization.titleKey(ch.seriesName ?: ch.name, ch.visual?.facts), ch.favoriteId))
                else navigate(Route.SeriesDetails(ch.seriesKey ?: ch.id))
        }
    }
}

val LocalNav = staticCompositionLocalOf<AppNav> { error("AppNav not provided") }
