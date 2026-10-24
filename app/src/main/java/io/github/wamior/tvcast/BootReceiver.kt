package io.github.wamior.tvcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, CastService::class.java))
        } catch (e: Exception) {
            Log.w("TVCast", "cannot start service on boot", e)
        }
    }
}
