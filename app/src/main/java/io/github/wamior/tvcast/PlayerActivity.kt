package io.github.wamior.tvcast

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.media3.ui.PlayerView

class PlayerActivity : Activity() {
    private lateinit var view: PlayerView
    private val onStopped: () -> Unit = { runOnUiThread { finish() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        view = findViewById(R.id.player_view)
        PlaybackHub.stopListeners += onStopped
    }

    override fun onStart() {
        super.onStart()
        val p = PlaybackHub.player
        if (p == null) finish() else view.player = p
    }

    override fun onStop() {
        view.player = null
        PlaybackHub.stop()
        super.onStop()
    }

    override fun onDestroy() {
        PlaybackHub.stopListeners -= onStopped
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
            PlaybackHub.togglePause(); true
        }
        KeyEvent.KEYCODE_MEDIA_PLAY -> { PlaybackHub.play(); true }
        KeyEvent.KEYCODE_MEDIA_PAUSE -> { PlaybackHub.pause(); true }
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { PlaybackHub.seekBy(10_000); true }
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { PlaybackHub.seekBy(-10_000); true }
        KeyEvent.KEYCODE_BACK -> { PlaybackHub.stop(); finish(); true }
        else -> super.onKeyDown(keyCode, event)
    }
}
