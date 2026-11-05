package com.streamvault.tv.data

import java.io.BufferedReader
import java.io.File

/**
 * Streaming M3U / M3U8 (extended) parser. Handles the common IPTV extensions:
 * tvg-* attributes, group-title, #EXTGRP, #EXTVLCOPT headers and Kodi-style
 * "url|User-Agent=...&Referer=..." suffixes. Works line-by-line so 100k+ entry
 * playlists don't need to be held as one string.
 */
object M3uParser {
    private val attrRegex = Regex("""([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"""")
    private val episodeRegex = Regex("""^(.*?)[\s._\-:|]*[Ss](\d{1,2})[\s._-]*[Ee](\d{1,4})(?!\d)""")
    private val vodExtensions = setOf("mp4", "mkv", "avi", "mov", "m4v", "wmv", "flv", "webm", "mpg", "mpeg")
    private val seriesWords = listOf("series", "serie", "tv show", "tvshow", "season", "episod")
    private val movieWords = listOf("movie", "film", "vod", "cinema", "pelicul", "4k uhd movies")

    fun parse(file: File, playlistId: String, maxItems: Int = Int.MAX_VALUE, existing: Map<String, Channel> = emptyMap()): MutableList<Channel> =
        file.bufferedReader(Charsets.UTF_8).use { parse(it, playlistId, maxItems, existing) }

    fun parse(reader: BufferedReader, playlistId: String, maxItems: Int = Int.MAX_VALUE, existing: Map<String, Channel> = emptyMap()): MutableList<Channel> {
        val out = ArrayList<Channel>(4096)
        // Provider logos, groups and show names repeat across hundreds of thousands of episodes.
        val strings = HashMap<String, String>()
        fun pooled(value: String?): String? = value?.let { strings.getOrPut(it) { it } }
        var title: String? = null
        var attrs: Map<String, String> = emptyMap()
        var userAgent: String? = null
        var referrer: String? = null
        var extGroup: String? = null

        while (true) {
            val raw = reader.readLine() ?: break
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty()) continue

            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                attrs = parseAttributes(line)
                title = displayName(line)
            } else if (line.startsWith("#EXTVLCOPT:", ignoreCase = true)) {
                val opt = line.substring(11).trim()
                if (opt.startsWith("http-user-agent=", ignoreCase = true)) {
                    userAgent = opt.substringAfter('=').trim()
                } else if (opt.startsWith("http-referrer=", ignoreCase = true) ||
                    opt.startsWith("http-referer=", ignoreCase = true)
                ) {
                    referrer = opt.substringAfter('=').trim()
                }
            } else if (line.startsWith("#EXTGRP:", ignoreCase = true)) {
                extGroup = line.substring(8).trim()
            } else if (line.startsWith("#")) {
                // #EXTM3U, #KODIPROP, comments: ignored
            } else {
                var url = line
                var ua = userAgent
                var ref = referrer
                val pipe = url.indexOf('|')
                if (pipe > 0) {
                    val options = url.substring(pipe + 1)
                    url = url.substring(0, pipe)
                    for (kv in options.split('&')) {
                        val key = kv.substringBefore('=').trim()
                        val value = kv.substringAfter('=', "").trim()
                        if (key.equals("User-Agent", ignoreCase = true)) ua = value
                        else if (key.equals("Referer", ignoreCase = true) || key.equals("Referrer", ignoreCase = true)) ref = value
                    }
                }
                if (url.contains("://")) {
                    val name = title?.takeIf { it.isNotBlank() }
                        ?: attrs["tvg-name"]?.takeIf { it.isNotBlank() }
                        ?: url.substringBefore('?').substringAfterLast('/')
                    val group = attrs["group-title"]?.trim()?.takeIf { it.isNotBlank() }
                        ?: extGroup?.takeIf { it.isNotBlank() }
                        ?: "Uncategorized"
                    val channel = makeChannel(name, url, attrs, group, ua ?: attrs["user-agent"], ref, playlistId)
                    val candidate = channel.copy(
                        logo = pooled(channel.logo), group = pooled(channel.group)!!,
                        poster = pooled(channel.poster), seriesKey = pooled(channel.seriesKey),
                        seriesName = pooled(channel.seriesName), userAgent = pooled(channel.userAgent),
                        referrer = pooled(channel.referrer),
                    )
                    val previous = existing[url]?.takeIf { it.playlistId == playlistId }
                    if (candidate.poster == null) candidate.poster = previous?.poster
                    candidate.visual = previous?.visual
                    out.add(if (candidate == previous) previous!! else candidate)
                    if (out.size >= maxItems) break
                }
                title = null
                attrs = emptyMap()
                userAgent = null
                referrer = null
                extGroup = null
            }
        }
        return out
    }

    private fun parseAttributes(line: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (m in attrRegex.findAll(line)) {
            map[m.groupValues[1].lowercase()] = m.groupValues[2]
        }
        return map
    }

    /** The title is everything after the first comma that is not inside quotes. */
    private fun displayName(line: String): String {
        var inQuotes = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) return line.substring(i + 1).trim()
        }
        return ""
    }

    private fun detectType(url: String, group: String, name: String): ContentType {
        val u = url.lowercase()
        val g = group.lowercase()
        val path = u.substringBefore('?').substringBefore('#').substringAfter("://").substringAfter('/', "")
        if (path.startsWith("series/") || path.contains("/series/")) return ContentType.SERIES
        if (path.startsWith("movie/") || path.startsWith("movies/") || path.contains("/movie/") || path.contains("/movies/")) return ContentType.MOVIE
        if (path.startsWith("live/") || path.contains("/live/")) return ContentType.LIVE
        if (episodeRegex.containsMatchIn(name) || seriesWords.any { g.contains(it) }) return ContentType.SERIES
        if (movieWords.any { g.contains(it) }) return ContentType.MOVIE
        val ext = u.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
        if (ext in vodExtensions) {
            return if (episodeRegex.containsMatchIn(name)) ContentType.SERIES else ContentType.MOVIE
        }
        return ContentType.LIVE
    }

    private fun makeChannel(
        name: String,
        url: String,
        attrs: Map<String, String>,
        group: String,
        userAgent: String?,
        referrer: String?,
        playlistId: String,
    ): Channel {
        val type = detectType(url, group, name)
        var seriesName: String? = null
        var season: Int? = null
        var episode: Int? = null
        if (type == ContentType.SERIES) {
            val m = episodeRegex.find(name)
            if (m != null) {
                seriesName = m.groupValues[1].trim(' ', '-', '_', '.', ':', '|').ifBlank { group }
                season = m.groupValues[2].toIntOrNull()
                episode = m.groupValues[3].toIntOrNull()
            } else {
                seriesName = name
            }
        }
        return Channel(
            id = url,
            name = name,
            logo = attrs["tvg-logo"]?.trim()?.takeIf { it.isNotBlank() },
            poster = (attrs["tvg-poster"] ?: attrs["poster"] ?: attrs["cover"])?.trim()?.takeIf { it.isNotBlank() },
            group = group,
            url = url,
            type = type,
            userAgent = userAgent?.takeIf { it.isNotBlank() },
            referrer = referrer?.takeIf { it.isNotBlank() },
            playlistId = playlistId,
            seriesKey = seriesName?.lowercase(),
            seriesName = seriesName,
            season = season,
            episode = episode,
        )
    }
}

