package io.github.wamior.tvcast.net

import android.content.Context
import android.util.Log
import io.github.wamior.tvcast.Config
import io.github.wamior.tvcast.PlaybackHub
import io.github.wamior.tvcast.PlaybackHub.State
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

private const val TAG = "TVCast.Dlna"
private const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"

private class UpnpError(val code: Int, val description: String) : Exception(description)

private const val SINK_PROTOCOLS = "http-get:*:video/mp4:*,http-get:*:video/mpeg:*,http-get:*:video/x-matroska:*," +
    "http-get:*:video/x-msvideo:*,http-get:*:video/webm:*,http-get:*:video/quicktime:*," +
    "http-get:*:audio/mpeg:*,http-get:*:audio/mp4:*,http-get:*:audio/flac:*,http-get:*:audio/x-wav:*," +
    "http-get:*:application/vnd.apple.mpegurl:*,http-get:*:application/x-mpegURL:*,http-get:*:image/jpeg:*"

/** UPnP/DLNA MediaRenderer (AVTransport + RenderingControl + ConnectionManager) driving [PlaybackHub]. */
class DlnaRenderer(private val ctx: Context) {
    private val uuid = Config.uuid(ctx)
    private val name = Config.deviceName(ctx)
    private val services = listOf(UpnpServices.avTransport, UpnpServices.renderingControl, UpnpServices.connectionManager)

    private var http: MiniHttpServer? = null
    private var ssdp: SsdpServer? = null

    @Volatile private var uri: String? = null
    @Volatile private var meta: String = ""
    @Volatile private var loadedUri: String? = null

    fun start() {
        http = MiniHttpServer(Config.DLNA_PORT, "dlna", ::handle).also { it.start() }
        ssdp = SsdpServer(
            uuid,
            { NetUtil.localIpv4()?.let { "http://$it:${Config.DLNA_PORT}/desc.xml" } },
            DEVICE_TYPE,
            services.map { it.type },
        ).also { it.start() }
    }

    fun stop() {
        runCatching { ssdp?.stop() }
        runCatching { http?.stop() }
    }

    private fun handle(req: HttpRequest): HttpResponse {
        val path = req.path
        if (path == "/desc.xml") return HttpResponse.xml(descriptionXml())
        for (svc in services) {
            when (path) {
                svc.scpdUrl -> return HttpResponse.xml(svc.scpdXml())
                svc.controlUrl -> return if (req.method == "POST") control(svc, req) else HttpResponse(405)
                svc.eventUrl -> return when (req.method) {
                    "SUBSCRIBE" -> HttpResponse(
                        200,
                        mapOf("SID" to "uuid:${UUID.randomUUID()}", "TIMEOUT" to "Second-1800"),
                    )
                    "UNSUBSCRIBE" -> HttpResponse(200)
                    else -> HttpResponse(405)
                }
            }
        }
        return HttpResponse(404)
    }

    private fun descriptionXml(): String = buildString {
        append("<?xml version=\"1.0\"?>\n")
        append("<root xmlns=\"urn:schemas-upnp-org:device-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion><device>")
        append("<deviceType>$DEVICE_TYPE</deviceType>")
        append("<friendlyName>${esc(name)}</friendlyName>")
        append("<manufacturer>wamior</manufacturer><modelName>TV AirPlay Cast</modelName><modelNumber>1</modelNumber>")
        append("<UDN>uuid:$uuid</UDN><serviceList>")
        for (s in services) {
            append("<service><serviceType>${s.type}</serviceType><serviceId>${s.id}</serviceId>")
            append("<SCPDURL>${s.scpdUrl}</SCPDURL><controlURL>${s.controlUrl}</controlURL><eventSubURL>${s.eventUrl}</eventSubURL></service>")
        }
        append("</serviceList></device></root>")
    }

    private fun control(svc: ServiceDef, req: HttpRequest): HttpResponse {
        val action = req.header("soapaction")?.trim('"')?.substringAfter('#')
            ?: return HttpResponse(400)
        return try {
            val args = parseSoapArgs(req.body)
            Log.d(TAG, "${svc.shortName}.$action $args")
            val out = when (svc.shortName) {
                "AVTransport" -> avTransport(action, args)
                "RenderingControl" -> renderingControl(action, args)
                else -> connectionManager(action)
            }
            HttpResponse.xml(soapResponse(svc.type, action, out))
        } catch (e: UpnpError) {
            HttpResponse.xml(soapFault(e.code, e.description), 500)
        } catch (e: Exception) {
            Log.w(TAG, "control $action failed", e)
            HttpResponse.xml(soapFault(501, "Action Failed"), 500)
        }
    }

