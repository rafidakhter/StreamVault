package com.streamvault.tv.remote

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.streamvault.tv.data.Channel
import com.streamvault.tv.data.Repo
import com.streamvault.tv.player.PlayerActivity
import com.streamvault.tv.player.PlayerController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** The local companion is opt-in, foreground-only, and owns no player or catalog copies. */
object PhoneRemote {
    data class State(val address: String? = null, val phones: Int = 0, val error: String? = null)
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private var initialized = false
    private var enabled = false
    private var started = 0
    private var foreground = WeakReference<Activity>(null)
    private var player = WeakReference<PlayerController>(null)
    private var playerActivity = WeakReference<PlayerActivity>(null)
    private var server: PhoneRemoteServer? = null
    @Volatile private var generation = 0L
    private lateinit var application: Application
    private val stopWhenHidden = Runnable { if (started == 0) stopServer() }

    fun initialize(app: Application) {
        if (initialized) return
        initialized = true
        application = app
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
                foreground = WeakReference(activity)
                main.removeCallbacks(stopWhenHidden)
                if (enabled && server == null) startServer()
            }
            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (foreground.get() === activity) foreground.clear()
                if (started == 0) main.postDelayed(stopWhenHidden, 2_000)
            }
            override fun onActivityResumed(activity: Activity) { foreground = WeakReference(activity) }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    fun enable(context: Context) {
        if (!initialized) initialize(context.applicationContext as Application)
        enabled = true
        if (context is Activity) foreground = WeakReference(context)
        if (server == null) startServer()
    }

    fun disable() { enabled = false; stopServer() }
    fun refreshCode() { server?.refreshPairing() }
    fun disconnectPhones() { server?.revoke() }

    private fun startServer() {
        val ownGeneration = ++generation
        try {
            server = PhoneRemoteServer(application) { address, count ->
                if (generation == ownGeneration) mutableState.value = State(address, count)
            }.also { it.start() }
        } catch (_: Exception) {
            server?.stop()
            server = null
            mutableState.value = State(error = "Unable to start phone remote. Check the TV's network and try again.")
        }
    }
    private fun stopServer() {
        generation++
        server?.stop()
        server = null
        mutableState.value = State()
    }

    fun attach(activity: PlayerActivity, controller: PlayerController) {
        playerActivity = WeakReference(activity)
        player = WeakReference(controller)
    }
    fun detach(controller: PlayerController) {
        if (player.get() === controller) { player.clear(); playerActivity.clear() }
    }

    /** All player and Activity operations run on the UI thread, never on HTTP workers. */
    private fun <T> onMain(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val task = FutureTask(action)
        main.post(task)
        return try { task.get(4, TimeUnit.SECONDS) } catch (error: Exception) {
            task.cancel(false)
            throw IllegalStateException("TV is busy. Try again.", error)
        }
    }

    fun startPlayback(queue: List<Channel>, index: Int, position: Long, profileId: String, authorized: () -> Boolean) = onMain {
        check(Repo.activeProfile.value.id == profileId && authorized()) { "Profile changed. Pair again." }
        val activity = foreground.get()?.takeUnless { it.isFinishing || it.isDestroyed }
            ?: throw IllegalStateException("Open StreamVault on the TV to start playback.")
        val existing = playerActivity.get()?.takeUnless { it.isFinishing || it.isDestroyed }
        if (existing != null) existing.replacePlayback(queue, index, position)
        else PlayerActivity.start(activity, queue, index, position)
        JSONObject().put("ok", true)
    }

    fun setItemInList(id: String, relatedIds: List<String>, saved: Boolean, profileId: String, authorized: () -> Boolean): JSONObject = onMain {
        check(Repo.activeProfile.value.id == profileId && authorized()) { "Profile changed. Pair again." }
        val changed = if (saved) Repo.addFavorite(id, relatedIds, profileId) else Repo.removeFavorites(relatedIds, profileId)
        JSONObject().put("ok", true).put("inMyList", relatedIds.any { Repo.isFavorite(it) } || Repo.isFavorite(id)).put(if (saved) "added" else "removed", changed)
    }

    fun playlistRequest(profileId: String, authorized: () -> Boolean, action: () -> JSONObject): JSONObject = onMain {
        check(Repo.activeProfile.value.id == profileId && authorized()) { "Profile changed. Pair again." }
        runCatching(action)
    }.getOrThrow()

    fun status(profileId: String): JSONObject = onMain {
        check(Repo.activeProfile.value.id == profileId) { "Profile changed. Pair again." }
        val profile = Repo.activeProfile.value
        val result = JSONObject().put("profile", JSONObject().put("name", profile.name).put("initials", profile.initials))
        val ctrl = player.get()?.takeIf { it.profileId == profileId } ?: return@onMain result.put("active", false)
        val active = ctrl.error == null && !ctrl.ended && ctrl.player.playbackState in listOf(
            androidx.media3.common.Player.STATE_READY, androidx.media3.common.Player.STATE_BUFFERING)
        if (!active) return@onMain result.put("active", false)
        result.put("active", true).put("title", com.streamvault.tv.data.LibraryOrganization.clean(ctrl.current.name))
            .put("playing", ctrl.isPlaying).put("buffering", ctrl.isBuffering).put("live", ctrl.isLive)
            .put("position", ctrl.positionMs).put("duration", ctrl.durationMs)
            .put("seekable", ctrl.player.isCurrentMediaItemSeekable)
            .put("hasNext", ctrl.hasNext).put("hasPrevious", ctrl.hasPrevious)
            .put("error", if (ctrl.error == null) JSONObject.NULL else "Playback needs attention on the TV.")
    }

    fun command(name: String, position: Long, profileId: String, authorized: () -> Boolean): JSONObject = onMain {
        check(Repo.activeProfile.value.id == profileId && authorized()) { "Profile changed. Pair again." }
        val ctrl = player.get()?.takeIf { it.profileId == profileId } ?: throw IllegalStateException("Nothing is playing on the TV.")
        when (name) {
            "play" -> ctrl.play()
            "pause" -> ctrl.pause()
            "next" -> if (ctrl.hasNext) ctrl.next()
            "previous" -> if (ctrl.hasPrevious) ctrl.previous()
            "seek" -> {
                require(ctrl.player.isCurrentMediaItemSeekable) { "This stream cannot be seeked." }
                ctrl.seekTo(position.coerceIn(0, ctrl.durationMs.coerceAtLeast(0)))
            }
            "stop" -> playerActivity.get()?.finish()
            else -> throw IllegalArgumentException("Unknown playback command.")
        }
        JSONObject().put("ok", true)
    }
}
