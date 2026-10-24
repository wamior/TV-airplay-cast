package io.github.wamior.tvcast.mirror

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import java.lang.ref.WeakReference

/** Full-screen surface that shows the phone's mirrored screen. */
class MirrorActivity : Activity(), SurfaceHolder.Callback {
    companion object {
        private var current: WeakReference<MirrorActivity>? = null

        fun finishIfOpen() {
            current?.get()?.let { a -> a.runOnUiThread { a.finish() } }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val view = SurfaceView(this)
        view.holder.addCallback(this)
        setContentView(view)
        current = WeakReference(this)
    }

    override fun onDestroy() {
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
