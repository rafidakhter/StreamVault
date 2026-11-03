package com.streamvault.tv.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Single source of truth for playlists, the parsed library and per-user state
 * (My List, watch progress, recently watched, settings). Persisted in
 * SharedPreferences as JSON; playlists are cached on disk so the app opens
 * instantly and works when the provider is slow.
 */
object Repo {
    const val USER_AGENT = "StreamVault/1.0 (Linux; Android TV)"
    private const val PREFS = "streamvault"

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val libraryMutex = Mutex()

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _library = MutableStateFlow(Library())
    val library: StateFlow<Library> = _library.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    /** Most recently added first. Holds channel ids and "series:<key>" ids. */
    private val _favorites = MutableStateFlow<List<String>>(emptyList())
    val favorites: StateFlow<List<String>> = _favorites.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = _progress.asStateFlow()

    private val _recent = MutableStateFlow<List<String>>(emptyList())
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Hand-off to PlayerActivity (kept in memory, the queue can be thousands of channels). */
    @Volatile var queue: List<Channel> = emptyList()
    @Volatile var queueIndex: Int = 0

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _playlists.value = readPlaylists()
        _favorites.value = readStringList("favorites")
        _recent.value = readStringList("recent")
        _progress.value = readProgress()
        _settings.value = readSettings()
        scope.launch { rebuild(refresh = false) }
    }

    // ---------------------------------------------------------------- playlists

    private fun fileFor(id: String) = File(appContext.filesDir, "playlist_$id.m3u")

    private fun download(url: String, dest: File) {
        val enriched = metadataPlaylistUrl(url)
        if (enriched != url) {
            try {
                downloadFile(enriched, dest)
                return
            } catch (_: IOException) {
                // Some providers only support the originally supplied format.
            }
        }
        downloadFile(url, dest)
    }

    private fun downloadFile(url: String, dest: File) {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Server answered HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty response")
            val tmp = File(dest.path + ".tmp")
            body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (M3uParser.parse(tmp, "validation").isEmpty()) {
                tmp.delete()
                throw IOException("No channels were found at that link")
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        }
    }

    private suspend fun rebuild(refresh: Boolean, onlyId: String? = null) {
        libraryMutex.withLock {
            _loading.value = true
            try {
                val all = ArrayList<Channel>()
                val updated = ArrayList<Playlist>()
                for (pl in _playlists.value) {
                    val file = fileFor(pl.id)
                    var p = pl
                    try {
                        val migrationKey = "playlist_metadata_v1_${pl.id}"
                        val needsMetadata = metadataPlaylistUrl(pl.url) != pl.url && !prefs.getBoolean(migrationKey, false)
                        if (!file.exists() || needsMetadata || (refresh && (onlyId == null || onlyId == pl.id))) {
                            download(pl.url, file)
                            prefs.edit().putBoolean(migrationKey, true).apply()
                            p = p.copy(updatedAt = System.currentTimeMillis())
                        }
                        val items = M3uParser.parse(file, pl.id)
                        p = p.copy(channelCount = items.size)
                        all.addAll(ProviderArtwork.enrich(pl, items, appContext.filesDir,
                            refresh && (onlyId == null || onlyId == pl.id)))
                    } catch (e: Exception) {
                        postStatus("${pl.name}: ${e.message ?: "couldn't refresh"}")
                        if (file.exists()) runCatching { all.addAll(M3uParser.parse(file, pl.id)) }
                    }
                    updated.add(p)
                }
                _playlists.value = _playlists.value.map { cur -> updated.firstOrNull { it.id == cur.id } ?: cur }
                savePlaylists()
                _library.value = LibraryBuilder.build(all)
            } finally {
                _loading.value = false
            }
        }
    }

    /** Downloads, validates and adds a playlist. Returns a user-facing message. */
    suspend fun addPlaylist(name: String, url: String): String = withContext(Dispatchers.IO) {
        val clean = url.trim()
        if (!clean.startsWith("http://", true) && !clean.startsWith("https://", true)) {
            return@withContext "Enter a link that starts with http:// or https://"
        }
        val id = "pl" + System.currentTimeMillis()
        val file = fileFor(id)
        _loading.value = true
        try {
            download(clean, file)
            prefs.edit().putBoolean("playlist_metadata_v1_$id", true).apply()
            val count = M3uParser.parse(file, id).size
            if (count == 0) {
                file.delete()
                return@withContext "No channels were found at that link"
            }
            val playlist = Playlist(
                id = id,
                name = name.trim().ifBlank { "Playlist ${_playlists.value.size + 1}" },
                url = clean,
                updatedAt = System.currentTimeMillis(),
                channelCount = count,
            )
            _playlists.value = _playlists.value + playlist
            savePlaylists()
            rebuild(refresh = false)
            "Added ${playlist.name} ($count items)"
        } catch (e: Exception) {
            file.delete()
            "Couldn't load playlist: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            _loading.value = false
        }
    }

    fun addPlaylistAsync(name: String, url: String, onResult: (String) -> Unit = {}) {
        scope.launch {
            val msg = addPlaylist(name, url)
            postStatus(msg)
            onResult(msg)
        }
    }

    fun removePlaylist(id: String) {
        scope.launch {
            _playlists.value = _playlists.value.filterNot { it.id == id }
            savePlaylists()
            fileFor(id).delete()
            rebuild(refresh = false)
        }
    }

    fun refresh(id: String? = null) {
        scope.launch {
            rebuild(refresh = true, onlyId = id)
            postStatus("Library refreshed")
        }
    }

    // ---------------------------------------------------------------- user state

    fun isFavorite(id: String) = id in _favorites.value

    fun toggleFavorite(id: String) {
        val cur = _favorites.value
        val next = if (id in cur) cur - id else listOf(id) + cur
        _favorites.value = next
        writeStringList("favorites", next)
        postStatus(if (id in next) "Added to My List" else "Removed from My List")
    }

    fun saveProgress(id: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        val updated = HashMap(_progress.value)
        updated[id] = Progress(positionMs, durationMs, System.currentTimeMillis())
        val trimmed = if (updated.size > 500) {
            updated.entries.sortedByDescending { it.value.updatedAt }.take(400).associate { it.key to it.value }
        } else updated
        _progress.value = trimmed
        val json = JSONObject()
        for ((k, v) in trimmed) json.put(k, JSONArray().put(v.positionMs).put(v.durationMs).put(v.updatedAt))
        prefs.edit().putString("progress", json.toString()).apply()
    }

    fun markRecent(id: String) {
        val next = (listOf(id) + (_recent.value - id)).take(40)
        _recent.value = next
        writeStringList("recent", next)
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val s = transform(_settings.value)
        _settings.value = s
        val o = JSONObject()
            .put("subtitleLang", s.subtitleLang)
            .put("subtitleScale", s.subtitleScale.toDouble())
            .put("subtitleBackground", s.subtitleBackground)
            .put("autoplayNext", s.autoplayNext)
            .put("osApiKey", s.osApiKey)
            .put("osUsername", s.osUsername)
            .put("osPassword", s.osPassword)
        prefs.edit().putString("settings", o.toString()).apply()
    }

    fun postStatus(message: String) {
        _status.value = message
    }

    fun clearStatus() {
        _status.value = null
    }

    // ---------------------------------------------------------------- persistence

    private fun savePlaylists() {
        val arr = JSONArray()
        for (p in _playlists.value) {
            arr.put(
                JSONObject().put("id", p.id).put("name", p.name).put("url", p.url)
                    .put("updatedAt", p.updatedAt).put("count", p.channelCount)
            )
        }
        prefs.edit().putString("playlists", arr.toString()).apply()
    }

    private fun readPlaylists(): List<Playlist> = runCatching {
        val arr = JSONArray(prefs.getString("playlists", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Playlist(o.getString("id"), o.optString("name"), o.getString("url"), o.optLong("updatedAt"), o.optInt("count"))
        }
    }.getOrDefault(emptyList())

    private fun readStringList(key: String): List<String> = runCatching {
        val arr = JSONArray(prefs.getString(key, "[]"))
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    private fun writeStringList(key: String, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    private fun readProgress(): Map<String, Progress> = runCatching {
        val o = JSONObject(prefs.getString("progress", "{}") ?: "{}")
        val map = HashMap<String, Progress>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val a = o.getJSONArray(k)
            map[k] = Progress(a.getLong(0), a.getLong(1), a.getLong(2))
        }
        map as Map<String, Progress>
    }.getOrDefault(emptyMap())

    private fun readSettings(): AppSettings = runCatching {
        val o = JSONObject(prefs.getString("settings", "{}") ?: "{}")
        AppSettings(
            subtitleLang = o.optString("subtitleLang", "en"),
            subtitleScale = o.optDouble("subtitleScale", 1.0).toFloat(),
            subtitleBackground = o.optBoolean("subtitleBackground", true),
            autoplayNext = o.optBoolean("autoplayNext", true),
            osApiKey = o.optString("osApiKey", ""),
            osUsername = o.optString("osUsername", ""),
            osPassword = o.optString("osPassword", ""),
        )
    }.getOrDefault(AppSettings())
}

/** Keep credentials and output options intact; only request extended metadata. */
internal fun metadataPlaylistUrl(url: String): String {
    val parsed = url.toHttpUrlOrNull() ?: return url
    if (!parsed.pathSegments.last().equals("get.php", ignoreCase = true) ||
        !parsed.queryParameter("type").equals("m3u", ignoreCase = true)) return url
    return parsed.newBuilder().setQueryParameter("type", "m3u_plus").build().toString()
}
