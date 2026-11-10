package com.streamvault.tv.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.streamvault.tv.data.Channel
import com.streamvault.tv.data.Repo
import com.streamvault.tv.ui.StreamVaultTheme
import com.streamvault.tv.remote.PhoneRemote
import androidx.compose.runtime.key

class PlayerActivity : ComponentActivity() {
    private var controller: PlayerController? = null
    private var resumeOnStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(applicationContext)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        replacePlayback(Repo.queue, Repo.queueIndex, intent.getLongExtra(EXTRA_START, -1L))
    }

    /** Reuse this Activity, releasing the old decoder before opening a phone-selected stream. */
    fun replacePlayback(queue: List<Channel>, index: Int, startMs: Long) {
        if (queue.isEmpty()) { finish(); return }
        controller?.let { PhoneRemote.detach(it); it.release() }
        controller = null
        resumeOnStart = false
        Repo.queue = queue
        Repo.queueIndex = index
        val ctrl = PlayerController(this, lifecycleScope, queue, index)
        controller = ctrl
        PhoneRemote.attach(this, ctrl)
        ctrl.playIndex(index, startMs)
        setContent {
            StreamVaultTheme {
                key(ctrl) { PlayerScreen(ctrl, onExit = { finish() }) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (resumeOnStart) controller?.play()
        resumeOnStart = false
    }

    override fun onStop() {
        controller?.let {
            resumeOnStart = it.isPlaying
            it.pause()
            it.saveProgress()
        }
        super.onStop()
    }

    override fun onDestroy() {
        controller?.let { PhoneRemote.detach(it); it.release() }
        controller = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_START = "start_ms"

        fun start(context: Context, queue: List<Channel>, index: Int, startMs: Long) {
            Repo.queue = queue
            Repo.queueIndex = index
            val intent = Intent(context, PlayerActivity::class.java).putExtra(EXTRA_START, startMs)
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
