package com.streamvault.tv.subtitles

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * Minimal client for the OpenSubtitles.com REST API (v1).
 * A free API key: https://www.opensubtitles.com/en/consumers
 * Logging in (optional) raises the daily download quota.
 * All calls are blocking: run them on Dispatchers.IO.
 */
class OpenSubtitlesClient(private val http: OkHttpClient) {

    data class Result(
        val fileId: Long,
        val fileName: String,
        val release: String,
        val language: String,
        val downloads: Int,
        val hearingImpaired: Boolean,
        val title: String,
    )

    private val base = "https://api.opensubtitles.com/api/v1"
    private val json = "application/json".toMediaType()

    private fun Request.Builder.common(apiKey: String): Request.Builder =
        header("Api-Key", apiKey.trim())
            .header("User-Agent", "StreamVault v1.0")
            .header("Accept", "application/json")

    fun search(
        apiKey: String,
        query: String,
        languages: String,
        season: Int?,
        episode: Int?,
        year: Int?,
    ): List<Result> {
        // OpenSubtitles asks for lower-case, alphabetically ordered parameters.
        val url = "$base/subtitles".toHttpUrl().newBuilder().apply {
            if (episode != null) addQueryParameter("episode_number", episode.toString())
            if (languages.isNotBlank()) addQueryParameter("languages", languages.trim().lowercase())
            addQueryParameter("query", query.trim().lowercase())
            if (season != null) addQueryParameter("season_number", season.toString())
            if (year != null && season == null) addQueryParameter("year", year.toString())
        }.build()
        val request = Request.Builder().url(url).common(apiKey).get().build()
        val body = http.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException(errorMessage(r.code, text))
            text
        }
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Result>()
        for (i in 0 until data.length()) {
            val attrs = data.optJSONObject(i)?.optJSONObject("attributes") ?: continue
            val file = attrs.optJSONArray("files")?.optJSONObject(0) ?: continue
            val feature = attrs.optJSONObject("feature_details")
            out.add(
                Result(
                    fileId = file.optLong("file_id"),
                    fileName = file.optString("file_name"),
                    release = attrs.optString("release"),
                    language = attrs.optString("language"),
                    downloads = attrs.optInt("download_count"),
                    hearingImpaired = attrs.optBoolean("hearing_impaired"),
                    title = feature?.optString("title").orEmpty(),
                )
            )
        }
        return out.filter { it.fileId != 0L }
    }

    fun login(apiKey: String, username: String, password: String): String {
        val payload = JSONObject().put("username", username).put("password", password).toString()
        val request = Request.Builder().url("$base/login").common(apiKey)
            .post(payload.toRequestBody(json)).build()
        return http.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException(errorMessage(r.code, text))
            JSONObject(text).optString("token").ifBlank { throw IOException("Login failed") }
        }
    }

    /** Returns the subtitle file as text. */
    fun download(apiKey: String, token: String?, fileId: Long): String {
        val payload = JSONObject().put("file_id", fileId).toString()
        val builder = Request.Builder().url("$base/download").common(apiKey).post(payload.toRequestBody(json))
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        val link = http.newCall(builder.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException(errorMessage(r.code, text))
            JSONObject(text).optString("link")
        }
        if (link.isBlank()) throw IOException("OpenSubtitles didn't return a download link")
        return fetchText(link)
    }

    fun fetchText(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "StreamVault v1.0").build()
        val bytes = http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Download failed (HTTP ${r.code})")
            r.body?.bytes() ?: ByteArray(0)
        }
        return SubtitleParser.decode(bytes)
    }

    private fun errorMessage(code: Int, body: String): String {
        val msg = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
            .ifBlank { body.take(140) }
        return when (code) {
            401, 403 -> "OpenSubtitles rejected the request ($code). Check your API key in Settings. $msg"
            406 -> "Daily download limit reached. Log in under Settings to get more. $msg"
            429 -> "Too many requests, try again in a moment."
            else -> "OpenSubtitles error $code: $msg"
        }.trim()
    }
}
