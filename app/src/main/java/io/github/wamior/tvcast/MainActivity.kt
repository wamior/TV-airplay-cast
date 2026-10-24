package io.github.wamior.tvcast

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var grant: Button

    private val refresh = object : Runnable {
        override fun run() {
            status.text = "Address: ${CastStatus.ip()}\n\n" +
                "AirPlay (video): ${CastStatus.airplay}\n" +
                "DLNA / UPnP: ${CastStatus.dlna}\n" +
                "Discovery (mDNS): ${CastStatus.discovery}\n\n" +
                "AirPlay mirroring and Google Cast: not implemented yet"
            grant.visibility = if (Settings.canDrawOverlays(this@MainActivity) || !canOpenOverlaySettings()) {
                android.view.View.GONE
            } else {
                android.view.View.VISIBLE
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
        grant.setOnClickListener {
            startActivity(overlaySettingsIntent())
        }
        PlaybackHub.init(this)
        ContextCompat.startForegroundService(this, Intent(this, CastService::class.java))
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun overlaySettingsIntent() =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))

    private fun canOpenOverlaySettings() =
        overlaySettingsIntent().resolveActivity(packageManager) != null
}
