package com.streamvault.tv.data

import android.util.JsonReader
import android.util.JsonToken
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.util.Locale

/** Read cover metadata in a stream rather than keeping the provider's JSON catalog in memory. */
internal object ProviderArtwork {
    fun enrich(playlist: Playlist, channels: MutableList<Channel>, directory: File, refresh: Boolean): List<Channel> {
        val url = playlist.url.toHttpUrlOrNull() ?: return channels
        if (!url.pathSegments.last().equals("get.php", true)) return channels
        val username = url.queryParameter("username") ?: return channels
        val password = url.queryParameter("password") ?: return channels
        val movies = HashMap<String, String>()
        val series = HashMap<String, String>()
        val live = HashMap<String, String>()
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
        val showCovers = HashMap<String, String?>()
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
                        seriesName = null, season = null, episode = null, poster = null)
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
                channel.poster = cover
                if (channel.type == ContentType.MOVIE) movieMatches++ else seriesMatches++
            }
        }
        android.util.Log.i("ProviderArtwork", "Covers: ${movies.size} movies, ${series.size} shows; matched $movieMatches movies, $seriesMatches episodes; corrected $reclassified live channels")
        return channels
    }

    private fun normalize(name: String) = name.trim().lowercase(Locale.ROOT)

    private fun read(file: File, target: MutableMap<String, String>, action: String) {
        JsonReader(file.reader()).use { reader ->
            reader.beginArray()
            while (reader.hasNext()) {
                var id: String? = null
                var name: String? = null
                var image: String? = null
                reader.beginObject()
                while (reader.hasNext()) {
                    val field = reader.nextName()
                    if ((field == "stream_id" || field == "name" || field == "cover" || field == "stream_icon") &&
                        (reader.peek() == JsonToken.STRING || reader.peek() == JsonToken.NUMBER)) {
                        val value = reader.nextString()
                        when (field) {
                            "stream_id" -> id = value
                            "name" -> name = value
                            "cover", "stream_icon" -> if (value.startsWith("http://") || value.startsWith("https://")) image = value
                        }
                    } else reader.skipValue()
                }
                reader.endObject()
                val key = if (action == "get_series") name?.let(::normalize) else id
                if (key != null) {
                    if (action == "get_live_streams") target[key] = ""
                    else if (image != null) target[key] = image
                }
            }
            reader.endArray()
        }
    }
}
