package io.github.wamior.tvcast.mirror

/** JNI bridge to the UxPlay protocol library (see app/src/main/cpp). */
object AirPlayNative {
    interface Listener {
        fun onVideoSize(width: Int, height: Int, h265: Boolean)
        fun onVideoFrame(data: ByteArray, h265: Boolean, ntpNs: Long)
        fun onAudioFormat(ct: Int)
        fun onAudioFrame(data: ByteArray, ct: Int, ntpNs: Long)
        fun onFlush(video: Boolean)
        fun onConnection(open: Boolean)
        fun onVolume(db: Float)
        fun onReset(reason: Int)
    }

    init {
        System.loadLibrary("tvcastnative")
    }

    /** Returns the listening port (> 0) or a negative error code. */
    external fun start(listener: Listener, name: String, hwAddr: ByteArray, mac: String, keyFile: String): Int

    /** [raopName, raopTxt, airplayName, airplayTxt]; the TXT strings are "key=value\n" lines. */
    external fun services(): Array<String?>

    external fun disconnect()

    external fun stop()
}
