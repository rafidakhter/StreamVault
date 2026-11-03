package com.streamvault.tv.player

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import com.streamvault.tv.data.Channel
import com.streamvault.tv.data.ContentType
import com.streamvault.tv.data.Repo
import com.streamvault.tv.subtitles.ExternalSubtitles
import com.streamvault.tv.subtitles.OpenSubtitlesClient
import com.streamvault.tv.subtitles.SubtitleParser
import com.streamvault.tv.subtitles.TitleCleaner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class TrackOption(val group: Tracks.Group, val index: Int, val label: String, val selected: Boolean)

class SubtitleSearchState {
    var query by mutableStateOf("")
    var language by mutableStateOf("en")
    var season: Int? = null
    var episode: Int? = null
    var year: Int? = null
    var results by mutableStateOf<List<OpenSubtitlesClient.Result>>(emptyList())
    var status by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var urlInput by mutableStateOf("")
}

@OptIn(UnstableApi::class)
class PlayerController(
    context: Context,
    private val scope: CoroutineScope,
    val queue: List<Channel>,
    startIndex: Int,
) {
    private val appContext = context.applicationContext
    private val openSubtitles = OpenSubtitlesClient(Repo.http)
    private var osToken: String? = null

    val player: ExoPlayer = ExoPlayer.Builder(appContext,
        DefaultRenderersFactory(appContext).setEnableDecoderFallback(true))
        .setAudioAttributes(AudioAttributes.DEFAULT, true)
        .setHandleAudioBecomingNoisy(true)
        .build()

    var index by mutableIntStateOf(startIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0)))
        private set
    val current: Channel get() = queue[index]
    val hasNext: Boolean get() = index < queue.size - 1
    val hasPrevious: Boolean get() = index > 0
    val isLive: Boolean get() = current.type == ContentType.LIVE

    var isPlaying by mutableStateOf(false)
        private set
    var isBuffering by mutableStateOf(true)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var bufferedMs by mutableLongStateOf(0L)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var ended by mutableStateOf(false)
        private set
    var tracks by mutableStateOf(Tracks.EMPTY)
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    var resizeMode by mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)
        private set
    private var pendingSeekMs: Long? = null

    val subs = ExternalSubtitles()
    val search = SubtitleSearchState()

    private var seekJob: Job? = null
    private var retried = false
    private var lastSave = 0L

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    error = null
                    retried = false
                }
                if (state == Player.STATE_ENDED) {
                    ended = true
                    saveProgress()
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                val rendererError = e as? ExoPlaybackException
                if (rendererError?.type == ExoPlaybackException.TYPE_RENDERER &&
                    (rendererError.rendererFormatSupport == C.FORMAT_EXCEEDS_CAPABILITIES ||
                        rendererError.rendererFormatSupport == C.FORMAT_UNSUPPORTED_TYPE ||
                        rendererError.rendererFormatSupport == C.FORMAT_UNSUPPORTED_SUBTYPE)) {
                    val format = rendererError.rendererFormat
                    val codec = when (format?.sampleMimeType) {
                        MimeTypes.VIDEO_H265 -> "HEVC (H.265)"
                        MimeTypes.VIDEO_H264 -> "H.264"
                        else -> format?.sampleMimeType ?: "this format"
                    }
                    val resolution = if (format != null && format.width > 0 && format.height > 0)
                        " at ${format.width} × ${format.height}" else ""
                    error = "This device cannot decode $codec$resolution. Choose an HD/1080p or H.264 version if available. Android TV emulators may not support the same video formats as a physical TV."
                    return
                }
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                if (isLive && !retried) {
                    // Live IPTV streams drop often; one quiet reconnect before bothering the user.
                    retried = true
                    scope.launch {
                        delay(1500)
                        player.prepare()
                        player.play()
                    }
                    return
                }
                error = when (e.errorCode) {
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server refused the stream (bad HTTP status). Your subscription may have expired or too many devices are connected."
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Network problem: couldn't reach the stream server."
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "This device couldn't decode the stream. Try an HD/1080p or H.264 version if available."
                    else -> e.message ?: e.errorCodeName
                }
            }

            override fun onTracksChanged(newTracks: Tracks) {
                tracks = newTracks
            }
        })
        val lang = Repo.settings.value.subtitleLang
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage(lang)
            .build()
    }

    // ------------------------------------------------------------- playback

    fun playIndex(i: Int, startMs: Long = -1L) {
        if (queue.isEmpty()) return
        saveProgress()
        index = i.coerceIn(0, queue.size - 1)
        val ch = current
        error = null
        ended = false
        retried = false
        pendingSeekMs = null
        positionMs = 0L
        durationMs = 0L
        subs.clear()
        resetSearch(ch)

        val item = MediaItem.Builder()
            .setUri(ch.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(ch.name).build())
            .apply {
                val u = ch.url.lowercase()
                when {
                    u.contains(".m3u8") || u.contains("type=m3u8") -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    u.contains(".mpd") -> setMimeType(MimeTypes.APPLICATION_MPD)
                }
            }
            .build()

        val headers = HashMap<String, String>()
        ch.referrer?.let { headers["Referer"] = it }
        val dataSource = OkHttpDataSource.Factory(Repo.http)
            .setUserAgent(ch.userAgent ?: Repo.USER_AGENT)
            .setDefaultRequestProperties(headers)
        val source = DefaultMediaSourceFactory(appContext)
            .setDataSourceFactory(dataSource)
            .createMediaSource(item)

        var start = startMs
        if (start < 0 && !isLive) {
            Repo.progress.value[ch.id]?.takeIf { it.isInProgress }?.let { start = it.positionMs }
        }
        if (start > 0) player.setMediaSource(source, start) else player.setMediaSource(source)
        player.setPlaybackSpeed(if (isLive) 1f else speed)
        player.prepare()
        player.playWhenReady = true
        Repo.markRecent(ch.id)
    }

    fun next() {
        if (hasNext) playIndex(index + 1)
    }

    fun previous() {
        if (hasPrevious) playIndex(index - 1)
    }

    fun retry() {
        error = null
        retried = false
        player.prepare()
        player.play()
    }

    fun togglePlay() {
        if (ended) {
            ended = false
            player.seekTo(0)
            player.play()
            return
        }
        if (player.isPlaying) player.pause() else player.play()
    }

    fun play() = player.play()
    fun pause() = player.pause()

    /** Netflix-style scrubbing: presses accumulate and the real seek happens once you stop. */
    fun seekBy(deltaMs: Long) {
        if (isLive && !player.isCurrentMediaItemSeekable) return
        val base = pendingSeekMs ?: player.currentPosition
        val max = if (durationMs > 0) durationMs - 1000 else Long.MAX_VALUE
        val target = (base + deltaMs).coerceIn(0L, max.coerceAtLeast(0L))
        pendingSeekMs = target
        positionMs = target
        seekJob?.cancel()
        seekJob = scope.launch {
            delay(600)
            player.seekTo(target)
            pendingSeekMs = null
        }
    }

    fun seekTo(ms: Long) {
        player.seekTo(ms)
        positionMs = ms
    }

    fun setPlaybackSpeed(value: Float) {
        speed = value
        player.setPlaybackSpeed(value)
    }

    fun cycleResize() {
        resizeMode = when (resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    val resizeLabel: String
        get() = when (resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch"
            else -> "Fit"
        }

    /** Called ~10x per second from the UI. */
    fun tick() {
        val d = player.duration
        durationMs = if (d == C.TIME_UNSET || d < 0) 0L else d
        positionMs = pendingSeekMs ?: player.currentPosition
        bufferedMs = player.bufferedPosition
        val now = SystemClock.elapsedRealtime()
        if (now - lastSave > 10_000) {
            lastSave = now
            saveProgress()
        }
    }

    fun saveProgress() {
        if (queue.isEmpty()) return
        val ch = current
        if (ch.type == ContentType.LIVE) return
        val d = player.duration
        if (d == C.TIME_UNSET || d <= 0) return
        val pos = if (ended) d else player.currentPosition
        Repo.saveProgress(ch.id, pos, d)
    }

    fun release() {
        saveProgress()
        seekJob?.cancel()
        player.release()
    }

    // ------------------------------------------------------------- tracks

    fun trackOptions(type: Int): List<TrackOption> {
        val out = ArrayList<TrackOption>()
        for (g in tracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                out.add(TrackOption(g, i, trackLabel(f, out.size + 1, type), g.isTrackSelected(i)))
            }
        }
        return out
    }

    fun selectTrack(option: TrackOption) {
        if (option.group.type == C.TRACK_TYPE_TEXT) subs.clear()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(option.group.type, false)
            .setOverrideForType(TrackSelectionOverride(option.group.mediaTrackGroup, option.index))
            .build()
    }

    private fun disableEmbeddedText() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    fun subtitlesOff() {
        subs.clear()
        disableEmbeddedText()
    }

    private fun trackLabel(f: Format, n: Int, type: Int): String {
        val parts = ArrayList<String>()
        val lang = f.language
        if (!lang.isNullOrBlank() && lang != "und") {
            val display = Locale.forLanguageTag(lang).displayLanguage
            parts.add(if (display.isNullOrBlank()) lang else display)
        }
        if (!f.label.isNullOrBlank() && f.label != parts.firstOrNull()) parts.add(f.label!!)
        if (type == C.TRACK_TYPE_AUDIO && f.channelCount != Format.NO_VALUE) {
            parts.add(
                when (f.channelCount) {
                    1 -> "Mono"
                    2 -> "Stereo"
                    6 -> "5.1"
                    8 -> "7.1"
                    else -> "${f.channelCount}ch"
                }
            )
        }
        val mime = f.sampleMimeType
        if (mime != null) {
            when {
                mime.contains("eac3") -> parts.add("Dolby Digital+")
                mime.contains("ac3") -> parts.add("Dolby Digital")
                mime.contains("dts") -> parts.add("DTS")
            }
        }
        return if (parts.isEmpty()) "Track $n" else parts.joinToString(" · ")
    }

    // ------------------------------------------------------------- external subtitles

    private fun resetSearch(ch: Channel) {
        search.query = TitleCleaner.clean(ch.seriesName ?: ch.name)
        search.season = if (ch.episode != null) (ch.season ?: 1) else null
        search.episode = ch.episode
        search.year = TitleCleaner.year(ch.name)
        search.language = Repo.settings.value.subtitleLang
        search.results = emptyList()
        search.status = null
        search.busy = false
    }

    fun searchSubtitles() {
        val settings = Repo.settings.value
        if (settings.osApiKey.isBlank()) {
            search.status = "Add your free OpenSubtitles API key in Settings first (opensubtitles.com → API consumers)."
            return
        }
        if (search.query.isBlank()) return
        search.busy = true
        search.status = "Searching…"
        scope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    openSubtitles.search(
                        settings.osApiKey, search.query, search.language,
                        search.season, search.episode, search.year,
                    )
                }
                search.results = results.sortedByDescending { it.downloads }
                search.status = if (results.isEmpty()) "No subtitles found. Try a shorter title or another language." else null
            } catch (e: Exception) {
                search.status = e.message ?: "Search failed"
            } finally {
                search.busy = false
            }
        }
    }

    fun downloadSubtitle(result: OpenSubtitlesClient.Result) {
        val settings = Repo.settings.value
        search.busy = true
        search.status = "Downloading…"
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    if (osToken == null && settings.osUsername.isNotBlank() && settings.osPassword.isNotBlank()) {
                        osToken = runCatching {
                            openSubtitles.login(settings.osApiKey, settings.osUsername, settings.osPassword)
                        }.getOrNull()
                    }
                    openSubtitles.download(settings.osApiKey, osToken, result.fileId)
                }
                applyExternal(text, "${result.language.uppercase()} · ${result.release.ifBlank { result.fileName }}")
            } catch (e: Exception) {
                search.status = e.message ?: "Download failed"
            } finally {
                search.busy = false
            }
        }
    }

    fun loadSubtitleUrl(url: String) {
        val clean = url.trim()
        if (!clean.startsWith("http", ignoreCase = true)) {
            search.status = "Enter a full link to an .srt or .vtt file"
            return
        }
        search.busy = true
        search.status = "Loading…"
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) { openSubtitles.fetchText(clean) }
                applyExternal(text, clean.substringAfterLast('/').substringBefore('?'))
            } catch (e: Exception) {
                search.status = e.message ?: "Couldn't load that file"
            } finally {
                search.busy = false
            }
        }
    }

    private fun applyExternal(text: String, label: String) {
        val cues = SubtitleParser.parse(text)
        if (cues.isEmpty()) {
            search.status = "That file didn't contain readable SRT/VTT subtitles."
            return
        }
        disableEmbeddedText()
        subs.load(cues, label)
        search.status = "Loaded ${cues.size} lines. Adjust timing below if they're early or late."
    }

    fun adjustOffset(deltaMs: Long) {
        subs.offsetMs += deltaMs
    }
}
