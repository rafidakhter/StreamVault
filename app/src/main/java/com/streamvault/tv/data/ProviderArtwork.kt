package com.streamvault.tv.data

import android.util.JsonReader
import android.util.JsonToken
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.util.Locale

/** Read cover metadata in a stream rather than keeping the provider's JSON catalog in memory. */
internal object ProviderArtwork {
    private val trailerId = Regex("[A-Za-z0-9_-]{11}")
    private val detailsCache = android.util.LruCache<String, TitleArtwork>(64)
    private val detailsHttp by lazy { Repo.http.newBuilder().callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build() }

    /** Fetch only the focused movie's details, never a whole catalog of plots/backdrops. */
    fun details(playlist: Playlist, channel: Channel): TitleArtwork? {
        detailsCache.get(channel.id)?.let { return it }
        if (channel.type != ContentType.MOVIE) return channel.visual
        val source = playlist.url.toHttpUrlOrNull() ?: return null
        if (!source.pathSegments.last().equals("get.php", true)) return null
        val username = source.queryParameter("username") ?: return null
        val password = source.queryParameter("password") ?: return null
        val id = channel.url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
        val api = source.newBuilder().query(null).setPathSegment(source.pathSegments.lastIndex, "player_api.php")
            .addQueryParameter("username", username).addQueryParameter("password", password)
            .addQueryParameter("action", "get_vod_info").addQueryParameter("vod_id", id).build()
        return try {
            detailsHttp.newCall(Request.Builder().url(api).header("User-Agent", Repo.USER_AGENT).build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                var poster = channel.artwork
                var backdrop = channel.visual?.backdrop
                var trailer = channel.visual?.trailer
                var summary = channel.visual?.summary
                var genre: String? = null
                var year: String? = null
                var rating: String? = null
                JsonReader(body.charStream()).use { reader ->
                    reader.beginObject()
                    while (reader.hasNext()) {
                        if (reader.nextName() != "info" || reader.peek() != JsonToken.BEGIN_OBJECT) {
                            reader.skipValue()
                            continue
                        }
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val field = reader.nextName()
                            if (field == "backdrop_path" && reader.peek() == JsonToken.BEGIN_ARRAY) {
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    if (reader.peek() == JsonToken.STRING && backdrop == null) backdrop = webUrl(reader.nextString())
                                    else reader.skipValue()
                                }
                                reader.endArray()
                            } else if ((reader.peek() == JsonToken.STRING || reader.peek() == JsonToken.NUMBER) && field in listOf("movie_image", "cover_big", "backdrop_path", "youtube_trailer", "plot", "description", "genre", "releasedate", "releaseDate", "rating")) {
                                val value = reader.nextString()
                                when (field) {
                                    "movie_image", "cover_big" -> webUrl(value)?.let { poster = it }
                                    "backdrop_path" -> backdrop = webUrl(value)
                                    "youtube_trailer" -> trailer = trailerUrl(value)
                                    "plot", "description" -> cleanText(value)?.let { summary = it }
                                    "genre" -> genre = cleanText(value)
                                    "releasedate", "releaseDate" -> year = value.take(4).takeIf { it.toIntOrNull() != null }
                                    "rating" -> rating = value.takeIf { (it.toDoubleOrNull() ?: 0.0) > 0 }?.let { "★ $it" }
                                }
                            } else reader.skipValue()
                        }
                        reader.endObject()
                    }
                    reader.endObject()
                }
                TitleArtwork(poster, backdrop, trailer, summary,
                    listOfNotNull(year, genre, rating).joinToString(" · ").ifBlank { channel.visual?.facts }).also { detailsCache.put(channel.id, it) }
            }
        } catch (_: Exception) { null }
    }

    fun enrich(playlist: Playlist, channels: MutableList<Channel>, directory: File, refresh: Boolean): List<Channel> {
        val url = playlist.url.toHttpUrlOrNull() ?: return channels
        if (!url.pathSegments.last().equals("get.php", true)) return channels
        val username = url.queryParameter("username") ?: return channels
        val password = url.queryParameter("password") ?: return channels
        val movies = HashMap<String, TitleArtwork>()
        val series = HashMap<String, TitleArtwork>()
        val live = HashMap<String, TitleArtwork>()
        for ((action, target) in listOf("get_live_streams" to live, "get_vod_streams" to movies, "get_series" to series)) {
            if (action == "get_vod_streams" && channels.none { it.type == ContentType.MOVIE }) continue
            if (action == "get_series" && channels.none { it.type == ContentType.SERIES }) continue
            val cache = File(directory, "artwork_${playlist.id}_$action.json")
            try {
                if (refresh || !cache.exists()) {
                    val api = url.newBuilder().query(null)
                        .setPathSegment(url.pathSegments.lastIndex, "player_api.php")
                        .addQueryParameter("username", username)
                        .addQueryParameter("password", password)
                        .addQueryParameter("action", action).build()
                    Repo.http.newCall(Request.Builder().url(api).header("User-Agent", Repo.USER_AGENT).build()).execute().use { response ->
                        if (!response.isSuccessful) error("Artwork unavailable")
                        val body = response.body ?: error("Empty artwork response")
                        val temp = File(cache.path + ".tmp")
                        body.byteStream().use { input -> temp.outputStream().use { input.copyTo(it) } }
                        // Validate before replacing a working offline catalog.
                        read(temp, target, action)
                        temp.copyTo(cache, overwrite = true)
                        temp.delete()
                    }
                } else read(cache, target, action)
            } catch (_: Exception) {
                target.clear()
                if (cache.exists()) runCatching { read(cache, target, action) }
            }
        }
        // Update the catalog in place rather than copying every episode.
        val showCovers = HashMap<String, TitleArtwork?>()
        var movieMatches = 0
        var seriesMatches = 0
        var reclassified = 0
        for (index in channels.indices) {
            val channel = channels[index]
            val streamId = channel.url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
            // Live service channels often have "movies" or "series" in their group name.
            // Prefer the provider's live catalog to those ambiguous name heuristics.
            val path = channel.url.substringBefore('?')
            if (live.containsKey(streamId) && !path.contains("/movie/", true) &&
                !path.contains("/movies/", true) && !path.contains("/series/", true)) {
                if (channel.type != ContentType.LIVE) {
                    channels[index] = channel.copy(type = ContentType.LIVE, seriesKey = null,
                        seriesName = null, season = null, episode = null, poster = null, visual = null)
                    reclassified++
                }
                continue
            }
            val cover = when (channel.type) {
                ContentType.MOVIE -> movies[streamId]
                ContentType.SERIES -> {
                    val name = channel.seriesName ?: channel.name
                    if (showCovers.containsKey(name)) showCovers[name]
                    else series[normalize(name)].also { showCovers[name] = it }
                }
                ContentType.LIVE -> null
            }
            if (cover != null) {
                channel.visual = cover
                if (channel.type == ContentType.MOVIE) movieMatches++ else seriesMatches++
            }
        }
        android.util.Log.i("ProviderArtwork", "Covers: ${movies.size} movies, ${series.size} shows; matched $movieMatches movies, $seriesMatches episodes; corrected $reclassified live channels")
        return channels
    }

    private fun normalize(name: String) = name.trim().lowercase(Locale.ROOT)

    private fun read(file: File, target: MutableMap<String, TitleArtwork>, action: String) {
        val liveMarker = TitleArtwork(null, null, null)
        JsonReader(file.reader()).use { reader ->
            reader.beginArray()
            while (reader.hasNext()) {
                var id: String? = null
                var name: String? = null
                var image: String? = null
                var backdrop: String? = null
                var trailer: String? = null
                var summary: String? = null
                var genre: String? = null
                var year: String? = null
                var rating: String? = null
                reader.beginObject()
                while (reader.hasNext()) {
                    val field = reader.nextName()
                    if (field == "backdrop_path" && reader.peek() == JsonToken.BEGIN_ARRAY) {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            if (reader.peek() == JsonToken.STRING && backdrop == null) {
                                backdrop = webUrl(reader.nextString())
                            } else reader.skipValue()
                        }
                        reader.endArray()
                    } else if ((field == "stream_id" || field == "name" || field == "cover" || field == "stream_icon" ||
                            field == "backdrop_path" || field == "youtube_trailer" ||
                            (action == "get_series" && field == "plot") ||
                            (action != "get_live_streams" && field in listOf("genre", "releaseDate", "rating"))) &&
                        (reader.peek() == JsonToken.STRING || reader.peek() == JsonToken.NUMBER)) {
                        val value = reader.nextString()
                        when (field) {
                            "stream_id" -> id = value
                            "name" -> name = value
                            "cover", "stream_icon" -> if (value.startsWith("http://") || value.startsWith("https://")) image = value
                            "backdrop_path" -> backdrop = webUrl(value)
                            "youtube_trailer" -> {
                                trailer = trailerUrl(value)
                            }
                            "plot" -> summary = cleanText(value)
                            "genre" -> genre = cleanText(value)
                            "releaseDate" -> year = value.take(4).takeIf { it.toIntOrNull() != null }
                            "rating" -> rating = value.takeIf { (it.toDoubleOrNull() ?: 0.0) > 0 }?.let { "★ $it" }
                        }
                    } else reader.skipValue()
                }
                reader.endObject()
                val key = if (action == "get_series") name?.let(::normalize) else id
                if (key != null) {
                    if (action == "get_live_streams") target[key] = liveMarker
                    else if (image != null || backdrop != null || trailer != null || summary != null) target[key] = TitleArtwork(image, backdrop, trailer,
                        summary, listOfNotNull(year, genre, rating).joinToString(" · ").ifBlank { null })
                }
            }
            reader.endArray()
        }
    }

    private fun webUrl(value: String): String? = value.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    private fun cleanText(value: String): String? = value.trim().takeIf { it.isNotEmpty() && !it.equals("null", true) }?.take(1600)
    private fun trailerUrl(value: String): String? = if (trailerId.matches(value)) "https://www.youtube.com/watch?v=$value" else webUrl(value)
}
