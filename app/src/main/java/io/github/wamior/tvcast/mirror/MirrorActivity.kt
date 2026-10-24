package io.github.wamior.tvcast.mirror

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import java.lang.ref.WeakReference

/** Full-screen surface that shows the phone's mirrored screen, letterboxed to the stream's aspect ratio. */
class MirrorActivity : Activity(), SurfaceHolder.Callback {
    companion object {
        private var current: WeakReference<MirrorActivity>? = null

        fun finishIfOpen() {
            current?.get()?.let { a -> a.runOnUiThread { a.finish() } }
        }
    }

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER),
        )
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitToStream() }
        setContentView(root)
        MirrorHub.onDisplaySize = { _, _ -> runOnUiThread { fitToStream() } }
        current = WeakReference(this)
    }

    override fun onDestroy() {
        MirrorHub.onDisplaySize = null
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    /** The SurfaceView stretches the video to its own size, so give it exactly the stream's aspect ratio. */
    private fun fitToStream() {
        val w = MirrorHub.displayW
        val h = MirrorHub.displayH
        val rw = root.width
        val rh = root.height
        if (w <= 0 || h <= 0 || rw == 0 || rh == 0) return
        val scale = minOf(rw.toFloat() / w, rh.toFloat() / h)
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        val nw = (w * scale).toInt()
        val nh = (h * scale).toInt()
        if (lp.width != nw || lp.height != nh) {
            lp.width = nw
            lp.height = nh
            lp.gravity = Gravity.CENTER
            surfaceView.layoutParams = lp
        }
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
