package io.github.wamior.tvcast

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var grant: Button
    private lateinit var mode: Button

    private val refresh = object : Runnable {
        override fun run() {
            status.text = "Address: ${CastStatus.ip()}\n\n" +
                "AirPlay: ${CastStatus.airplay}\n" +
                "DLNA / UPnP: ${CastStatus.dlna}\n" +
                "Discovery (mDNS): ${CastStatus.discovery}\n\n" +
                "Google Cast: not implemented\n\n" + io.github.wamior.tvcast.mirror.MirrorHub.summary()
            mode.text = if (Config.mode(this@MainActivity) == "mirror") {
                "AirPlay mode: screen mirroring (press to switch to video URL)"
            } else {
                "AirPlay mode: video URL (press to switch to screen mirroring)"
            }
            grant.visibility = if (Settings.canDrawOverlays(this@MainActivity) || !canOpenOverlaySettings()) {
                View.GONE
            } else {
                View.VISIBLE
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<TextView>(R.id.title).text = Config.deviceName(this)
        status = findViewById(R.id.status)
        grant = findViewById(R.id.grant)
        mode = findViewById(R.id.mode)
        grant.setOnClickListener { startActivity(overlaySettingsIntent()) }
        mode.setOnClickListener {
            Config.setMode(this, if (Config.mode(this) == "mirror") "video" else "mirror")
            CastStatus.airplay = "restarting"
            stopService(Intent(this, CastService::class.java))
            // Ports and mDNS records are released asynchronously; give them a moment before starting again.
            handler.postDelayed({ startReceiver() }, 1500)
        }
        PlaybackHub.init(this)
        startReceiver()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun startReceiver() {
        ContextCompat.startForegroundService(this, Intent(this, CastService::class.java))
    }

    private fun overlaySettingsIntent() =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))

    private fun canOpenOverlaySettings() =
        overlaySettingsIntent().resolveActivity(packageManager) != null
}
