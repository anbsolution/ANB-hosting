package com.anbhosting.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/** Resolves the phone's active Wi-Fi IPv4 address for optional LAN access. */
internal object LocalNetwork {
    fun wifiIpv4Address(context: Context): Inet4Address? = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiNetwork = manager.allNetworks.firstOrNull { network ->
            manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }

        wifiNetwork?.let {
            manager.getLinkProperties(it)
                ?.linkAddresses
                ?.asSequence()
                ?.mapNotNull { address -> address.address as? Inet4Address }
                ?.firstOrNull { address -> !address.isLoopbackAddress && !address.isLinkLocalAddress }
        }
    }.getOrNull()
}
