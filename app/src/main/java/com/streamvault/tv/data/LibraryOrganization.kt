package com.streamvault.tv.data

import java.util.Locale
import android.os.SystemClock
import android.util.Log

enum class CatalogProvider(val label: String, val logo: String?) {
    ALL("All", null), NETFLIX("Netflix", "netflix"), PRIME("Prime Video", "primevideo"),
    APPLE("Apple TV+", "appletv"), HULU("Hulu", "hulu"), DISNEY("Disney+", "disneyplus"),
    HBO("HBO Max", "hbo"), PARAMOUNT("Paramount+", "paramountplus"),
    STARZPLAY("Starzplay", null), BET("BET+", null), QUIBI("Quibi", null), HAYU("Hayu", null),
    PEACOCK("Peacock", null), OTHERS("Others", null)
}

data class CatalogVersion(
    val movie: Channel? = null,
    val series: Series? = null,
    val language: String = LibraryOrganization.language(movie?.name ?: series!!.name, movie?.group ?: series!!.group),
    val quality: Int = LibraryOrganization.quality("${movie?.name ?: series!!.name} ${movie?.group ?: series!!.group}"),
    val provider: CatalogProvider = LibraryOrganization.provider(movie?.group ?: series!!.group),
    val year: Int? = LibraryOrganization.year(movie?.name ?: series!!.name, movie?.visual?.facts ?: series?.visual?.facts),
    val rating: Double? = LibraryOrganization.rating(movie?.visual?.facts ?: series?.visual?.facts),
    val genres: List<String> = LibraryOrganization.genres(movie?.visual?.facts ?: series?.visual?.facts, movie?.group ?: series!!.group),
) {
    val sourceName get() = movie?.name ?: series!!.name
    val sourceGroup get() = movie?.group ?: series!!.group
    val id get() = movie?.id ?: series!!.favoriteId
    val visual get() = movie?.visual ?: series?.visual
    val poster get() = movie?.artwork ?: series?.poster
}

data class CatalogTitle(val key: String, val name: String, val type: ContentType, val versions: List<CatalogVersion>) {
    val year get() = versions.firstNotNullOfOrNull { it.year }
    val rating get() = versions.firstNotNullOfOrNull { it.rating }
}

