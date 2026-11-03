package com.streamvault.tv.subtitles

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream

data class SubCue(val startMs: Long, val endMs: Long, val text: String)

/** SRT + WebVTT parser (the formats OpenSubtitles and most sites hand out). */
object SubtitleParser {
    private val timing = Regex(
        """(?:(\d+):)?(\d{1,2}):(\d{1,2})[,.](\d{1,3})\s*-->\s*(?:(\d+):)?(\d{1,2}):(\d{1,2})[,.](\d{1,3})"""
    )
    private val tags = Regex("""<[^>]+>|\{\\[^}]*\}""")

    fun parse(text: String): List<SubCue> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val cues = ArrayList<SubCue>()
        var i = 0
        while (i < lines.size) {
            val m = timing.find(lines[i])
            if (m == null) {
                i++
                continue
            }
            val g = m.groupValues
            val start = toMs(g[1], g[2], g[3], g[4])
            val end = toMs(g[5], g[6], g[7], g[8])
            i++
            val sb = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (timing.containsMatchIn(lines[i])) break
                val clean = tags.replace(lines[i], "").trim()
                if (clean.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(clean)
                }
                i++
            }
            if (sb.isNotEmpty() && end > start) cues.add(SubCue(start, end, sb.toString()))
        }
        cues.sortBy { it.startMs }
        return cues
    }

    private fun toMs(h: String, m: String, s: String, ms: String): Long {
        val hours = h.toLongOrNull() ?: 0L
        val millis = ms.padEnd(3, '0').take(3).toLong()
        return ((hours * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + millis
    }

    /** Decodes downloaded subtitle bytes: gunzips if needed, UTF-8 first then Windows-1252. */
    fun decode(bytes: ByteArray): String {
        val data = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
        } else bytes
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(data)).toString()
        } catch (e: CharacterCodingException) {
            String(data, Charset.forName("windows-1252"))
        }
        return text.removePrefix("﻿")
    }
}

/**
 * Side-loaded subtitles rendered by our own overlay, so the timing can be
 * shifted live (ExoPlayer can't offset side-loaded text tracks).
 * Positive offset = subtitles appear later.
 */
class ExternalSubtitles {
    var cues by mutableStateOf<List<SubCue>>(emptyList())
        private set
    var label by mutableStateOf<String?>(null)
        private set
    var offsetMs by mutableLongStateOf(0L)

    val active: Boolean get() = cues.isNotEmpty()

    fun load(newCues: List<SubCue>, newLabel: String) {
        cues = newCues
        label = newLabel
        offsetMs = 0L
    }

    fun clear() {
        cues = emptyList()
        label = null
        offsetMs = 0L
    }

    fun textAt(positionMs: Long): String? {
        val list = cues
        if (list.isEmpty()) return null
        val t = positionMs - offsetMs
        var lo = 0
        var hi = list.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].startMs <= t) {
                idx = mid
                lo = mid + 1
            } else hi = mid - 1
        }
        if (idx < 0) return null
        val parts = ArrayList<String>(2)
        var i = idx
        var checked = 0
        while (i >= 0 && checked < 6) {
            val c = list[i]
            if (c.endMs > t) parts.add(0, c.text)
            i--
            checked++
        }
        return if (parts.isEmpty()) null else parts.joinToString("\n")
    }
}

/** Turns IPTV titles like "EN | The Matrix (1999) [4K]" into a clean search query. */
object TitleCleaner {
    private val bracketed = Regex("""\[[^\]]*\]|\{[^}]*\}""")
    private val providerPrefix = Regex("""^\s*(?:\|[^|]{1,10}\|\s*|[A-Z]{2,4}\s*[-:|•]\s+)""")
    private val quality = Regex(
        """\b(4K|UHD|FHD|HD|SD|HDR|HEVC|H\.?26[45]|1080p|720p|2160p|MULTI(?:-?SUB)?|VOSTFR|DUAL|x26[45])\b""",
        RegexOption.IGNORE_CASE
    )
    private val yearInParens = Regex("""\((19|20)\d{2}\)""")
    private val trailingYear = Regex("""\s(19|20)\d{2}\s*$""")
    private val episodeTag = Regex("""[Ss]\d{1,2}\s*[Ee]\d{1,4}.*$""")

    fun year(name: String): Int? =
        yearInParens.find(name)?.value?.trim('(', ')')?.toIntOrNull()
            ?: trailingYear.find(name)?.value?.trim()?.toIntOrNull()

    fun clean(name: String): String {
        var s = name
        s = bracketed.replace(s, " ")
        s = providerPrefix.replace(s, "")
        s = episodeTag.replace(s, " ")
        s = yearInParens.replace(s, " ")
        s = trailingYear.replace(s, " ")
        s = quality.replace(s, " ")
        s = s.replace('.', ' ').replace('_', ' ')
        s = s.replace(Regex("""\s{2,}"""), " ").trim(' ', '-', '|', ':', '(', ')')
        return s.ifBlank { name }
    }
}
