package io.github.wamior.tvcast

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.wamior.tvcast.net.AIRPLAY_FEATURES
import io.github.wamior.tvcast.net.AirPlayServer
import io.github.wamior.tvcast.net.DlnaRenderer
import io.github.wamior.tvcast.net.NetUtil

private const val TAG = "TVCast.Service"
private const val CHANNEL = "tvcast"

/** What the main screen shows. */
object CastStatus {
    @Volatile var dlna = "starting"
    @Volatile var airplay = "starting"
    @Volatile var discovery = "starting"
    fun ip(): String = NetUtil.localIpv4() ?: "no network"
}

class CastService : Service() {
    private var dlna: DlnaRenderer? = null
    private var airplay: AirPlayServer? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val nsdListeners = mutableListOf<NsdManager.RegistrationListener>()
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (!started) {
            started = true
            PlaybackHub.init(this)
            Thread { startAll() }.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        started = false
        runCatching { dlna?.stop() }
        runCatching { airplay?.stop() }
        val nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        nsdListeners.forEach { runCatching { nsd.unregisterService(it) } }
        nsdListeners.clear()
        runCatching { multicastLock?.release() }
        runCatching { wifiLock?.release() }
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Receiver", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Waiting for casts from your phone")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, n)
        }
    }

    private fun startAll() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("tvcast").apply { setReferenceCounted(false); acquire() }
        @Suppress("DEPRECATION")
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tvcast").apply {
            setReferenceCounted(false); acquire()
        }

        CastStatus.dlna = runCatching { DlnaRenderer(this).also { it.start(); dlna = it } }
            .fold({ "listening" }, { Log.e(TAG, "dlna", it); "error: ${it.message}" })
        CastStatus.airplay = runCatching { AirPlayServer(this).also { it.start(); airplay = it } }
            .fold({ "listening" }, { Log.e(TAG, "airplay", it); "error: ${it.message}" })
        CastStatus.discovery = runCatching { registerMdns() }
            .fold({ "advertising" }, { Log.e(TAG, "mdns", it); "error: ${it.message}" })
    }

    private fun registerMdns() {
        val nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        val info = NsdServiceInfo().apply {
            serviceName = Config.deviceName(this@CastService)
            serviceType = "_airplay._tcp"
            port = Config.AIRPLAY_PORT
            setAttribute("deviceid", Config.deviceId(this@CastService))
            setAttribute("features", "0x${AIRPLAY_FEATURES.toString(16)}")
            setAttribute("model", "AppleTV3,2")
            setAttribute("srcvers", "220.68")
            setAttribute("flags", "0x4")
            setAttribute("vv", "2")
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {
                Log.i(TAG, "mDNS registered ${i.serviceName}")
            }

            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {
                Log.e(TAG, "mDNS failed $code")
            }

            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        nsdListeners += l
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }
}
