package io.github.wamior.tvcast.net

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

private const val TAG = "TVCast.Http"
private const val MAX_BODY = 32 * 1024 * 1024

class HttpRequest(
    val method: String,
    val target: String,
    val headers: Map<String, String>,
    val body: ByteArray,
    val remote: String,
) {
    val path: String get() = target.substringBefore('?')

    val query: Map<String, String> by lazy {
        target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
    }

    fun header(name: String): String? = headers[name.lowercase()]
}

/** [onUpgrade] hands the raw socket to the caller (e.g. AirPlay /reverse); the server then stops touching it. */
class HttpResponse(
    val status: Int = 200,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
    val onUpgrade: ((Socket) -> Unit)? = null,
) {
    companion object {
        fun text(body: String, contentType: String, status: Int = 200) =
            HttpResponse(status, mapOf("Content-Type" to contentType), body.toByteArray(Charsets.UTF_8))

        fun xml(body: String, status: Int = 200) = text(body, "text/xml; charset=\"utf-8\"", status)
    }
}

/** Small thread-per-connection HTTP/1.1 server; handles arbitrary methods (SUBSCRIBE, ...) and keep-alive. */
class MiniHttpServer(
    private val port: Int,
    private val name: String,
    private val handler: (HttpRequest) -> HttpResponse,
) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        server = s
        Log.i(TAG, "$name listening on $port")
        pool.execute {
            while (!s.isClosed) {
                try {
                    val c = s.accept()
                    pool.execute { serve(c) }
                } catch (e: IOException) {
                    if (!s.isClosed) Log.w(TAG, "$name accept failed", e)
                }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        pool.shutdownNow()
    }

    private fun serve(sock: Socket) {
        var keep = false
        try {
            sock.soTimeout = 120_000
            sock.tcpNoDelay = true
            val input = sock.getInputStream().buffered()
            val out = sock.getOutputStream()
            while (true) {
                val req = readRequest(input, sock.inetAddress?.hostAddress ?: "") ?: break
                val resp = try {
                    handler(req)
                } catch (e: Exception) {
                    Log.w(TAG, "$name handler failed for ${req.method} ${req.target}", e)
                    HttpResponse(500)
                }
                writeResponse(out, req, resp)
                val upgrade = resp.onUpgrade
                if (upgrade != null) {
                    keep = true
                    upgrade(sock)
                    return
                }
                if (req.header("connection").equals("close", ignoreCase = true)) break
            }
        } catch (_: IOException) {
        } finally {
            if (!keep) runCatching { sock.close() }
        }
    }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = i.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 16384) throw IOException("line too long")
        }
    }

    private fun readFully(i: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = i.read(buf, off, n - off)
            if (r < 0) throw IOException("unexpected EOF")
            off += r
        }
        return buf
    }

    private fun readRequest(i: InputStream, remote: String): HttpRequest? {
        val line = readLine(i) ?: return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(i) ?: return null
            if (h.isEmpty()) break
            val idx = h.indexOf(':')
            if (idx > 0) headers[h.substring(0, idx).trim().lowercase()] = h.substring(idx + 1).trim()
        }
        val body = if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            readChunked(i)
        } else {
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            if (len < 0 || len > MAX_BODY) throw IOException("bad content-length")
            if (len > 0) readFully(i, len) else ByteArray(0)
        }
        return HttpRequest(parts[0].uppercase(), parts[1], headers, body, remote)
    }

    private fun readChunked(i: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = (readLine(i) ?: throw IOException("EOF in chunks")).substringBefore(';').trim().toInt(16)
            if (size == 0) {
                while (!readLine(i).isNullOrEmpty()) { /* skip trailers */ }
                break
            }
            if (out.size() + size > MAX_BODY) throw IOException("body too large")
            out.write(readFully(i, size))
            readLine(i)
        }
        return out.toByteArray()
    }

    private fun reason(status: Int) = when (status) {
        101 -> "Switching Protocols"
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        501 -> "Not Implemented"
        else -> "Status"
    }

    private fun writeResponse(out: java.io.OutputStream, req: HttpRequest, resp: HttpResponse) {
        val head = StringBuilder("HTTP/1.1 ${resp.status} ${reason(resp.status)}\r\n")
        resp.headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
        if (resp.status != 101) head.append("Content-Length: ${resp.body.size}\r\n")
        head.append("\r\n")
        val buf = ByteArrayOutputStream()
        buf.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (req.method != "HEAD" && resp.status != 101) buf.write(resp.body)
        out.write(buf.toByteArray())
        out.flush()
    }
}
