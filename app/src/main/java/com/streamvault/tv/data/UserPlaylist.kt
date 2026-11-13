package com.streamvault.tv.data

import org.json.JSONArray
import org.json.JSONObject

/** A profile's curated collection, distinct from an IPTV source Playlist. Stores references only. */
data class UserPlaylist(val id: String, val name: String, val itemIds: List<String> = emptyList())

class UserPlaylistError(message: String) : IllegalArgumentException(message)

internal fun encodeUserPlaylists(lists: List<UserPlaylist>): String = JSONArray().apply {
    lists.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("items", JSONArray(it.itemIds))) }
}.toString()

internal fun decodeUserPlaylists(value: String): List<UserPlaylist> = runCatching {
    val array = JSONArray(value)
    (0 until minOf(array.length(), 30)).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("id")
        val name = item.optString("name").trim().take(48)
        if (!id.matches(Regex("[a-f0-9-]{36}")) || name.isBlank()) return@mapNotNull null
        val ids = item.optJSONArray("items") ?: JSONArray()
        UserPlaylist(id, name, (0 until minOf(ids.length(), 300)).mapNotNull {
            ids.optString(it).takeIf { ref -> ref.isNotBlank() && ref.length <= 4096 }
        }.distinct())
    }.distinctBy { it.id }
}.getOrDefault(emptyList())
