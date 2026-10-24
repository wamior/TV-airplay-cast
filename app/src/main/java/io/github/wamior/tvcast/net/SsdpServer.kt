package io.github.wamior.tvcast.net

import android.os.Build
import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random

private const val TAG = "TVCast.Ssdp"
private const val GROUP = "239.255.255.250"
private const val PORT = 1900

/** SSDP responder/announcer for a UPnP root device. */
class SsdpServer(
    private val uuid: String,
    private val location: () -> String?,
    deviceType: String,
    serviceTypes: List<String>,
) {
    /** (NT/ST, USN) pairs. */
    private val targets: List<Pair<String, String>> = buildList {
        add("upnp:rootdevice" to "uuid:$uuid::upnp:rootdevice")
        add("uuid:$uuid" to "uuid:$uuid")
        add(deviceType to "uuid:$uuid::$deviceType")
        serviceTypes.forEach { add(it to "uuid:$uuid::$it") }
    }

    private val serverHeader = "Android/${Build.VERSION.RELEASE} UPnP/1.0 TVCast/0.1"
    private val group = InetAddress.getByName(GROUP)
    private val scheduler = Executors.newScheduledThreadPool(2)
    private var sock: MulticastSocket? = null

    fun start() {
        val s = MulticastSocket(null as SocketAddress?)
        s.reuseAddress = true
        s.bind(InetSocketAddress(PORT))
        s.timeToLive = 4
        val itf = NetUtil.localInterface()
        if (itf != null) {
            runCatching { s.networkInterface = itf }
            runCatching { s.joinGroup(InetSocketAddress(group, PORT), itf) }
                .onFailure { @Suppress("DEPRECATION") s.joinGroup(group) }
        } else {
            @Suppress("DEPRECATION") s.joinGroup(group)
        }
        sock = s

        thread(name = "ssdp-rx", isDaemon = true) {
            val buf = ByteArray(4096)
            while (!s.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try {
                    s.receive(p)
                } catch (e: IOException) {
                    break
                }
                val text = String(p.data, 0, p.length, Charsets.UTF_8)
                if (text.startsWith("M-SEARCH")) onSearch(text, p.address, p.port)
            }
        }
        scheduler.scheduleAtFixedRate({ notifyAll("ssdp:alive") }, 0, 300, TimeUnit.SECONDS)
        scheduler.schedule({ notifyAll("ssdp:alive") }, 2, TimeUnit.SECONDS)
        Log.i(TAG, "SSDP started")
    }

    fun stop() {
        runCatching { notifyAll("ssdp:byebye") }
        scheduler.shutdownNow()
        runCatching { sock?.close() }
    }

    private fun onSearch(text: String, from: InetAddress, port: Int) {
        val st = text.lineSequence().drop(1)
            .firstOrNull { it.startsWith("ST:", ignoreCase = true) }
            ?.substringAfter(':')?.trim() ?: return
        val loc = location() ?: return
        val matches = if (st == "ssdp:all") targets else targets.filter { it.first == st }
        for ((t, usn) in matches) {
            val msg = "HTTP/1.1 200 OK\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "EXT:\r\n" +
                "LOCATION: $loc\r\n" +
                "SERVER: $serverHeader\r\n" +
                "ST: $t\r\n" +
                "USN: $usn\r\n\r\n"
            runCatching {
                scheduler.schedule({ send(msg, from, port) }, Random.nextLong(0, 300), TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun notifyAll(nts: String) {
        val loc = location() ?: return
        for ((t, usn) in targets) {
            val msg = "NOTIFY * HTTP/1.1\r\n" +
                "HOST: $GROUP:$PORT\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "LOCATION: $loc\r\n" +
                "NT: $t\r\n" +
                "NTS: $nts\r\n" +
                "SERVER: $serverHeader\r\n" +
                "USN: $usn\r\n\r\n"
            send(msg, group, PORT)
        }
    }

    private fun send(msg: String, to: InetAddress, port: Int) {
        val bytes = msg.toByteArray(Charsets.UTF_8)
        try {
            sock?.send(DatagramPacket(bytes, bytes.size, to, port))
        } catch (e: IOException) {
            Log.w(TAG, "send failed: ${e.message}")
        }
    }
}
