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

class PlayerActivity : ComponentActivity() {
    private var controller: PlayerController? = null
    private var resumeOnStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(applicationContext)
        val queue = Repo.queue
        if (queue.isEmpty()) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val ctrl = PlayerController(this, lifecycleScope, queue, Repo.queueIndex)
        controller = ctrl
        ctrl.playIndex(Repo.queueIndex, intent.getLongExtra(EXTRA_START, -1L))
        setContent {
            StreamVaultTheme {
                PlayerScreen(ctrl, onExit = { finish() })
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
        controller?.release()
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
