package io.github.wamior.tvcast.mirror

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import kotlin.concurrent.thread

private const val TAG = "TVCast.Mirror"
private const val MAX_GOP_BYTES = 24 * 1024 * 1024

/**
 * Receives decrypted H.264 and AAC frames from the native AirPlay stack and plays them with MediaCodec.
 * Video goes to the [Surface] of [MirrorActivity]; frames are rendered as soon as they are decoded (low latency).
 */
object MirrorHub : AirPlayNative.Listener {
    private lateinit var app: Context
    private val lock = Any()

    private var surface: Surface? = null
    private var video: MediaCodec? = null
    private var videoThread: Thread? = null
    private var videoW = 1920
    private var videoH = 1080
    private var videoH265 = false
    private var configFrame: ByteArray? = null
    private var uiLaunched = false

    private var audio: MediaCodec? = null
    private var audioThread: Thread? = null
    private var track: AudioTrack? = null
    private var volume = 1f

    @Volatile var videoFrames = 0
    @Volatile var audioFrames = 0
    @Volatile var framesOut = 0

    /** Visible size of the stream as reported by the decoder; the TV screen letterboxes to it. */
    @Volatile var displayW = 0
    @Volatile var displayH = 0
    @Volatile var onDisplaySize: ((Int, Int) -> Unit)? = null
    @Volatile var decoder = "idle"
    val log = java.util.concurrent.ConcurrentLinkedDeque<String>()

    /** Frames since the last key frame: the TV activity (and its surface) appears after the first key frame was sent. */
    private class Frame(val data: ByteArray, val ptsUs: Long)

    private val gop = ArrayList<Frame>()
    private var gopBytes = 0
    private var gopValid = false

    fun summary(): String =
        "video frames in: $videoFrames, decoded: $framesOut, audio frames: $audioFrames, ${videoW}x$videoH\n" +
            "video decoder: $decoder\n" + log.toList().takeLast(6).joinToString("\n")

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun attachSurface(s: Surface) { synchronized(lock) {
        surface = s
        val cfg = configFrame
        if (cfg != null && gopValid && gop.isNotEmpty()) startVideo(cfg)
    } }

    fun detachSurface() { synchronized(lock) {
        surface = null
        stopVideo()
    } }

    /** User pressed Back on the TV: drop the phone's connection too. */
    fun userClosed() {
        AirPlayNative.disconnect()
        closeAll()
    }

    // ---- AirPlayNative.Listener (called on native threads) ----

    override fun onVideoSize(width: Int, height: Int, h265: Boolean) { synchronized(lock) {
        Log.i(TAG, "video ${width}x$height h265=$h265")
        videoW = width.coerceAtLeast(16)
        videoH = height.coerceAtLeast(16)
        videoH265 = h265
        stopVideo()
    } }

    override fun onVideoFrame(data: ByteArray, h265: Boolean, ntpNs: Long) { synchronized(lock) {
        videoFrames++
        launchUi()
        // The native layer marks undecryptable frames by setting the first byte to 1 (a valid frame starts with 0).
        if (data.isEmpty() || data[0].toInt() == 1) return
        // SPS+PPS are prepended to the first slice packet, so one packet is parameter sets plus an IDR frame.
        val parts = splitConfig(data, h265)
        if (parts != null) {
            val old = configFrame
            if (old == null || !old.contentEquals(parts.first)) {
                configFrame = parts.first
                stopVideo()
                gop.clear()
                gopBytes = 0
                gopValid = false
            }
            if (!parts.second) return
        }
        val idr = hasIdr(data, h265)
        if (idr) {
            gop.clear()
            gopBytes = 0
            gopValid = true
        }
        if (!gopValid) {
            if (video == null) decoder = "waiting for key frame"
            return
        }
        val running = video
        gop.add(Frame(data, ntpNs / 1000))
        gopBytes += data.size
        if (gopBytes > MAX_GOP_BYTES) {
            gop.clear()
            gopBytes = 0
            gopValid = false
        }
        if (running != null) {
            feed(running, data, ntpNs / 1000)
            return
        }
        val cfg = configFrame
        when {
            cfg == null -> decoder = "waiting for SPS/PPS"
            surface == null -> decoder = "waiting for surface"
            else -> startVideo(cfg)
        }
    } }

