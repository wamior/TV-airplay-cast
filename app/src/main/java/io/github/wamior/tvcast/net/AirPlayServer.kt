package io.github.wamior.tvcast.net

import android.content.Context
import android.util.Log
import com.dd.plist.NSArray
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSObject
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser
import io.github.wamior.tvcast.Config
import io.github.wamior.tvcast.PlaybackHub
import io.github.wamior.tvcast.PlaybackHub.State
import java.net.Socket
import java.util.Locale

private const val TAG = "TVCast.AirPlay"
private const val PLIST = "text/x-apple-plist+xml"

/** Features advertised over mDNS / server-info: video (bit 0) + photo (bit 1). No FairPlay, no mirroring yet. */
const val AIRPLAY_FEATURES = 0x3L

/**
 * Legacy (AirPlay 1) HTTP receiver for URL-based video playback: /play, /scrub, /rate, /stop, /playback-info.
 * Screen mirroring needs the FairPlay handshake and is not implemented.
 */
class AirPlayServer(private val ctx: Context) {
    private val deviceId = Config.deviceId(ctx)
    private var http: MiniHttpServer? = null
    private val reverseSockets = mutableListOf<Socket>()

    fun start() {
        http = MiniHttpServer(Config.AIRPLAY_PORT, "airplay", ::handle).also { it.start() }
    }

    fun stop() {
        runCatching { http?.stop() }
        synchronized(reverseSockets) {
            reverseSockets.forEach { runCatching { it.close() } }
            reverseSockets.clear()
        }
    }

    private fun handle(req: HttpRequest): HttpResponse {
        Log.d(TAG, "${req.method} ${req.target} type=${req.header("content-type")} len=${req.body.size}")
        return when (req.path) {
            "/server-info" -> plist(serverInfo())
            "/reverse" -> HttpResponse(
                101,
                mapOf("Upgrade" to "PTTH/1.0", "Connection" to "Upgrade"),
                onUpgrade = { s -> synchronized(reverseSockets) { reverseSockets += s } },
            )
            "/play" -> if (play(req)) HttpResponse() else HttpResponse(400)
            "/playback-info" -> plist(playbackInfo())
            "/rate" -> {
                if ((req.query["value"]?.toDoubleOrNull() ?: 1.0) == 0.0) PlaybackHub.pause() else PlaybackHub.play()
                HttpResponse()
            }
            "/scrub" -> if (req.method == "POST") {
                req.query["position"]?.toDoubleOrNull()?.let { PlaybackHub.seekTo((it * 1000).toLong()) }
                HttpResponse()
            } else {
                val s = PlaybackHub.snapshot
                HttpResponse.text(
                    String.format(Locale.US, "duration: %.3f\nposition: %.3f\n", s.durationMs / 1000.0, s.positionMs / 1000.0),
                    "text/parameters",
                )
            }
            "/stop" -> { PlaybackHub.stop(); HttpResponse() }
            "/setProperty", "/getProperty" -> plist(NSDictionary().apply { put("errorCode", 0L) })
            "/photo", "/volume" -> HttpResponse()
            else -> HttpResponse(404)
        }
    }

    private fun serverInfo() = NSDictionary().apply {
        put("deviceid", deviceId)
        put("features", AIRPLAY_FEATURES)
        put("macAddress", deviceId)
        put("model", "AppleTV3,2")
        put("protovers", "1.0")
        put("srcvers", "220.68")
    }

    private fun playbackInfo(): NSDictionary {
        val s = PlaybackHub.snapshot
        val d = NSDictionary()
        if (s.state == State.IDLE || s.state == State.STOPPED) {
            d.put("readyToPlay", false)
            return d
        }
        val dur = s.durationMs / 1000.0
        val range = NSDictionary().apply { put("start", 0.0); put("duration", dur) }
        d.put("duration", dur)
        d.put("position", s.positionMs / 1000.0)
        d.put("rate", if (s.state == State.PLAYING) 1.0 else 0.0)
        d.put("readyToPlay", s.state == State.PLAYING || s.state == State.PAUSED)
        d.put("playbackBufferEmpty", s.state == State.LOADING)
        d.put("playbackBufferFull", false)
        d.put("playbackLikelyToKeepUp", s.state != State.LOADING)
        d.put("loadedTimeRanges", NSArray(range))
        d.put("seekableTimeRanges", NSArray(range))
        return d
    }

    private fun play(req: HttpRequest): Boolean {
        var url: String? = null
        var fraction = 0.0
        val b = req.body
        val head = String(b, 0, minOf(b.size, 6), Charsets.ISO_8859_1)
        if (head == "bplist" || head.startsWith("<?xml")) {
            val d = PropertyListParser.parse(b) as? NSDictionary ?: return false
            url = (d.objectForKey("Content-Location") as? NSString)?.content
            fraction = (d.objectForKey("Start-Position") as? NSNumber)?.doubleValue() ?: 0.0
        } else {
            for (line in String(b, Charsets.UTF_8).lineSequence()) {
                val k = line.substringBefore(':').trim()
                val v = line.substringAfter(':', "").trim()
                when (k) {
                    "Content-Location" -> url = v
                    "Start-Position" -> fraction = v.toDoubleOrNull() ?: 0.0
                }
            }
        }
        Log.i(TAG, "play url=$url start=$fraction")
        val u = url ?: return false
        PlaybackHub.load(u, startFraction = fraction)
        return true
    }

    private fun plist(o: NSObject) = HttpResponse.text(o.toXMLPropertyList(), PLIST)
}
