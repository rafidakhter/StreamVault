package com.streamvault.tv.data

enum class ContentType { LIVE, MOVIE, SERIES }

/** Shared by all episodes of a show to keep the catalog's memory footprint small. */
data class TitleArtwork(val poster: String?, val backdrop: String?, val trailer: String?)

data class Channel(
    /** Stable key: the stream URL. */
    val id: String,
    val name: String,
    val logo: String?,
    val group: String,
    val url: String,
    val type: ContentType,
    val tvgId: String?,
    val userAgent: String?,
    val referrer: String?,
    val playlistId: String,
    val seriesKey: String? = null,
    val seriesName: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    var poster: String? = null,
    var visual: TitleArtwork? = null,
) {
    val artwork: String? get() = visual?.poster ?: poster ?: logo
    val episodeLabel: String?
        get() = if (episode != null) "S${season ?: 1}:E$episode" else null

    /** The id used for "My List": a whole series is favourited, not a single episode. */
    val favoriteId: String
        get() = if (type == ContentType.SERIES && seriesKey != null) "series:$seriesKey" else id
}

data class Series(
    val key: String,
    val name: String,
    val group: String,
    val poster: String?,
    val episodes: List<Channel>,
    val visual: TitleArtwork? = null,
) {
    val favoriteId: String get() = "series:$key"
    val seasons: List<Int> get() = episodes.map { it.season ?: 1 }.distinct().sorted()
}

data class Playlist(
    val id: String,
    val name: String,
    val url: String,
    val updatedAt: Long = 0L,
    val channelCount: Int = 0,
)

data class Progress(val positionMs: Long, val durationMs: Long, val updatedAt: Long) {
    val fraction: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val isInProgress: Boolean get() = positionMs >= 1_000 && fraction < 0.95f
    val isFinished: Boolean get() = durationMs > 0 && fraction >= 0.95f
    val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0)
}

data class AppSettings(
    val subtitleLang: String = "en",
    val subtitleScale: Float = 1f,
    val subtitleBackground: Boolean = true,
    val autoplayNext: Boolean = true,
    val osApiKey: String = "",
    val osUsername: String = "",
    val osPassword: String = "",
)

data class Library(
    internal val revision: Long = 0L,
    val all: List<Channel> = emptyList(),
    val byId: Map<String, Channel> = emptyMap(),
    val liveGroups: List<Pair<String, List<Channel>>> = emptyList(),
    val movieGroups: List<Pair<String, List<Channel>>> = emptyList(),
    val seriesGroups: List<Pair<String, List<Series>>> = emptyList(),
    val series: Map<String, Series> = emptyMap(),
) {
    val liveByGroup: Map<String, List<Channel>> by lazy { liveGroups.toMap() }
    val isEmpty: Boolean get() = all.isEmpty()

    /** Channels to zap through when a live channel is opened: its own group. */
    fun liveQueueFor(ch: Channel): List<Channel> = liveByGroup[ch.group] ?: listOf(ch)
}
