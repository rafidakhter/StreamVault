package com.streamvault.tv.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.Locale

/** A profile is a local identity, not a provider account or a sign-in credential. */
data class UserProfile(val id: String, val name: String, val color: Int = 0) {
    val initials: String get() = name.trim().split(Regex("\\s+"))
        .filter { it.isNotBlank() }.take(2).joinToString("") { String(Character.toChars(it.codePointAt(0))) }.uppercase(Locale.ROOT).take(2).ifBlank { "U" }
}

/** Separate files avoid loading every profile at startup and keep progress writes scoped. */
internal class ProfileStore(private val context: Context, private val shared: SharedPreferences) {
    var profiles: List<UserProfile> = readProfiles().ifEmpty { listOf(UserProfile("default", "You")) }
        private set
    var activeId: String = shared.getString("active_profile", null)?.takeIf { id -> profiles.any { it.id == id } } ?: profiles.first().id
        private set
    val active: UserProfile get() = profiles.first { it.id == activeId }

    init {
        // Copy before removing legacy state. A failed/interrupted migration leaves the original data recoverable.
        if (!shared.getBoolean("profiles_v1_migrated", false)) {
            val target = preferences("default")
            val editor = target.edit()
            for (key in listOf("favorites", "recent", "progress", "settings")) {
                if (!target.contains(key) && shared.contains(key)) {
                    var value = shared.getString(key, null)
                    if (key == "settings" && value != null) {
                        value = runCatching { JSONObject(value).apply {
                            remove("osApiKey"); remove("osUsername"); remove("osPassword")
                        }.toString() }.getOrDefault(value)
                    }
                    editor.putString(key, value)
                }
            }
            if (editor.commit()) {
                shared.edit().putString("profiles", encode(profiles)).putString("active_profile", activeId)
                    .putBoolean("profiles_v1_migrated", true).remove("favorites").remove("recent").remove("progress").commit()
            }
        }
    }

    fun preferences(id: String = activeId): SharedPreferences =
        context.getSharedPreferences("streamvault_profile_$id", Context.MODE_PRIVATE)

    fun select(id: String): UserProfile {
        val profile = profiles.firstOrNull { it.id == id } ?: error("Profile not found.")
        check(shared.edit().putString("active_profile", id).commit()) { "Could not save the active profile." }
        activeId = id
        return profile
    }

    fun create(name: String): UserProfile {
        require(profiles.size < 8) { "You can create up to 8 profiles." }
        val normalized = validate(name)
        val profile = UserProfile(UUID.randomUUID().toString(), normalized, profiles.size % 6)
        save(profiles + profile, activeId)
        return profile
    }

    fun rename(id: String, name: String): UserProfile {
        val normalized = validate(name, id)
        val profile = profiles.firstOrNull { it.id == id }?.copy(name = normalized) ?: error("Profile not found.")
        save(profiles.map { if (it.id == id) profile else it }, activeId)
        return profile
    }

    fun remove(id: String) {
        require(profiles.size > 1) { "Keep at least one profile." }
        require(profiles.any { it.id == id }) { "Profile not found." }
        val next = profiles.filterNot { it.id == id }
        save(next, if (id == activeId) next.first().id else activeId)
        preferences(id).edit().clear().apply()
    }

    private fun validate(name: String, excluding: String? = null): String {
        val value = name.trim().replace(Regex("\\s+"), " ")
        require(value.isNotBlank() && value.length <= 40 && value.none { it.isISOControl() }) { "Enter a name between 1 and 40 characters." }
        require(profiles.none { it.id != excluding && it.name.equals(value, true) }) { "That profile name is already in use." }
        return value
    }
    private fun save(next: List<UserProfile>, selected: String) {
        check(shared.edit().putString("profiles", encode(next)).putString("active_profile", selected).commit()) { "Could not save profiles." }
        profiles = next
        activeId = selected
    }
    private fun encode(values: List<UserProfile>) = JSONArray().apply {
        values.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.color)) }
    }.toString()
    private fun readProfiles(): List<UserProfile> = runCatching {
        val array = JSONArray(shared.getString("profiles", "[]"))
        (0 until array.length()).map { array.getJSONObject(it) }.mapNotNull {
            val id = it.optString("id")
            val name = it.optString("name").trim().take(40)
            if ((id == "default" || id.matches(Regex("[a-f0-9-]{36}"))) && name.isNotBlank()) UserProfile(id, name, it.optInt("color")) else null
        }.distinctBy { it.id }.take(8)
    }.getOrDefault(emptyList())
}
