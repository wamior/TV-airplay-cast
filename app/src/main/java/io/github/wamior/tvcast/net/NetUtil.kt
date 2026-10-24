package io.github.wamior.tvcast.net

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtil {
    fun localInterface(): NetworkInterface? =
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.filter { it.isUp && !it.isLoopback && !it.isVirtual }
            ?.firstOrNull { itf -> itf.inetAddresses.toList().any { it is Inet4Address && it.isSiteLocalAddress } }

    fun localIpv4(): String? =
        localInterface()?.inetAddresses?.toList()
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
}
