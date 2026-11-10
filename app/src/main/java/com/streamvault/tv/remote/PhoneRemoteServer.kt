package com.streamvault.tv.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import com.streamvault.tv.data.*
import com.streamvault.tv.ui.localIpAddress
import com.streamvault.tv.ui.catalogGenres
import java.util.Collections
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Small bounded LAN server. Never sends provider links or playlist credentials to the browser. */
internal class PhoneRemoteServer(private val context: Context, private val onChange: (String?, Int) -> Unit) {
    private data class Entry(val title: CatalogTitle? = null, val live: Channel? = null)
    private class HttpError(val code: Int, message: String) : Exception(message)
    private val random = SecureRandom()
    @Volatile private var socket: ServerSocket? = null
    private val clients = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private lateinit var origin: String
    private lateinit var host: String
    private var pairing = token()
    private var pairExpires = 0L
    private val sessions = hashMapOf<String, Long>()
    private val entries = object : LinkedHashMap<String, Entry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 256
    }
    private var revision = -1L
    private val options = hashMapOf<ContentType, JSONObject>()
    private val workers = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "phone-remote-request").apply { isDaemon = true } })
    private val imageClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
                require(addresses.isNotEmpty() && addresses.all { publicAddress(it) }) { "Unsupported artwork host" }
            }
        }).build()
    private val imageDir = File(context.cacheDir, "phone-art").apply { mkdirs() }

    fun start() {
        val ip = localIpAddress() ?: throw IllegalStateException("No LAN connection")
        val listener = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(ip, 0), 8) }
        socket = listener
        host = "$ip:${listener.localPort}"
        origin = "http://$host"
        refreshPairing()
        thread(name = "phone-remote", isDaemon = true) {
            while (!listener.isClosed) {
                val client = try { listener.accept() } catch (_: Exception) { break }
                clients.add(client)
                try {
                    workers.execute { try { handle(client) } finally { clients.remove(client) } }
                } catch (_: Exception) { clients.remove(client); client.close() }
            }
        }
    }
    @Synchronized fun refreshPairing() {
        pairing = token()
        pairExpires = SystemClock.elapsedRealtime() + 120_000
        publish()
    }
    @Synchronized fun revoke() { sessions.clear(); refreshPairing() }
    @Synchronized private fun publish() {
        sessions.entries.removeAll { it.value < SystemClock.elapsedRealtime() }
        onChange("$origin/#pair=$pairing", sessions.size)
    }
    fun stop() {
        runCatching { socket?.close() }
        socket = null
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        workers.shutdownNow()
        synchronized(this) { sessions.clear(); entries.clear(); options.clear() }
        imageClient.dispatcher.cancelAll()
        imageClient.connectionPool.evictAll()
        onChange(null, 0)
    }
    private fun token(): String = ByteArray(32).also(random::nextBytes)
        .let { Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING) }
    private fun opaque(prefix: String, value: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$prefix:$value".toByteArray()).take(18).toByteArray()
        .let { Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING) }
    private fun remember(entry: Entry): String {
        val id = entry.title?.let { opaque(it.type.name, it.key) } ?: opaque("live", entry.live!!.id)
        synchronized(this) { entries[id] = entry }
        return id
    }
    private fun entry(id: String): Entry {
        checkRevision()
        return synchronized(this) { entries[id] } ?: throw HttpError(409, "Library changed. Refresh the catalog and select the title again.")
    }
    @Synchronized private fun checkRevision() {
        val current = Repo.library.value.revision
        if (revision != current) { entries.clear(); options.clear(); revision = current }
    }
    private fun versionId(version: CatalogVersion) = opaque("version", version.id)
    private fun chosen(entry: Entry, id: String?): CatalogVersion {
        val versions = entry.title?.versions ?: throw HttpError(400, "Select a movie or series.")
        return if (id.isNullOrBlank()) LibraryOrganization.choose(versions, Repo.settings.value)
        else versions.firstOrNull { versionId(it) == id } ?: throw HttpError(409, "That version is no longer available.")
    }

    private fun handle(socket: Socket) = socket.use { client ->
        client.soTimeout = 6_000
        try {
            val input = BufferedInputStream(client.getInputStream())
            var headerBytes = 0
            val readDeadline = SystemClock.elapsedRealtime() + 6_000
            fun line(): String {
                val bytes = ByteArrayOutputStream()
                while (true) {
                    if (SystemClock.elapsedRealtime() > readDeadline) throw HttpError(408, "Request timed out.")
                    val value = input.read()
                    if (value < 0) throw HttpError(400, "Incomplete request.")
                    if (++headerBytes > 16_384) throw HttpError(431, "Request headers too large.")
                    if (value == 10) break
                    bytes.write(value)
                }
                return bytes.toString("US-ASCII").trimEnd('\r')
            }
            val start = line().split(' ')
            if (start.size != 3 || start[0] !in listOf("GET", "POST")) throw HttpError(405, "Unsupported request.")
            if (this.socket?.isClosed != false) throw HttpError(503, "Phone remote is off.")
            val method = start[0]
            val target = start[1]
            val headers = hashMapOf<String, String>()
            while (true) {
                val h = line()
                if (h.isEmpty()) break
                val key = h.substringBefore(':').lowercase()
                if (headers.containsKey(key)) throw HttpError(400, "Duplicate header.")
                headers[key] = h.substringAfter(':', "").trim()
            }
            if (headers["host"] != host) throw HttpError(403, "Invalid host.")
            if (headers["origin"] != null && headers["origin"] != origin) throw HttpError(403, "Invalid origin.")
            if (headers.containsKey("transfer-encoding")) throw HttpError(400, "Unsupported transfer encoding.")
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length !in 0..8192) throw HttpError(413, "Request too large.")
            val body = ByteArray(length)
            var offset = 0
            while (offset < length) {
                if (SystemClock.elapsedRealtime() > readDeadline) throw HttpError(408, "Request timed out.")
                val n = input.read(body, offset, length - offset)
                if (n < 0) throw HttpError(400, "Incomplete request.")
                offset += n
            }
            val path = target.substringBefore('?')
            val params = target.substringAfter('?', "").split('&').filter { '=' in it }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            if (method == "GET" && path == "/") {
                reply(client, 200, "text/html; charset=utf-8", context.assets.open("phone-remote.html").use { it.readBytes() })
                return@use
            }
            if (method == "POST" && !headers["content-type"].orEmpty().startsWith("application/json")) throw HttpError(415, "Use JSON.")
            val json = if (method == "POST") JSONObject(String(body, Charsets.UTF_8)) else JSONObject()
            val cookie = headers["cookie"].orEmpty().split(';').map { it.trim() }.firstOrNull { it.startsWith("svremote=") }?.substringAfter('=')
            if (method == "POST" && path == "/api/pair") {
                val session = synchronized(this) {
                    publish()
                    if (pairExpires < SystemClock.elapsedRealtime() || !MessageDigest.isEqual(json.optString("token").toByteArray(), pairing.toByteArray())) {
                        throw HttpError(401, "Pairing code expired. Refresh the QR code on the TV and scan again.")
                    }
                    if (cookie != null) sessions.remove(cookie)
                    if (sessions.size >= 4) throw HttpError(429, "Four phones are paired. Disconnect phones on the TV first.")
                    token().also { sessions[it] = SystemClock.elapsedRealtime() + 3_600_000; refreshPairing() }
                }
                replyJson(client, JSONObject().put("ok", true), "Set-Cookie: svremote=$session; HttpOnly; SameSite=Strict; Path=/api; Max-Age=3600\r\n")
                return@use
            }
            synchronized(this) {
                if (cookie == null || (sessions[cookie] ?: 0) < SystemClock.elapsedRealtime()) {
                    if (cookie != null) sessions.remove(cookie)
                    throw HttpError(401, "Scan the QR code on your TV to pair this phone.")
                }
                sessions[cookie] = SystemClock.elapsedRealtime() + 3_600_000
            }
            checkRevision()
            val response = when ("$method $path") {
                "GET /api/options" -> catalogOptions(type(params))
                "GET /api/catalog" -> catalog(params)
                "GET /api/title" -> details(params)
                "GET /api/status" -> PhoneRemote.status()
                "POST /api/play" -> play(json)
                "POST /api/control" -> PhoneRemote.command(json.optString("command"), json.optLong("position", 0))
                "GET /api/art" -> {
                    val bytes = artwork(entry(params["id"].orEmpty()), params["version"])
                    reply(client, 200, "image/jpeg", bytes, cookieHeader(cookie!!), cache = true)
                    return@use
                }
                else -> throw HttpError(404, "Not found.")
            }
            replyJson(client, response, cookieHeader(cookie!!))
        } catch (error: Exception) {
            runCatching {
                val status = (error as? HttpError)?.code ?: if (error is IllegalArgumentException) 400 else 503
                // Provider exceptions and URLs must never enter a browser response.
                val message = if (error is HttpError) error.message else "The TV could not complete this request. Try again."
                reply(client, status, "application/json", JSONObject().put("error", message).toString().toByteArray())
            }
        }
    }

    private fun type(params: Map<String, String>) = runCatching { ContentType.valueOf(params["type"] ?: "MOVIE") }
        .getOrElse { throw HttpError(400, "Unknown catalog type.") }

    private fun catalogOptions(type: ContentType): JSONObject {
        synchronized(this) { options[type]?.let { return it } }
        val providers = linkedSetOf<String>()
        val languages = sortedSetOf<String>()
        val genres = sortedSetOf<String>()
        if (type == ContentType.LIVE) Repo.library.value.liveGroups.forEach { genres.add(it.first) }
        else for (title in LibraryOrganization.catalog(Repo.library.value, type)) {
            genres.addAll(catalogGenres(title))
            for (version in title.versions) {
                providers.add(if (version.provider == CatalogProvider.DISNEY) CatalogProvider.OTHERS.name else version.provider.name)
                languages.add(version.language)
            }
        }
        return JSONObject().put("providers", JSONArray().also { array ->
            CatalogProvider.entries.filter { it.name in providers }.forEach { array.put(JSONObject().put("id", it.name).put("name", it.label)) }
        }).put("languages", JSONArray(languages.toList())).put("genres", JSONArray(genres.take(500)))
            .also { synchronized(this) { options[type] = it } }
    }

    private fun catalog(params: Map<String, String>): JSONObject {
        val type = type(params)
        val query = params["q"].orEmpty().take(120).trim()
        val provider = params["provider"].orEmpty()
        val selectedProvider = provider.takeIf { it.isNotBlank() }?.let { name ->
            CatalogProvider.entries.firstOrNull { it.name == name } ?: throw HttpError(400, "Unknown provider.")
        }
        val genre = params["genre"].orEmpty()
        val language = params["language"].orEmpty()
        val languages = language.split(',').filter { it.isNotBlank() }
        val qualities = params["quality"].orEmpty().split(',').mapNotNull { it.toIntOrNull() }
        val offset = (params["offset"]?.toIntOrNull() ?: 0).coerceIn(0, Int.MAX_VALUE - 48)
        val limit = (params["limit"]?.toIntOrNull() ?: 24).coerceIn(1, 48)
        val array = JSONArray()
        var total = 0
        if (type == ContentType.LIVE) {
            for (channel in Repo.library.value.all) {
                if (channel.type != ContentType.LIVE || (genre.isNotEmpty() && channel.group != genre) || !channel.name.contains(query, true)) continue
                if (total++ in offset until offset + limit) {
                    val id = remember(Entry(live = channel))
                    array.put(JSONObject().put("id", id).put("name", channel.name.take(240)).put("type", "LIVE")
                        .put("subtitle", channel.group.take(160)).put("art", "/api/art?id=$id"))
                }
            }
        } else {
            for (title in LibraryOrganization.catalog(Repo.library.value, type)) {
                if (!title.name.contains(query, true)) continue
                if (genre.isNotEmpty() && genre !in catalogGenres(title)) continue
                val versions = title.versions.filter {
                    (selectedProvider == null || LibraryOrganization.providerMatches(it.provider, selectedProvider)) &&
                    (languages.isEmpty() || it.language in languages) && (qualities.isEmpty() || it.quality in qualities)
                }
                if (versions.isEmpty()) continue
                if (total++ !in offset until offset + limit) continue
                val selected = LibraryOrganization.choose(versions, Repo.settings.value)
                val id = remember(Entry(title = title))
                array.put(JSONObject().put("id", id).put("name", title.name.take(240)).put("type", type.name)
                    .put("subtitle", listOfNotNull(title.year?.toString(), selected.provider.label).joinToString(" · "))
                    .put("version", versionId(selected)).put("art", "/api/art?id=$id&version=${versionId(selected)}"))
            }
        }
        return JSONObject().put("items", array).put("total", total).put("offset", offset).put("limit", limit)
            .put("loading", Repo.loading.value)
    }

    private fun details(params: Map<String, String>): JSONObject {
        val id = params["id"].orEmpty()
        val entry = entry(id)
        entry.live?.let { return JSONObject().put("id", id).put("name", it.name).put("type", "LIVE").put("summary", it.group)
            .put("versions", JSONArray()).put("episodes", JSONArray()).put("art", "/api/art?id=$id") }
        val title = entry.title!!
        val version = chosen(entry, params["version"])
        val episodes = version.series?.episodes.orEmpty()
        val seasons = episodes.map { it.season ?: 1 }.distinct().sorted()
        val season = params["season"]?.toIntOrNull()?.takeIf { it in seasons } ?: seasons.firstOrNull()
        val offset = (params["offset"]?.toIntOrNull() ?: 0).coerceIn(0, episodes.size)
        val inSeason = episodes.filter { (it.season ?: 1) == season }
        return JSONObject().put("id", id).put("name", title.name).put("type", title.type.name)
            .put("version", versionId(version)).put("art", "/api/art?id=$id&version=${versionId(version)}")
            .put("summary", version.visual?.summary.orEmpty().take(4000)).put("facts", version.visual?.facts.orEmpty().take(600))
            .put("season", season).put("seasons", JSONArray(seasons)).put("episodeTotal", inSeason.size)
            .put("versions", JSONArray().also { array -> title.versions.take(100).forEach {
                array.put(JSONObject().put("id", versionId(it)).put("label", "${it.language} · ${LibraryOrganization.qualityLabel(it.quality)} · ${it.provider.label}"))
            } }).put("episodes", JSONArray().also { array -> inSeason.drop(offset).take(50).forEach { episode ->
                val saved = Repo.progressFor(episode)
                array.put(JSONObject().put("id", opaque("episode", episode.id)).put("name", LibraryOrganization.clean(episode.name).take(240))
                    .put("label", episode.episodeLabel.orEmpty()).put("progress", saved?.fraction ?: 0))
            } })
    }

    private fun play(json: JSONObject): JSONObject {
        val entry = entry(json.optString("id"))
        entry.live?.let { channel ->
            val queue = Repo.library.value.liveQueueFor(channel)
            return PhoneRemote.startPlayback(queue, queue.indexOfFirst { it.id == channel.id }.coerceAtLeast(0), 0)
        }
        val version = chosen(entry, json.optString("version"))
        val queue = version.movie?.let { listOf(it) } ?: version.series!!.episodes
        if (queue.isEmpty()) throw HttpError(409, "No episodes available.")
        val episode = json.optString("episode")
        val index = if (episode.isNotEmpty()) queue.indexOfFirst { opaque("episode", it.id) == episode }
        else {
            val latest = queue.indices.mapNotNull { i -> Repo.progressFor(queue[i])?.takeIf { it.positionMs >= 1000 || it.isFinished }?.let { i to it } }
                .maxByOrNull { it.second.updatedAt }
            when { latest == null -> 0; latest.second.isFinished && latest.first < queue.lastIndex -> latest.first + 1; else -> latest.first }
        }
        if (index < 0) throw HttpError(409, "That episode is no longer available.")
        val saved = Repo.progressFor(queue[index])?.takeIf { it.isInProgress }
        return PhoneRemote.startPlayback(queue, index, saved?.positionMs ?: 0)
    }

    private fun artwork(entry: Entry, version: String?): ByteArray {
        val url = (entry.live?.artwork ?: chosen(entry, version).poster)?.toHttpUrlOrNull() ?: throw HttpError(404, "No artwork.")
        val file = File(imageDir, opaque("art", url.toString()) + ".jpg")
        if (file.exists()) return file.readBytes()
        var current = url
        repeat(4) {
            if (current.scheme !in listOf("http", "https") || current.username.isNotEmpty() || current.password.isNotEmpty() ||
                InetAddress.getAllByName(current.host).any { !publicAddress(it) }) throw HttpError(404, "Artwork unavailable.")
            imageClient.newCall(Request.Builder().url(current).build()).execute().use { response ->
                if (response.isRedirect) {
                    current = current.resolve(response.header("Location").orEmpty()) ?: throw HttpError(404, "Artwork unavailable.")
                } else {
                    if (!response.isSuccessful) throw HttpError(404, "Artwork unavailable.")
                    val body = response.body ?: throw HttpError(404, "Artwork unavailable.")
                    if (body.contentLength() > 2_000_000) throw HttpError(404, "Artwork too large.")
                    val buffer = ByteArrayOutputStream()
                    body.byteStream().use { input ->
                        val chunk = ByteArray(8192)
                        while (true) {
                            val n = input.read(chunk)
                            if (n < 0) break
                            if (buffer.size() + n > 2_000_000) throw HttpError(404, "Artwork too large.")
                            buffer.write(chunk, 0, n)
                        }
                    }
                    val bytes = buffer.toByteArray()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth !in 1..20_000 || bounds.outHeight !in 1..20_000) throw HttpError(404, "Invalid artwork.")
                    var sample = 1
                    while (bounds.outWidth / sample > 600 || bounds.outHeight / sample > 900) sample *= 2
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                        inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565
                    }) ?: throw HttpError(404, "Invalid artwork.")
                    val out = ByteArrayOutputStream()
                    try { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out) } finally { bitmap.recycle() }
                    val result = out.toByteArray()
                    synchronized(imageDir) {
                        val files = imageDir.listFiles().orEmpty().sortedBy { it.lastModified() }
                        var total = files.sumOf { it.length() }
                        for (old in files) { if (total + result.size <= 20_000_000) break; val size = old.length(); if (old.delete()) total -= size }
                        file.writeBytes(result)
                    }
                    return result
                }
            }
        }
        throw HttpError(404, "Artwork unavailable.")
    }
    private fun publicAddress(address: InetAddress): Boolean {
        val raw = address.address
        if (raw.size == 4) {
            val first = raw[0].toInt() and 255
            val second = raw[1].toInt() and 255
            if (first == 0 || first == 127 || first >= 224 || (first == 100 && second in 64..127)) return false
        }
        return !address.isAnyLocalAddress && !address.isLoopbackAddress &&
        !address.isLinkLocalAddress && !address.isSiteLocalAddress && !address.isMulticastAddress &&
        !(address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc)
    }

    private fun cookieHeader(value: String) = "Set-Cookie: svremote=$value; HttpOnly; SameSite=Strict; Path=/api; Max-Age=3600\r\n"

    private fun replyJson(socket: Socket, data: JSONObject, extra: String = "") =
        reply(socket, 200, "application/json; charset=utf-8", data.toString().toByteArray(), extra)
    private fun reply(socket: Socket, code: Int, type: String, bytes: ByteArray, extra: String = "", cache: Boolean = false) {
        val headers = "HTTP/1.1 $code Response\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n" +
            "Cache-Control: ${if (cache) "private, max-age=600" else "no-store"}\r\nX-Content-Type-Options: nosniff\r\n" +
            "Referrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'\r\n" + extra + "\r\n"
        socket.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
    }
}