    private fun feed(codec: MediaCodec, data: ByteArray, ptsUs: Long) {
        try {
            val idx = codec.dequeueInputBuffer(20_000)
            if (idx >= 0) {
                val buf = codec.getInputBuffer(idx)!!
                buf.clear()
                if (data.size > buf.capacity()) return
                buf.put(data)
                codec.queueInputBuffer(idx, 0, data.size, ptsUs, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "video feed failed", e)
            log.addLast("video feed failed: ${e.message}")
            stopVideo()
        }
    }

    private fun hasIdr(data: ByteArray, h265: Boolean): Boolean {
        var i = 0
        while (i + 4 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                val start = when {
                    data[i + 2].toInt() == 1 -> i + 3
                    data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1 -> i + 4
                    else -> -1
                }
                if (start in 0 until data.size) {
                    val b = data[start].toInt()
                    val type = if (h265) (b shr 1) and 0x3f else b and 0x1f
                    if (if (h265) type == 19 || type == 20 else type == 5) return true
                    i = start
                    continue
                }
            }
            i++
        }
        return false
    }

    override fun onAudioFormat(ct: Int) { synchronized(lock) {
        Log.i(TAG, "audio ct=$ct")
        stopAudio()
        // ct: 2 = ALAC, 4 = AAC-LC, 8 = AAC-ELD
        val csd = when (ct) {
            8 -> byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00)
            4 -> byteArrayOf(0x12, 0x10)
            else -> {
                Log.w(TAG, "audio codec $ct not supported yet")
                return
            }
        }
        try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2)
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, if (ct == 8) 39 else 2)
            fmt.setInteger(MediaFormat.KEY_IS_ADTS, 0)
            fmt.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd))
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(fmt, null, null, 0)
            codec.start()
            audio = codec

            val minBuf = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(44100)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            t.setVolume(volume)
            t.play()
            track = t
            audioThread = thread(name = "mirror-audio-out", isDaemon = true) { drainAudio(codec, t) }
        } catch (e: Exception) {
            Log.e(TAG, "audio decoder setup failed", e)
            stopAudio()
        }
    } }

    override fun onAudioFrame(data: ByteArray, ct: Int, ntpNs: Long) { synchronized(lock) {
        audioFrames++
        val codec = audio ?: return
        try {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val buf = codec.getInputBuffer(idx)!!
                buf.clear()
                if (data.size > buf.capacity()) return
                buf.put(data)
                codec.queueInputBuffer(idx, 0, data.size, ntpNs / 1000, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "audio feed failed", e)
            stopAudio()
        }
    } }

    override fun onFlush(video: Boolean) { synchronized(lock) {
        try {
            if (video) this.video?.flush() else audio?.flush()
        } catch (e: Exception) {
            Log.w(TAG, "flush failed", e)
        }
    } }

    override fun onConnection(open: Boolean) {
        Log.i(TAG, "connection open=$open")
        if (!open) closeAll()
    }

    override fun onVolume(db: Float) { synchronized(lock) {
        volume = when {
            db <= -144f -> 0f
            db >= 0f -> 1f
            db <= -30f -> 0f
            else -> (30f + db) / 30f
        }
        track?.setVolume(volume)
        Unit
    } }

    override fun onLog(message: String) {
        log.addLast(message)
        while (log.size > 30) log.pollFirst()
    }

    override fun onReset(reason: Int) {
        Log.w(TAG, "connection reset reason=$reason")
        closeAll()
    }

    // ---- internals ----

    private fun launchUi() {
        if (uiLaunched) return
        uiLaunched = true
        try {
            app.startActivity(
                Intent(app, MirrorActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (e: Exception) {
            Log.w(TAG, "cannot open mirror screen (overlay permission missing?)", e)
        }
    }

    private fun closeAll() { synchronized(lock) {
        decoder = "idle"
        gop.clear()
        gopBytes = 0
        gopValid = false
        framesOut = 0
        videoFrames = 0
        audioFrames = 0
        stopVideo()
        stopAudio()
        configFrame = null
        uiLaunched = false
        MirrorActivity.finishIfOpen()
    } }

    /**
     * If the packet starts with an SPS, returns (parameter sets, packet also carries slice data); null otherwise.
     * Only H.264 is handled; HEVC packets are passed through unchanged.
     */
    private fun splitConfig(data: ByteArray, h265: Boolean): Pair<ByteArray, Boolean>? {
        if (h265 || data.size < 5) return null
        if ((data[4].toInt() and 0x1f) != 7) return null
        var i = 4
        while (i + 4 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
                val type = data[i + 4].toInt() and 0x1f
                if (type == 1 || type == 5) return Pair(data.copyOfRange(0, i), true)
                i += 4
            } else {
                i++
            }
        }
        return Pair(data, false)
    }

    private fun startVideo(cfg: ByteArray) {
        val s = surface ?: run { decoder = "waiting for surface"; return }
        if (video != null) return
        try {
            val mime = if (videoH265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val fmt = MediaFormat.createVideoFormat(mime, videoW, videoH)
            fmt.setInteger(MediaFormat.KEY_MAX_WIDTH, maxOf(videoW, videoH))
            fmt.setInteger(MediaFormat.KEY_MAX_HEIGHT, maxOf(videoW, videoH))
            if (Build.VERSION.SDK_INT >= 30) fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, s, null, 0)
            codec.start()
            video = codec
            decoder = "running ${if (videoH265) "hevc" else "avc"} ${videoW}x$videoH"
            videoThread = thread(name = "mirror-video-out", isDaemon = true) { drainVideo(codec) }
            val idx = codec.dequeueInputBuffer(100_000)
            if (idx >= 0) {
                val buf = codec.getInputBuffer(idx)!!
                buf.clear()
                buf.put(cfg)
                codec.queueInputBuffer(idx, 0, cfg.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
            }
            for (f in gop.toList()) feed(codec, f.data, f.ptsUs)
        } catch (e: Exception) {
            Log.e(TAG, "video decoder setup failed", e)
            decoder = "setup failed: ${e.message}"
            stopVideo()
        }
    }

    private fun stopVideo() {
        val c = video ?: return
        video = null
        runCatching { c.stop() }
        runCatching { c.release() }
    }

    private fun stopAudio() {
        val c = audio
        audio = null
        c?.let { runCatching { it.stop() }; runCatching { it.release() } }
        val t = track
        track = null
        t?.let { runCatching { it.stop() }; runCatching { it.release() } }
    }

    /** Visible size of the decoded picture (decoders pad to macroblock multiples and report a crop rectangle). */
    private fun reportDisplaySize(f: MediaFormat) {
        fun span(lo: String, hi: String, size: String): Int =
            if (f.containsKey(lo) && f.containsKey(hi)) f.getInteger(hi) - f.getInteger(lo) + 1 else f.getInteger(size)
        val w = span("crop-left", "crop-right", MediaFormat.KEY_WIDTH)
        val h = span("crop-top", "crop-bottom", MediaFormat.KEY_HEIGHT)
        displayW = w
        displayH = h
        onDisplaySize?.invoke(w, h)
    }

    private fun drainVideo(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, 50_000)
                if (idx >= 0) {
                    if (info.size > 0) framesOut++
                    codec.releaseOutputBuffer(idx, true)
                } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    reportDisplaySize(codec.outputFormat)
                }
            }
        } catch (e: Exception) {
            log.addLast("video output ended: ${e.message}")
        }
    }

    private fun drainAudio(codec: MediaCodec, track: AudioTrack) {
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, 50_000)
                if (idx >= 0) {
                    val out = codec.getOutputBuffer(idx)!!
                    val bytes = ByteArray(info.size)
                    out.get(bytes)
                    out.clear()
                    codec.releaseOutputBuffer(idx, false)
                    if (bytes.isNotEmpty()) track.write(bytes, 0, bytes.size)
                }
            }
        } catch (_: Exception) {
            // codec/track released
        }
    }
}
