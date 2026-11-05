package com.streamvault.tv.data

import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.IdentityHashMap

/** Private, disposable parsed-data cache. Never replaces the original playlist files. */
internal object LibrarySnapshot {
    private const val VERSION = 1
    private const val MAGIC = 0x53564C42
    private const val MAX_SHARED_STRINGS = 65536

    fun read(file: File, fingerprint: String): List<Channel>? {
        val atomic = AtomicFile(file)
        return try {
            DataInputStream(atomic.openRead().buffered()).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION || input.readUTF() != fingerprint) return null
                val count = input.readInt()
                require(count in 0..2_000_000)
                val strings = ArrayList<String>()
                fun text(): String {
                    val size = input.readInt()
                    require(size in 0..4_000_000)
                    val bytes = ByteArray(size)
                    input.readFully(bytes)
                    return String(bytes, Charsets.UTF_8)
                }
                fun shared(): String? = when (val index = input.readInt()) {
                    -1 -> null
                    -2 -> text().also { if (strings.size < MAX_SHARED_STRINGS) strings.add(it) }
                    else -> { require(index in strings.indices); strings[index] }
                }
                fun optionalText(): String? = if (input.readBoolean()) text() else null
                val visuals = ArrayList<TitleArtwork>()
                fun artwork(): TitleArtwork? = when (val index = input.readInt()) {
                    -1 -> null
                    -2 -> TitleArtwork(optionalText(), optionalText(), optionalText(), optionalText(), optionalText()).also { visuals.add(it) }
                    else -> { require(index in visuals.indices); visuals[index] }
                }
                val channels = ArrayList<Channel>(count)
                repeat(count) {
                    val url = text()
                    val name = text()
                    val logo = shared()
                    val group = requireNotNull(shared())
                    val type = ContentType.entries[input.readUnsignedByte()]
                    val userAgent = shared()
                    val referrer = shared()
                    val playlistId = requireNotNull(shared())
                    val seriesKey = shared()
                    val seriesName = shared()
                    val season = input.readInt().takeUnless { it == Int.MIN_VALUE }
                    val episode = input.readInt().takeUnless { it == Int.MIN_VALUE }
                    channels.add(Channel(id = url, name = name, logo = logo, group = group, url = url,
                        type = type, userAgent = userAgent, referrer = referrer, playlistId = playlistId,
                        seriesKey = seriesKey, seriesName = seriesName, season = season, episode = episode,
                        poster = shared(), visual = artwork()))
                }
                require(input.readInt() == MAGIC && input.read() == -1)
                channels
            }
        } catch (_: Exception) { null }
    }

    fun write(file: File, fingerprint: String, channels: List<Channel>) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            val output = DataOutputStream(stream.buffered())
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeUTF(fingerprint)
            output.writeInt(channels.size)
            // Bound the transient dictionary so saving a huge library does not exhaust the heap.
            val strings = HashMap<String, Int>()
            fun text(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                output.writeInt(bytes.size)
                output.write(bytes)
            }
            fun shared(value: String?) {
                if (value == null) { output.writeInt(-1); return }
                val index = strings[value]
                if (index != null) output.writeInt(index)
                else {
                    output.writeInt(-2)
                    text(value)
                    if (strings.size < MAX_SHARED_STRINGS) strings[value] = strings.size
                }
            }
            fun optionalText(value: String?) {
                output.writeBoolean(value != null)
                if (value != null) text(value)
            }
            val visuals = IdentityHashMap<TitleArtwork, Int>()
            fun artwork(value: TitleArtwork?) {
                if (value == null) { output.writeInt(-1); return }
                val index = visuals[value]
                if (index != null) output.writeInt(index)
                else {
                    output.writeInt(-2)
                    visuals[value] = visuals.size
                    optionalText(value.poster); optionalText(value.backdrop); optionalText(value.trailer)
                    optionalText(value.summary); optionalText(value.facts)
                }
            }
            for (channel in channels) {
                require(channel.id == channel.url)
                text(channel.url); text(channel.name)
                shared(channel.logo); shared(channel.group)
                output.writeByte(channel.type.ordinal)
                shared(channel.userAgent); shared(channel.referrer); shared(channel.playlistId)
                shared(channel.seriesKey); shared(channel.seriesName)
                output.writeInt(channel.season ?: Int.MIN_VALUE)
                output.writeInt(channel.episode ?: Int.MIN_VALUE)
                shared(channel.poster); artwork(channel.visual)
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
