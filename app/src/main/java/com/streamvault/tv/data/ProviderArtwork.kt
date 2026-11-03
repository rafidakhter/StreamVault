package com.streamvault.tv.data

import android.util.JsonReader
import android.util.JsonToken
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.util.Locale

/** Read cover metadata in a stream rather than keeping the provider's JSON catalog in memory. */
internal object ProviderArtwork {
    fun enrich(playlist: Playlist, channels: List<Channel>, directory: File, refresh: Boolean): List<Channel> {
        val url = playlist.url.toHttpUrlOrNull() ?: return channels
        if (!url.pathSegments.last().equals("get.php", true)) return channels
        val username = url.queryParameter("username") ?: return channels
        val password = url.queryParameter("password") ?: return channels
        val movies = HashMap<String, String>()
        val series = HashMap<String, String>()
        for ((action, target) in listOf("get_vod_streams" to movies, "get_series" to series)) {
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
                        read(temp, target, action == "get_series")
                        temp.copyTo(cache, overwrite = true)
                        temp.delete()
                    }
                } else read(cache, target, action == "get_series")
            } catch (_: Exception) {
                target.clear()
                if (cache.exists()) runCatching { read(cache, target, action == "get_series") }
            }
        }
        return channels.map { channel ->
            val cover = when (channel.type) {
                ContentType.MOVIE -> movies[channel.url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')]
                ContentType.SERIES -> series[normalize(channel.seriesName ?: channel.name)]
                ContentType.LIVE -> null
            }
            if (cover == null) channel else channel.copy(poster = cover)
        }
    }

    private fun normalize(name: String) = name.trim().lowercase(Locale.ROOT)

    private fun read(file: File, target: MutableMap<String, String>, isSeries: Boolean) {
        JsonReader(file.reader()).use { reader ->
            reader.beginArray()
            while (reader.hasNext()) {
                var id: String? = null
                var name: String? = null
                var image: String? = null
                reader.beginObject()
                while (reader.hasNext()) {
                    val field = reader.nextName()
                    if (field in setOf("stream_id", "name", "cover", "stream_icon") &&
                        reader.peek() in setOf(JsonToken.STRING, JsonToken.NUMBER)) {
                        val value = reader.nextString()
                        when (field) {
                            "stream_id" -> id = value
                            "name" -> name = value
                            "cover", "stream_icon" -> if (value.startsWith("http://") || value.startsWith("https://")) image = value
                        }
                    } else reader.skipValue()
                }
                reader.endObject()
                val key = if (isSeries) name?.let(::normalize) else id
                if (key != null && image != null) target[key] = image
            }
            reader.endArray()
        }
    }
}