    private fun avTransport(action: String, a: Map<String, String>): Map<String, String> {
        val snap = PlaybackHub.snapshot
        return when (action) {
            "SetAVTransportURI" -> {
                uri = a["CurrentURI"]
                meta = a["CurrentURIMetaData"] ?: ""
                emptyMap()
            }
            "Play" -> {
                val u = uri ?: throw UpnpError(701, "Transition not available")
                if (u != loadedUri || snap.state == State.IDLE || snap.state == State.STOPPED) {
                    loadedUri = u
                    PlaybackHub.load(u)
                } else {
                    PlaybackHub.play()
                }
                emptyMap()
            }
            "Pause" -> { PlaybackHub.pause(); emptyMap() }
            "Stop" -> { PlaybackHub.stop(); loadedUri = null; emptyMap() }
            "Seek" -> {
                val target = a["Target"] ?: ""
                if (a["Unit"] == "REL_TIME" || a["Unit"] == "ABS_TIME") PlaybackHub.seekTo(parseTime(target))
                emptyMap()
            }
            "Next", "Previous" -> emptyMap()
            "GetTransportInfo" -> mapOf(
                "CurrentTransportState" to when (snap.state) {
                    State.PLAYING -> "PLAYING"
                    State.PAUSED -> "PAUSED_PLAYBACK"
                    State.LOADING -> "TRANSITIONING"
                    State.STOPPED -> "STOPPED"
                    State.IDLE -> if (uri == null) "NO_MEDIA_PRESENT" else "STOPPED"
                },
                "CurrentTransportStatus" to "OK",
                "CurrentSpeed" to "1",
            )
            "GetPositionInfo" -> mapOf(
                "Track" to "1",
                "TrackDuration" to formatTime(snap.durationMs),
                "TrackMetaData" to meta,
                "TrackURI" to (uri ?: ""),
                "RelTime" to formatTime(snap.positionMs),
                "AbsTime" to formatTime(snap.positionMs),
                "RelCount" to "2147483647",
                "AbsCount" to "2147483647",
            )
            "GetMediaInfo" -> mapOf(
                "NrTracks" to if (uri == null) "0" else "1",
                "MediaDuration" to formatTime(snap.durationMs),
                "CurrentURI" to (uri ?: ""),
                "CurrentURIMetaData" to meta,
                "NextURI" to "NOT_IMPLEMENTED",
                "NextURIMetaData" to "NOT_IMPLEMENTED",
                "PlayMedium" to "NETWORK",
                "RecordMedium" to "NOT_IMPLEMENTED",
                "WriteStatus" to "NOT_IMPLEMENTED",
            )
            "GetDeviceCapabilities" -> mapOf(
                "PlayMedia" to "NETWORK",
                "RecMedia" to "NOT_IMPLEMENTED",
                "RecQualityModes" to "NOT_IMPLEMENTED",
            )
            "GetTransportSettings" -> mapOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED")
            "GetCurrentTransportActions" -> mapOf("Actions" to "Play,Pause,Stop,Seek")
            else -> throw UpnpError(401, "Invalid Action")
        }
    }

    private fun renderingControl(action: String, a: Map<String, String>): Map<String, String> = when (action) {
        "GetVolume" -> mapOf("CurrentVolume" to PlaybackHub.getVolumePercent().toString())
        "SetVolume" -> {
            PlaybackHub.setVolumePercent(a["DesiredVolume"]?.toIntOrNull() ?: 50)
            emptyMap()
        }
        "GetMute" -> mapOf("CurrentMute" to if (PlaybackHub.isMuted()) "1" else "0")
        "SetMute" -> {
            PlaybackHub.setMuted(a["DesiredMute"] == "1" || a["DesiredMute"].equals("true", ignoreCase = true))
            emptyMap()
        }
        else -> throw UpnpError(401, "Invalid Action")
    }

    private fun connectionManager(action: String): Map<String, String> = when (action) {
        "GetProtocolInfo" -> mapOf("Source" to "", "Sink" to SINK_PROTOCOLS)
        "GetCurrentConnectionIDs" -> mapOf("ConnectionIDs" to "0")
        "GetCurrentConnectionInfo" -> mapOf(
            "RcsID" to "0", "AVTransportID" to "0", "ProtocolInfo" to "",
            "PeerConnectionManager" to "", "PeerConnectionID" to "-1",
            "Direction" to "Input", "Status" to "OK",
        )
        else -> throw UpnpError(401, "Invalid Action")
    }

    private fun parseSoapArgs(body: ByteArray): Map<String, String> {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        val doc = f.newDocumentBuilder().parse(ByteArrayInputStream(body))
        val bodyEl = doc.getElementsByTagNameNS("*", "Body").item(0) ?: return emptyMap()
        var action: Node? = bodyEl.firstChild
        while (action != null && action.nodeType != Node.ELEMENT_NODE) action = action.nextSibling
        val out = HashMap<String, String>()
        var c = action?.firstChild
        while (c != null) {
            if (c.nodeType == Node.ELEMENT_NODE) out[c.localName ?: c.nodeName] = c.textContent ?: ""
            c = c.nextSibling
        }
        return out
    }

    private fun soapResponse(serviceType: String, action: String, out: Map<String, String>): String = buildString {
        append("<?xml version=\"1.0\"?>\n")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
        append("<u:${action}Response xmlns:u=\"$serviceType\">")
        out.forEach { (k, v) -> append("<$k>${esc(v)}</$k>") }
        append("</u:${action}Response></s:Body></s:Envelope>")
    }

    private fun soapFault(code: Int, description: String): String =
        "<?xml version=\"1.0\"?>\n" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>" +
            "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>$code</errorCode>" +
            "<errorDescription>${esc(description)}</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun formatTime(ms: Long): String {
        val total = ms / 1000
        return String.format(Locale.US, "%d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60)
    }

    private fun parseTime(s: String): Long {
        val parts = s.split(':')
        if (parts.size != 3) return 0
        val h = parts[0].toLongOrNull() ?: 0
        val m = parts[1].toLongOrNull() ?: 0
        val sec = parts[2].toDoubleOrNull() ?: 0.0
        return ((h * 3600 + m * 60) * 1000 + sec * 1000).toLong()
    }
}
