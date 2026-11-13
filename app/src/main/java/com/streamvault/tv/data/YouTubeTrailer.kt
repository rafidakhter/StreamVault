package com.streamvault.tv.data

import android.net.Uri

/** Only a validated video ID enters embed HTML; arbitrary provider URLs never enter a WebView. */
object YouTubeTrailer {
    fun videoId(value: String): String? = runCatching {
        if (value.matches(Regex("[A-Za-z0-9_-]{11}"))) return@runCatching value
        val uri = Uri.parse(value)
        if (uri.scheme !in listOf("https", "http")) return@runCatching null
        val host = uri.host?.lowercase(java.util.Locale.ROOT)
        val id = when (host) {
            "youtu.be", "www.youtu.be" -> uri.pathSegments.firstOrNull()
            "youtube.com", "www.youtube.com", "m.youtube.com", "youtube-nocookie.com", "www.youtube-nocookie.com" ->
                if (uri.pathSegments.firstOrNull() in listOf("embed", "shorts", "live")) uri.pathSegments.getOrNull(1) else uri.getQueryParameter("v")
            else -> null
        }
        id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    }.getOrNull()
}
