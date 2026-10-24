package io.github.wamior.tvcast

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.CopyOnWriteArraySet

private const val TAG = "TVCast.Hub"

/** Single playback sink shared by every protocol (DLNA, AirPlay, ...). Thread-safe facade over ExoPlayer. */
object PlaybackHub {
    enum class State { IDLE, LOADING, PLAYING, PAUSED, STOPPED }

    data class Snapshot(
        val state: State = State.IDLE,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val uri: String? = null,
    )

    @Volatile
    var snapshot = Snapshot()
        private set

    /** Called on the main thread when playback is torn down. */
    val stopListeners = CopyOnWriteArraySet<() -> Unit>()

    var player: ExoPlayer? = null
        private set

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private var currentUri: String? = null
    private var pendingFraction = 0.0
    private var pendingMs = 0L
    private var muted = false

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** Start playing [uri]. The start offset is either absolute ([startMs]) or a fraction of duration. */
    fun load(uri: String, startFraction: Double = 0.0, startMs: Long = 0) = runOnMain {
        val p = player ?: createPlayer()
        currentUri = uri
        pendingFraction = startFraction
        pendingMs = startMs
        p.setMediaItem(MediaItem.fromUri(uri))
        p.prepare()
        p.playWhenReady = true
        refresh()
        launchPlayerUi()
        main.removeCallbacks(tick)
        main.postDelayed(tick, 500)
    }

    fun play() = runOnMain { player?.playWhenReady = true; refresh() }

    fun pause() = runOnMain { player?.playWhenReady = false; refresh() }

    fun seekTo(ms: Long) = runOnMain { player?.seekTo(ms.coerceAtLeast(0)); refresh() }

    fun seekBy(deltaMs: Long) = runOnMain {
        player?.let { it.seekTo((it.currentPosition + deltaMs).coerceAtLeast(0)) }
        refresh()
    }

    fun togglePause() = runOnMain {
        player?.let { it.playWhenReady = !it.playWhenReady }
        refresh()
    }

    fun stop() = runOnMain {
        main.removeCallbacks(tick)
        player?.release()
        player = null
        snapshot = Snapshot(State.STOPPED, 0, 0, currentUri)
        stopListeners.forEach { it() }
    }

    fun setMuted(value: Boolean) = runOnMain {
        muted = value
        player?.volume = if (muted) 0f else 1f
    }

    fun isMuted() = muted

    fun getVolumePercent(): Int {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    fun setVolumePercent(percent: Int) {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, percent.coerceIn(0, 100) * max / 100, 0)
        } catch (e: SecurityException) {
            Log.w(TAG, "volume change refused", e)
        }
    }

    private fun createPlayer(): ExoPlayer {
        val p = ExoPlayer.Builder(app).build()
        p.addListener(listener)
        p.volume = if (muted) 0f else 1f
        player = p
        return p
    }

    private fun launchPlayerUi() {
        try {
            app.startActivity(
                Intent(app, PlayerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (e: Exception) {
            Log.w(TAG, "cannot start player UI (overlay permission missing?)", e)
        }
    }

    private fun applyPendingStart(p: ExoPlayer) {
        val dur = p.duration
        val target = when {
            pendingMs > 0 -> pendingMs
            pendingFraction > 0 && dur != C.TIME_UNSET -> (pendingFraction * dur).toLong()
            else -> 0L
        }
        pendingMs = 0
        pendingFraction = 0.0
        if (target > 0) p.seekTo(target)
    }

    private fun refresh() {
        val p = player ?: return
        val state = when (p.playbackState) {
            Player.STATE_BUFFERING -> State.LOADING
            Player.STATE_READY -> if (p.playWhenReady) State.PLAYING else State.PAUSED
            Player.STATE_ENDED -> State.STOPPED
            else -> State.LOADING
        }
        snapshot = Snapshot(
            state = state,
            positionMs = p.currentPosition.coerceAtLeast(0),
            durationMs = p.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0,
            uri = currentUri,
        )
    }

    private val tick = object : Runnable {
        override fun run() {
            if (player == null) return
            refresh()
            main.postDelayed(this, 500)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val p = player ?: return
            if (playbackState == Player.STATE_READY) applyPendingStart(p)
            if (playbackState == Player.STATE_ENDED) {
                main.post { stop() }
                return
            }
            refresh()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = refresh()

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "playback error", error)
            main.post { stop() }
        }
    }
}
