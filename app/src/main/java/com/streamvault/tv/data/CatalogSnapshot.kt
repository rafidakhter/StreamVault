package com.streamvault.tv.data

import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Cached organization metadata; references the current library instead of duplicating streams. */
internal object CatalogSnapshot {
    private const val MAGIC = 0x53564341
    // Bump when title matching or metadata normalization changes.
    private const val VERSION = 1

    fun read(file: File, fingerprint: String, lib: Library, type: ContentType): List<CatalogTitle>? = try {
        DataInputStream(AtomicFile(file).openRead().buffered()).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION ||
                input.readUTF() != fingerprint || input.readUTF() != type.name) return null
            val count = input.readInt()
            require(count in 0..2_000_000)
            var versionCount = 0
            val strings = HashMap<String, String>()
            fun pooled(): String {
                val value = input.readUTF()
                return strings[value] ?: value.also { if (strings.size < 4096) strings[it] = it }
            }
            val genreLists = HashMap<List<String>, List<String>>()
            val titles = ArrayList<CatalogTitle>(count)
            repeat(count) {
                val key = input.readUTF()
                val name = input.readUTF()
                val versions = input.readInt()
                require(versions in 1..2_000_000 && versionCount <= 2_000_000 - versions)
                versionCount += versions
                val entries = ArrayList<CatalogVersion>(versions)
                repeat(versions) {
                    val source = input.readUTF()
                    val movie = if (type == ContentType.MOVIE) requireNotNull(lib.byId[source]) else null
                    require(movie == null || movie.type == type)
                    val series = if (type == ContentType.SERIES) requireNotNull(lib.series[source]) else null
                    val language = pooled()
                    val quality = input.readInt()
                    val provider = CatalogProvider.valueOf(input.readUTF())
                    val year = input.readInt().takeUnless { it == Int.MIN_VALUE }
                    val rating = input.readDouble().takeUnless { it.isNaN() }
                    val genreCount = input.readInt()
                    require(genreCount in 0..1024)
                    val rawGenres = List(genreCount) { pooled() }
                    val genres = genreLists[rawGenres] ?: rawGenres.also {
                        if (genreLists.size < 4096) genreLists[it] = it
                    }
                    entries.add(CatalogVersion(movie, series, language, quality, provider, year, rating, genres))
                }
                titles.add(CatalogTitle(key, name, type, entries))
            }
            require(input.readInt() == MAGIC && input.read() == -1)
            titles
        }
    } catch (_: Exception) { null }

    fun write(file: File, fingerprint: String, type: ContentType, titles: List<CatalogTitle>) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            val output = DataOutputStream(stream.buffered())
            output.writeInt(MAGIC); output.writeInt(VERSION)
            output.writeUTF(fingerprint); output.writeUTF(type.name)
            output.writeInt(titles.size)
            for (title in titles) {
                output.writeUTF(title.key); output.writeUTF(title.name)
                output.writeInt(title.versions.size)
                for (version in title.versions) {
                    output.writeUTF(version.movie?.id ?: version.series!!.key)
                    output.writeUTF(version.language); output.writeInt(version.quality)
                    output.writeUTF(version.provider.name)
                    output.writeInt(version.year ?: Int.MIN_VALUE)
                    output.writeDouble(version.rating ?: Double.NaN)
                    output.writeInt(version.genres.size)
                    version.genres.forEach { output.writeUTF(it) }
                }
            }
            output.writeInt(MAGIC)
            output.flush()
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }
}