object LibraryBuilder {
    fun build(channels: List<Channel>): Library {
        // Browsing order lives in all; the lookup index needs no per-entry ordering links.
        // Account for HashMap's load factor without doubling its requested capacity.
        val byId = HashMap<String, Channel>((channels.size / 0.75).toInt() + 1)
        val all = ArrayList<Channel>(channels.size)
        for (c in channels) {
            if (!byId.containsKey(c.id)) {
                byId[c.id] = c
                all.add(c)
            }
        }

        val live = LinkedHashMap<String, MutableList<Channel>>()
        val movies = LinkedHashMap<String, MutableList<Channel>>()
        val episodes = LinkedHashMap<String, MutableList<Channel>>()
        for (c in all) {
            when (c.type) {
                ContentType.LIVE -> live.getOrPut(c.group) { ArrayList() }.add(c)
                ContentType.MOVIE -> movies.getOrPut(c.group) { ArrayList() }.add(c)
                ContentType.SERIES -> episodes.getOrPut(c.seriesKey ?: c.id) { ArrayList() }.add(c)
            }
        }

        val seriesMap = LinkedHashMap<String, Series>()
        for ((key, eps) in episodes) {
            val sorted = eps.sortedWith(compareBy<Channel>({ it.season ?: 0 }, { it.episode ?: 0 }))
            val first = sorted.first()
            seriesMap[key] = Series(
                key = key,
                name = first.seriesName ?: first.name,
                group = first.group,
                poster = sorted.firstNotNullOfOrNull { it.artwork },
                episodes = sorted,
                visual = sorted.firstNotNullOfOrNull { it.visual },
            )
        }
        val seriesGroups = LinkedHashMap<String, MutableList<Series>>()
        for (s in seriesMap.values) seriesGroups.getOrPut(s.group) { ArrayList() }.add(s)

        return Library(
            revision = System.nanoTime(),
            all = all,
            byId = byId,
            liveGroups = live.map { it.key to it.value.toList() },
            movieGroups = movies.map { it.key to it.value.toList() },
            seriesGroups = seriesGroups.map { it.key to it.value.toList() },
            series = seriesMap,
        )
    }
}
