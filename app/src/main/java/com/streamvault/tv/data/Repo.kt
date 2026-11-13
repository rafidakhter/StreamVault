package com.streamvault.tv.data

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import java.security.MessageDigest
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
 * SharedPreferences as JSON. Source playlists and a parsed snapshot are cached
 * on disk to avoid repeated downloads and parsing on cold starts.
 */
object Repo {
    const val USER_AGENT = "StreamVault/1.0 (Linux; Android TV)"
    private const val PREFS = "streamvault"

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var profileStore: ProfileStore
    private val _profiles = MutableStateFlow(listOf(UserProfile("default", "You")))
    val profiles: StateFlow<List<UserProfile>> = _profiles.asStateFlow()
    private val _activeProfile = MutableStateFlow(UserProfile("default", "You"))
    val activeProfile: StateFlow<UserProfile> = _activeProfile.asStateFlow()
    private fun userPrefs(id: String = _activeProfile.value.id) = profileStore.preferences(id)
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

    const val LIKED_PLAYLIST_ID = "liked"
    private var likedIds: List<String> = emptyList()
    data class ListPickerRequest(val id: String, val relatedIds: List<String>)
    private val _listPicker = MutableStateFlow<ListPickerRequest?>(null)
    val listPicker: StateFlow<ListPickerRequest?> = _listPicker.asStateFlow()
    fun requestListPicker(id: String, relatedIds: List<String> = listOf(id)) {
        val target = _library.value.byId[id]?.favoriteId ?: id
        val related = relatedIds.map { _library.value.byId[it]?.favoriteId ?: it }.distinct()
        _listPicker.value = ListPickerRequest(target, related)
    }
    fun dismissListPicker() { _listPicker.value = null }

