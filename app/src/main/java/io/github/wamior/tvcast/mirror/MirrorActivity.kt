package io.github.wamior.tvcast.mirror

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.widget.TextView
import java.lang.ref.WeakReference

/** Full-screen surface that shows the phone's mirrored screen. */
class MirrorActivity : Activity(), SurfaceHolder.Callback {
    companion object {
        private var current: WeakReference<MirrorActivity>? = null

        fun finishIfOpen() {
            current?.get()?.let { a -> a.runOnUiThread { a.finish() } }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val overlay by lazy {
        TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x88000000.toInt())
            textSize = 14f
            setPadding(16, 8, 16, 8)
        }
    }
    private val started = System.currentTimeMillis()
    private val tick = object : Runnable {
        override fun run() {
            // Diagnostics for the first 20 seconds of a session.
            if (System.currentTimeMillis() - started < 20_000) {
                overlay.text = MirrorHub.summary()
                handler.postDelayed(this, 500)
            } else {
                overlay.visibility = android.view.View.GONE
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val view = SurfaceView(this)
        view.holder.addCallback(this)
        val root = FrameLayout(this)
        root.addView(view)
        root.addView(overlay)
        setContentView(root)
        handler.post(tick)
        current = WeakReference(this)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    override fun surfaceCreated(holder: SurfaceHolder) = MirrorHub.attachSurface(holder.surface)

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) = MirrorHub.detachSurface()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            MirrorHub.userClosed()
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
