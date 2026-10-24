package io.github.wamior.tvcast

import android.content.Context
import android.os.Build
import java.util.UUID

object Config {
    const val AIRPLAY_PORT = 7000
    const val DLNA_PORT = 49152

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tvcast", Context.MODE_PRIVATE)

    fun deviceName(ctx: Context): String =
        prefs(ctx).getString("name", null) ?: "TV Cast (${Build.MODEL})"

    /** "mirror" = AirPlay screen mirroring (phones show "Screen Mirroring"); "video" = legacy URL video hand-off. */
    fun mode(ctx: Context): String = prefs(ctx).getString("mode", null) ?: "mirror"

    fun setMode(ctx: Context, mode: String) {
        prefs(ctx).edit().putString("mode", mode).apply()
    }

    fun deviceIdBytes(ctx: Context): ByteArray =
        deviceId(ctx).split(':').map { it.toInt(16).toByte() }.toByteArray()

    fun uuid(ctx: Context): String {
        val p = prefs(ctx)
        return p.getString("uuid", null) ?: UUID.randomUUID().toString().also {
            p.edit().putString("uuid", it).apply()
        }
    }

    /** MAC-style identifier derived from the persistent UUID; AirPlay wants one. */
    fun deviceId(ctx: Context): String =
        uuid(ctx).replace("-", "").take(12).uppercase().chunked(2).joinToString(":")
}