    private val _userPlaylists = MutableStateFlow<List<UserPlaylist>>(emptyList())
    val userPlaylists: StateFlow<List<UserPlaylist>> = _userPlaylists.asStateFlow()

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
        profileStore = ProfileStore(appContext, prefs)
        _profiles.value = profileStore.profiles
        loadProfile(profileStore.active)
        scope.launch { rebuild(refresh = false) }
    }

    // ---------------------------------------------------------------- playlists

    private fun fileFor(id: String) = File(appContext.filesDir, "playlist_$id.m3u")

    suspend fun heroArtwork(channelId: String): TitleArtwork? = withContext(Dispatchers.IO) {
        val channel = _library.value.byId[channelId] ?: return@withContext null
        val playlist = _playlists.value.firstOrNull { it.id == channel.playlistId } ?: return@withContext null
        ProviderArtwork.details(playlist, channel)
    }

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
            if (M3uParser.parse(tmp, "validation", maxItems = 1).isEmpty()) {
                tmp.delete()
                throw IOException("No channels were found at that link")
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        }
    }

    internal fun catalogSnapshotFile(type: ContentType) = File(appContext.noBackupFilesDir, "catalog-v1-${type.name}.bin")

    private fun snapshotFile() = File(appContext.noBackupFilesDir, "library-v1.bin")

    private fun snapshotFingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) { digest.update(value.toByteArray(Charsets.UTF_8)); digest.update(0.toByte()) }
        add("library-v1")
        for (playlist in _playlists.value) {
            add(playlist.id); add(playlist.url)
            add(prefs.getBoolean("playlist_metadata_v1_${playlist.id}", false).toString())
            val sources = listOf(fileFor(playlist.id)) +
                listOf("get_live_streams", "get_vod_streams", "get_series").map {
                    File(appContext.filesDir, "artwork_${playlist.id}_$it.json")
                }
            for (source in sources) {
                add(source.name); add(source.exists().toString())
                add(source.length().toString()); add(source.lastModified().toString())
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun rebuild(refresh: Boolean, onlyId: String? = null) {
        libraryMutex.withLock {
            _loading.value = true
            val started = SystemClock.elapsedRealtime()
            try {
                if (!refresh && _playlists.value.isNotEmpty()) {
                    val fingerprint = snapshotFingerprint()
                    val cached = LibrarySnapshot.read(snapshotFile(), fingerprint)
                    if (cached != null) {
                        _library.value = LibraryBuilder.build(cached).copy(sourceFingerprint = fingerprint)
                        Log.i("LibraryStartup", "Snapshot restored: ${cached.size} entries in ${SystemClock.elapsedRealtime() - started} ms")
                        return@withLock
                    }
                }
                var complete = true
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
                        val items = M3uParser.parse(file, pl.id, existing = _library.value.byId)
                        p = p.copy(channelCount = items.size)
                        all.addAll(ProviderArtwork.enrich(pl, items, appContext.filesDir,
                            refresh && (onlyId == null || onlyId == pl.id)))
                    } catch (e: Exception) {
                        complete = false
                        postStatus("${pl.name}: ${e.message ?: "couldn't refresh"}")
                        if (file.exists()) runCatching { all.addAll(M3uParser.parse(file, pl.id)) }
                    }
                    updated.add(p)
                }
                _playlists.value = _playlists.value.map { cur -> updated.firstOrNull { it.id == cur.id } ?: cur }
                savePlaylists()
                val built = LibraryBuilder.build(all).copy(sourceFingerprint = if (complete) snapshotFingerprint() else null)
                _library.value = built
                Log.i("LibraryStartup", "Source loaded: ${built.all.size} entries in ${SystemClock.elapsedRealtime() - started} ms")
                if (complete && built.all.isNotEmpty()) {
                    try {
                        LibrarySnapshot.write(snapshotFile(), snapshotFingerprint(), built.all)
                        Log.i("LibraryStartup", "Snapshot saved")
                    } catch (error: Exception) {
                        Log.w("LibraryStartup", "Snapshot save failed: ${error.javaClass.simpleName}")
                    }
                }
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

    // ---------------------------------------------------------------- local profiles

    private fun loadProfile(profile: UserProfile) {
        val storage = userPrefs(profile.id)
        // Legacy favorites are the default Liked playlist. Keep their existing storage and every saved ID.
        likedIds = readStringList("favorites", storage)
        val custom = decodeUserPlaylists(storage.getString("user_playlists", "[]") ?: "[]").toMutableList()
        for (index in custom.indices) if (custom[index].name.equals("Liked", true)) {
            var name = "Liked (custom)"
            var suffix = 2
            while (custom.any { it.name.equals(name, true) }) name = "Liked (custom ${suffix++})"
            custom[index] = custom[index].copy(name = name)
        }
        publishUserPlaylists(listOf(UserPlaylist(LIKED_PLAYLIST_ID, "Liked", likedIds)) + custom)
        _listPicker.value = null
        _recent.value = readStringList("recent", storage)
        _progress.value = readProgress(storage)
        _settings.value = readSettings(storage)
        organizedProgressSource = null
        organizedProgressCache = emptyMap()
        _activeProfile.value = profile
    }

    @Synchronized fun switchProfile(id: String) {
        if (id != _activeProfile.value.id) loadProfile(profileStore.select(id))
    }
    @Synchronized fun addProfile(name: String): UserProfile = profileStore.create(name).also { _profiles.value = profileStore.profiles }
    @Synchronized fun renameProfile(id: String, name: String): UserProfile = profileStore.rename(id, name).also {
        _profiles.value = profileStore.profiles
        if (_activeProfile.value.id == id) _activeProfile.value = it
    }
    @Synchronized fun removeProfile(id: String) {
        profileStore.remove(id)
        _profiles.value = profileStore.profiles
        if (_activeProfile.value.id == id) loadProfile(profileStore.active)
    }

    // ---------------------------------------------------------------- user state

    // Curated playlists are private to the active profile, never IPTV source subscriptions.
    private fun playlistProfile(profileId: String) {
        check(_activeProfile.value.id == profileId) { "Profile changed. Pair again." }
    }

    private fun playlistName(name: String, excluding: String? = null): String {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        if (clean.isBlank() || clean.length > 48 || clean.any { it.isISOControl() })
            throw UserPlaylistError("Enter a playlist name between 1 and 48 characters.")
        if (_userPlaylists.value.any { it.id != excluding && it.name.equals(clean, true) })
            throw UserPlaylistError("A playlist with that name already exists.")
        return clean
    }

    private fun publishUserPlaylists(next: List<UserPlaylist>) {
        _userPlaylists.value = next
        _favorites.value = next.flatMap { it.itemIds }.distinct()
    }

    private fun saveUserPlaylists(next: List<UserPlaylist>) {
        val custom = next.filterNot { it.id == LIKED_PLAYLIST_ID }
        val liked = next.firstOrNull { it.id == LIKED_PLAYLIST_ID }?.itemIds ?: likedIds
        val encoded = encodeUserPlaylists(custom)
        if (encoded.toByteArray(Charsets.UTF_8).size > 512 * 1024)
            throw UserPlaylistError("Playlist storage is full. Remove some items first.")
        // One preferences edit keeps the legacy favorites key and curated collections in sync.
        userPrefs().edit().putString("user_playlists", encoded).putString("favorites", JSONArray(liked).toString()).apply()
        likedIds = liked
        publishUserPlaylists(listOf(UserPlaylist(LIKED_PLAYLIST_ID, "Liked", liked)) + custom)
    }

    @Synchronized fun createUserPlaylist(name: String, profileId: String = _activeProfile.value.id): UserPlaylist {
        playlistProfile(profileId)
        if (_userPlaylists.value.count { it.id != LIKED_PLAYLIST_ID } >= 30) throw UserPlaylistError("You can create up to 30 playlists.")
        val item = UserPlaylist(java.util.UUID.randomUUID().toString(), playlistName(name))
        saveUserPlaylists(_userPlaylists.value + item)
        return item
    }

    @Synchronized fun renameUserPlaylist(id: String, name: String, profileId: String = _activeProfile.value.id) {
        playlistProfile(profileId)
        if (_userPlaylists.value.none { it.id == id }) throw UserPlaylistError("Playlist no longer exists.")
        if (id == LIKED_PLAYLIST_ID) throw UserPlaylistError("The default Liked playlist cannot be renamed.")
        val clean = playlistName(name, id)
        saveUserPlaylists(_userPlaylists.value.map { if (it.id == id) it.copy(name = clean) else it })
    }

    @Synchronized fun deleteUserPlaylist(id: String, profileId: String = _activeProfile.value.id) {
        playlistProfile(profileId)
        if (id == LIKED_PLAYLIST_ID) throw UserPlaylistError("The default Liked playlist cannot be deleted.")
        saveUserPlaylists(_userPlaylists.value.filterNot { it.id == id })
    }

    /** Explicit set operations make repeated phone requests safe. Series use their whole-show ID. */
    @Synchronized fun setUserPlaylistItem(playlistId: String, id: String, relatedIds: List<String>, saved: Boolean,
        profileId: String = _activeProfile.value.id) {
        playlistProfile(profileId)
        val list = _userPlaylists.value.firstOrNull { it.id == playlistId }
            ?: throw UserPlaylistError("Playlist no longer exists.")
        if (id.isBlank() || id.length > 4096) throw UserPlaylistError("This item cannot be saved.")
        val related = relatedIds.toHashSet().also { it.add(id) }
        val nextIds = if (saved) {
            if (list.itemIds.any { it in related }) return
            if (playlistId != LIKED_PLAYLIST_ID && list.itemIds.size >= 300) throw UserPlaylistError("A playlist can contain up to 300 titles.")
            if (playlistId == LIKED_PLAYLIST_ID) listOf(id) + list.itemIds else list.itemIds + id
        } else list.itemIds.filterNot { it in related }
        if (nextIds == list.itemIds) return
        saveUserPlaylists(_userPlaylists.value.map { if (it.id == playlistId) it.copy(itemIds = nextIds) else it })
    }

    fun isFavorite(id: String) = id in _favorites.value

    /** Compatibility endpoints save to Liked; current clients use the playlist picker. */
    @Synchronized fun addFavorite(id: String, relatedIds: List<String>, profileId: String): Boolean {
        playlistProfile(profileId)
        if (likedIds.any { it == id || it in relatedIds }) return false
        setUserPlaylistItem(LIKED_PLAYLIST_ID, id, relatedIds, true, profileId)
        return true
    }

    @Synchronized fun removeFavorites(ids: List<String>, profileId: String): Boolean {
        playlistProfile(profileId)
        if (likedIds.none { it in ids }) return false
        setUserPlaylistItem(LIKED_PLAYLIST_ID, ids.first(), ids, false, profileId)
        return true
    }

    @Synchronized fun saveProgress(id: String, positionMs: Long, durationMs: Long, profileId: String = _activeProfile.value.id) {
        if (durationMs <= 0 || _profiles.value.none { it.id == profileId }) return
        val active = profileId == _activeProfile.value.id
        val storage = userPrefs(profileId)
        val updated = HashMap(if (active) _progress.value else readProgress(storage))
        updated[id] = Progress(positionMs, durationMs, System.currentTimeMillis())
        if ((if (active) _settings.value else readSettings(storage)).optimizeLibrary) _library.value.byId[id]?.let { LibraryOrganization.progressKey(it) }?.let {
            updated[it] = updated[id]!!
        }
        val trimmed = if (updated.size > 500) {
            updated.entries.sortedByDescending { it.value.updatedAt }.take(400).associate { it.key to it.value }
        } else updated
        if (active) _progress.value = trimmed
        val json = JSONObject()
        for ((k, v) in trimmed) json.put(k, JSONArray().put(v.positionMs).put(v.durationMs).put(v.updatedAt))
        storage.edit().putString("progress", json.toString()).apply()
    }

    @Synchronized fun markRecent(id: String, profileId: String = _activeProfile.value.id) {
        if (_profiles.value.none { it.id == profileId }) return
        val active = profileId == _activeProfile.value.id
        val storage = userPrefs(profileId)
        val next = (listOf(id) + ((if (active) _recent.value else readStringList("recent", storage)) - id)).take(500)
        if (active) _recent.value = next
        writeStringList("recent", next, storage)
    }

    @Synchronized fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val old = _settings.value
        val s = transform(old)
        _settings.value = s
        val o = JSONObject()
            .put("subtitleLang", s.subtitleLang)
            .put("subtitleScale", s.subtitleScale.toDouble())
            .put("subtitleBackground", s.subtitleBackground)
            .put("autoplayNext", s.autoplayNext)
            .put("optimizeLibrary", s.optimizeLibrary)
            .put("preferredLanguage", s.preferredLanguage)
            .put("maximumQuality", s.maximumQuality)
            .put("showOriginalNames", s.showOriginalNames)
        userPrefs().edit().putString("settings", o.toString()).apply()
        // Provider/service credentials remain shared; viewing preferences belong to the profile.
        if (old.osApiKey != s.osApiKey || old.osUsername != s.osUsername || old.osPassword != s.osPassword) {
            prefs.edit().putString("settings", JSONObject().put("osApiKey", s.osApiKey)
                .put("osUsername", s.osUsername).put("osPassword", s.osPassword).toString()).apply()
        }
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

    private fun readStringList(key: String, storage: SharedPreferences = userPrefs()): List<String> = runCatching {
        val arr = JSONArray(storage.getString(key, "[]"))
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    private fun writeStringList(key: String, list: List<String>, storage: SharedPreferences = userPrefs()) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        storage.edit().putString(key, arr.toString()).apply()
    }

    private fun readProgress(storage: SharedPreferences = userPrefs()): Map<String, Progress> = runCatching {
        val o = JSONObject(storage.getString("progress", "{}") ?: "{}")
        val map = HashMap<String, Progress>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val a = o.getJSONArray(k)
            map[k] = Progress(a.getLong(0), a.getLong(1), a.getLong(2))
        }
        map as Map<String, Progress>
    }.getOrDefault(emptyMap())

    private fun readSettings(storage: SharedPreferences = userPrefs()): AppSettings = runCatching {
        val o = JSONObject(storage.getString("settings", "{}") ?: "{}")
        val services = JSONObject(prefs.getString("settings", "{}") ?: "{}")
        AppSettings(
            subtitleLang = o.optString("subtitleLang", "en"),
            subtitleScale = o.optDouble("subtitleScale", 1.0).toFloat(),
            subtitleBackground = o.optBoolean("subtitleBackground", true),
            autoplayNext = o.optBoolean("autoplayNext", true),
            osApiKey = services.optString("osApiKey", ""),
            osUsername = services.optString("osUsername", ""),
            osPassword = services.optString("osPassword", ""),
            optimizeLibrary = o.optBoolean("optimizeLibrary", true),
            preferredLanguage = o.optString("preferredLanguage", "English"),
            maximumQuality = o.optInt("maximumQuality", 1080),
            showOriginalNames = o.optBoolean("showOriginalNames", false),
        )
    }.getOrDefault(AppSettings())

    private var organizedProgressSource: Map<String, Progress>? = null
    private var organizedProgressRevision = Long.MIN_VALUE
    private var organizedProgressCache: Map<String, Progress> = emptyMap()

    @Synchronized private fun organizedProgress(): Map<String, Progress> {
        val source = _progress.value
        val library = _library.value
        if (organizedProgressSource !== source || organizedProgressRevision != library.revision) {
            val grouped = HashMap<String, Progress>()
            for ((id, progress) in source) {
                val key = if (id.startsWith("organized:")) id else library.byId[id]?.let { LibraryOrganization.progressKey(it) }
                if (key != null && progress.updatedAt >= (grouped[key]?.updatedAt ?: 0L)) grouped[key] = progress
            }
            organizedProgressCache = grouped
            organizedProgressSource = source
            organizedProgressRevision = library.revision
        }
        return organizedProgressCache
    }

    @Synchronized fun progressFor(channel: Channel, profileId: String = _activeProfile.value.id): Progress? {
        if (profileId != _activeProfile.value.id) {
            if (_profiles.value.none { it.id == profileId }) return null
            val saved = readProgress(userPrefs(profileId))
            val key = LibraryOrganization.progressKey(channel)
            return (if (readSettings(userPrefs(profileId)).optimizeLibrary) saved[key] else null) ?: saved[channel.id]
        }
        if (!_settings.value.optimizeLibrary) return _progress.value[channel.id]
        val key = LibraryOrganization.progressKey(channel) ?: return _progress.value[channel.id]
        return organizedProgress()[key] ?: _progress.value[channel.id]
    }
}

/** Keep credentials and output options intact; only request extended metadata. */
internal fun metadataPlaylistUrl(url: String): String {
    val parsed = url.toHttpUrlOrNull() ?: return url
    if (!parsed.pathSegments.last().equals("get.php", ignoreCase = true) ||
        !parsed.queryParameter("type").equals("m3u", ignoreCase = true)) return url
    return parsed.newBuilder().setQueryParameter("type", "m3u_plus").build().toString()
}