object LibraryOrganization {
    private val languages = linkedMapOf("EN" to "English", "ENG" to "English", "FR" to "French", "FRENCH" to "French",
        "ES" to "Spanish", "SP" to "Spanish", "DE" to "German", "GER" to "German", "IT" to "Italian",
        "PT" to "Portuguese", "AR" to "Arabic", "HI" to "Hindi", "IN" to "Hindi", "TR" to "Turkish",
        "NL" to "Dutch", "RU" to "Russian", "PL" to "Polish", "US" to "English", "UK" to "English", "CA" to "English")
    private val prefix = Regex("^(?:${languages.keys.joinToString("|")})\\s*(?:[★✪❖✦*|:·-]+\\s*|(?=\\s))", RegexOption.IGNORE_CASE)
    private val tech = Regex("\\s*[\\[({](?:4K|UHD|FHD|HD|SD|1080P|720P|2160P|MULTI[ -]?(?:AUDIO|SUB|SUBS|AUDIO/SUB)|HEVC|H\\.?26[45]|HDR|DOLBY[^]})]*|REMUX|WEB[- ]?DL|BLU[- ]?RAY)[\\])}]", RegexOption.IGNORE_CASE)
    private val tail = Regex("\\s+(?:4K|UHD|FHD|HD|SD|1080P|720P|2160P|HEVC|H\\.?26[45])$", RegexOption.IGNORE_CASE)
    private val whitespace = Regex("\\s+")
    private val languageToken = Regex("^[A-Za-z]{2,6}\\b")
    private val quality4k = Regex("\\b(4K|UHD|2160P)\\b", RegexOption.IGNORE_CASE)
    private val quality1080 = Regex("\\b(FHD|1080P)\\b", RegexOption.IGNORE_CASE)
    private val quality720 = Regex("\\b(HD|720P)\\b", RegexOption.IGNORE_CASE)
    private val qualitySd = Regex("\\bSD\\b", RegexOption.IGNORE_CASE)
    private val ratingPattern = Regex("★\\s*([0-9.]+)")
    private val maxProvider = Regex("\\bmax\\b")
    private val yearPattern = Regex("\\b(?:19|20)\\d{2}\\b")
    private val releaseYear = Regex("(?:\\(|\\[|\\s*[-–|:]\\s*)((?:19|20)\\d{2})(?:\\)|\\])?$")
    private val knownGenres = listOf("Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama", "Family", "Fantasy", "History", "Horror", "Music", "Mystery", "Romance", "Science Fiction", "Thriller", "War", "Western", "Reality")
    private val combiningMarks = Regex("\\p{M}+")
    private val betProvider = Regex("\\bbet(?:\\+|\\b)")
    private fun plain(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
        .replace(combiningMarks, "").lowercase(Locale.ROOT).trim()
    private val genreAliases = buildMap {
        knownGenres.forEach { put(plain(it), it) }
        listOf(
            "Comedy" to listOf("comedia", "comedie", "commedia", "comenda", "komodie", "komedie", "komedia", "komedi"),
            "Action" to listOf("accion", "azione", "acao", "aktion"),
            "Adventure" to listOf("aventura", "aventure", "avventura", "abenteuer"),
            "Animation" to listOf("animacion", "animacao", "animazione", "animação"),
            "Drama" to listOf("drame", "dramma"),
            "Documentary" to listOf("documental", "documentaire", "documentario", "dokumentarfilm", "documentaries"),
            "Crime" to listOf("crimen", "krimi", "criminal", "policier"),
            "Family" to listOf("familia", "famille", "familienfilm", "familiare"),
            "Fantasy" to listOf("fantasia", "fantastique", "fantastico"),
            "Horror" to listOf("terror", "horreur", "orrore"),
            "Mystery" to listOf("misterio", "mystere", "mistero"),
            "Romance" to listOf("romantica", "romantico", "romantique", "romantik"),
            "Science Fiction" to listOf("sci-fi", "scifi", "ciencia ficcion", "science-fiction", "fantascienza"),
            "Thriller" to listOf("suspense", "suspenso"),
            "War" to listOf("guerra", "guerre", "krieg"),
            "History" to listOf("historia", "histoire", "historique", "historico"),
            "Music" to listOf("musica", "musique", "musik"),
            "Reality" to listOf("reality tv", "reality-tv")
        ).forEach { (genre, aliases) -> aliases.forEach { put(plain(it), genre) } }
    }
    private val genrePatterns = genreAliases.map { (alias, genre) -> genre to Regex("\\b${Regex.escape(alias)}\\b") }
    private val groupGenres = android.util.LruCache<String, List<String>>(1024)
    private val canonicalGenres = android.util.LruCache<String, String>(1024)
    private val suppliedGenres = android.util.LruCache<String, List<String>>(1024)
    private var revision = Long.MIN_VALUE
    private val catalogs = HashMap<ContentType, List<CatalogTitle>>()

    fun clean(name: String): String {
        var value = name.trim()
        repeat(2) { value = prefix.replaceFirst(value, "").trimStart(' ', '★', '✪', '*', '|', ':', '-') }
        value = tech.replace(value, "")
        repeat(2) { value = tail.replace(value, "") }
        value = releaseYear.replace(value.trim(), "")
        return whitespace.replace(value, " ").trim().ifBlank { name }
    }
    fun language(name: String, group: String): String {
        for (text in listOf(name, group)) {
            val token = languageToken.find(text.trim())?.value?.uppercase(Locale.ROOT)
            languages[token]?.let { return it }
        }
        return "Unknown"
    }
    fun quality(name: String): Int = when {
        quality4k.containsMatchIn(name) -> 2160
        quality1080.containsMatchIn(name) -> 1080
        quality720.containsMatchIn(name) -> 720
        qualitySd.containsMatchIn(name) -> 480
        else -> 0
    }
    fun qualityLabel(value: Int) = when(value) { 2160 -> "4K"; 1080 -> "FHD / 1080p"; 720 -> "HD / 720p"; 480 -> "SD"; else -> "Unspecified" }
    fun providerMatches(actual: CatalogProvider, selected: CatalogProvider) = selected == CatalogProvider.ALL || actual == selected ||
        (selected == CatalogProvider.OTHERS && actual == CatalogProvider.DISNEY)
    private val providerFormatting = Regex("\\p{Cf}")
    private val providerSeparators = Regex("[^a-z0-9]+")
    private val groupProviders = android.util.LruCache<String, CatalogProvider>(1024)

    fun provider(group: String): CatalogProvider {
        groupProviders.get(group)?.let { return it }
        // Normalize full-width text and invisible formatting as well as capitalization.
        val g = plain(java.text.Normalizer.normalize(group, java.text.Normalizer.Form.NFKC)
            .replace(providerFormatting, ""))
        val compact = g.replace(providerSeparators, "")
        return when {
            "netflix" in g -> CatalogProvider.NETFLIX
            "amazon" in g || "prime" in g -> CatalogProvider.PRIME
            "apple" in g -> CatalogProvider.APPLE
            "hulu" in g -> CatalogProvider.HULU
            "disney" in g -> CatalogProvider.DISNEY
            "hbo" in g || maxProvider.containsMatchIn(g) -> CatalogProvider.HBO
            "paramount" in g || "paramout" in g -> CatalogProvider.PARAMOUNT
            "starz" in compact -> CatalogProvider.STARZPLAY
            betProvider.containsMatchIn(g) -> CatalogProvider.BET
            "quibi" in compact -> CatalogProvider.QUIBI
            "hayu" in compact -> CatalogProvider.HAYU
            "peacock" in g -> CatalogProvider.PEACOCK
            else -> CatalogProvider.OTHERS
        }.also { groupProviders.put(group, it) }
    }

    private fun logProviderCoverage(type: ContentType, titles: List<CatalogTitle>) {
        val counts = java.util.EnumMap<CatalogProvider, Int>(CatalogProvider::class.java)
        for (title in titles) for (provider in title.versions.map { it.provider }.distinct()) {
            counts[provider] = (counts[provider] ?: 0) + 1
        }
        Log.i("CatalogProviders", "$type titles by provider: " +
            CatalogProvider.entries.filter { it != CatalogProvider.ALL }
                .joinToString { "${it.name}=${counts[it] ?: 0}" })
    }
    fun year(name: String, facts: String?): Int? = facts?.substringBefore(" · ")?.toIntOrNull()?.takeIf { it in 1900..2099 }
        ?: releaseYear.find(tech.replace(name, "").trim())?.groupValues?.get(1)?.toIntOrNull()
    fun genres(facts: String?, group: String): List<String> {
        // Cache genre combinations independently of each title's year and rating.
        val supplied = facts.orEmpty().split(" · ")
            .filter { !it.startsWith("★") && it.toIntOrNull() == null }.joinToString(" · ")
        if (supplied.isNotBlank()) {
            val result = suppliedGenres.get(supplied) ?: supplied.split(" · ")
                .flatMap { it.split(',', '/', ';', '|', '&') }.map { canonicalGenre(it) }
                .filter { it.isNotBlank() }.distinct().also { suppliedGenres.put(supplied, it) }
            if (result.isNotEmpty()) return result
        }
        groupGenres.get(group)?.let { return it }
        val normalizedGroup = plain(group)
        return genrePatterns.filter { it.second.containsMatchIn(normalizedGroup) }.map { it.first }.distinct()
            .ifEmpty { listOf("Other genres") }.also { groupGenres.put(group, it) }
    }
    fun canonicalGenre(value: String): String {
        canonicalGenres.get(value)?.let { return it }
        val trimmed = clean(value).trim()
        val normalized = plain(trimmed)
        return (genreAliases[normalized] ?: genrePatterns.firstOrNull { it.second.containsMatchIn(normalized) }?.first
            ?: trimmed.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) })
            .also { canonicalGenres.put(value, it) }
    }
    fun rating(facts: String?): Double? = ratingPattern.find(facts.orEmpty())?.groupValues?.get(1)?.toDoubleOrNull()
    fun titleKey(name: String, facts: String?): String {
        val cleaned = clean(name)
        return cleaned.lowercase(Locale.ROOT) + "|" + (year(name, facts)?.toString() ?: "unknown-year")
    }
    @Synchronized fun catalog(lib: Library, type: ContentType): List<CatalogTitle> {
        if (revision != lib.revision) { catalogs.clear(); revision = lib.revision }
        return catalogs.getOrPut(type) {
            val started = SystemClock.elapsedRealtimeNanos()
            val fingerprint = lib.sourceFingerprint
            if (fingerprint != null) {
                CatalogSnapshot.read(Repo.catalogSnapshotFile(type), fingerprint, lib, type)?.let {
                    logProviderCoverage(type, it)
                    Log.i("CatalogTiming", "$type disk cache: ${it.size} titles in ${(SystemClock.elapsedRealtimeNanos() - started) / 1_000_000}ms")
                    return@getOrPut it
                }
            }
            var metadataNs = 0L
            var keyNs = 0L
            var count = 0
            class Group(val name: String, val versions: MutableList<CatalogVersion> = ArrayList(1))
            val grouped = LinkedHashMap<String, Group>()
            fun add(name: String, version: () -> CatalogVersion) {
                val metadataStart = SystemClock.elapsedRealtimeNanos()
                val value = version()
                metadataNs += SystemClock.elapsedRealtimeNanos() - metadataStart
                val keyStart = SystemClock.elapsedRealtimeNanos()
                val cleaned = clean(name)
                val key = cleaned.lowercase(Locale.ROOT) + "|" + (value.year?.toString() ?: "unknown-year")
                keyNs += SystemClock.elapsedRealtimeNanos() - keyStart
                grouped.getOrPut(key) { Group(cleaned) }.versions.add(value)
                count++
            }
            if (type == ContentType.MOVIE) for ((_, channels) in lib.movieGroups) for (channel in channels) {
                add(channel.name) { CatalogVersion(movie = channel) }
            } else for (series in lib.series.values) {
                add(series.name) { CatalogVersion(series = series) }
            }
            grouped.map { (key, group) -> CatalogTitle(key, group.name, type, group.versions) }.also {
                logProviderCoverage(type, it)
                if (fingerprint != null) {
                    try {
                        CatalogSnapshot.write(Repo.catalogSnapshotFile(type), fingerprint, type, it)
                    } catch (error: Exception) {
                        Log.w("CatalogTiming", "$type cache save failed: ${error.javaClass.simpleName}")
                    }
                }
                Log.i("CatalogTiming", "$type: $count versions, ${it.size} titles; keys=${keyNs / 1_000_000}ms metadata=${metadataNs / 1_000_000}ms total=${(SystemClock.elapsedRealtimeNanos() - started) / 1_000_000}ms")
            }
        }
    }
    fun choose(versions: List<CatalogVersion>, settings: AppSettings): CatalogVersion = versions.minWith(compareBy<CatalogVersion>(
        { if (settings.preferredLanguage == "Any" || it.language == settings.preferredLanguage) 0 else 1 },
        { if (it.quality == 0 || it.quality <= settings.maximumQuality) 0 else 1 },
        { -it.quality }))
    fun progressKey(channel: Channel): String? = when(channel.type) {
        ContentType.MOVIE -> "organized:movie:${titleKey(channel.name, channel.visual?.facts)}"
        ContentType.SERIES -> if (channel.season != null && channel.episode != null)
            "organized:episode:${titleKey(channel.seriesName ?: channel.name, channel.visual?.facts)}:${channel.season}:${channel.episode}" else null
        else -> null
    }
}
