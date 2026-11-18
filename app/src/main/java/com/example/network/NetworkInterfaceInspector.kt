package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

enum class InterfaceKind {
  REMOTE_TUNNEL,
  LOCAL_NETWORK,
  CELLULAR_PRIVATE,
}

data class NetworkAddressInfo(
  val interfaceName: String,
  val ipAddress: String,
  val kind: InterfaceKind,
)

data class UpstreamStatus(
  val hasInternet: Boolean,
  val transportLabel: String,
)

object NetworkInterfaceInspector {

  fun getActiveIpv4Addresses(): List<NetworkAddressInfo> {
    val results = mutableListOf<NetworkAddressInfo>()
    val interfaces =
      runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return emptyList()

    for (iface in interfaces) {
      if (!iface.isUp || iface.isLoopback) continue
      val name = iface.name.lowercase()

      for (addr in iface.inetAddresses) {
        if (addr is Inet4Address && !addr.isLoopbackAddress) {
          val hostIp = addr.hostAddress ?: continue
          val kind = classifyInterface(name, hostIp)
          results.add(
            NetworkAddressInfo(
              interfaceName = iface.name,
              ipAddress = hostIp,
              kind = kind,
            )
          )
        }
      }
    }

    return results.sortedBy { it.kind.ordinal }
  }

  private fun classifyInterface(name: String, ip: String): InterfaceKind =
    when {
      name.startsWith("tun") ||
        name.startsWith("wg") ||
        name.startsWith("ppp") ||
        name.startsWith("tailscale") ||
        ip.startsWith("100.") -> InterfaceKind.REMOTE_TUNNEL
      name.startsWith("wlan") ||
        name.startsWith("ap") ||
        name.startsWith("swlan") ||
        name.startsWith("eth") -> InterfaceKind.LOCAL_NETWORK
      else -> InterfaceKind.CELLULAR_PRIVATE
    }

  fun checkUpstreamInternet(context: Context): UpstreamStatus {
    val cm =
      context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return UpstreamStatus(false, "Unknown")
    val network = cm.activeNetwork ?: return UpstreamStatus(false, "None")
    val caps = cm.getNetworkCapabilities(network) ?: return UpstreamStatus(false, "None")
    val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    val transport =
      when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN Tunnel"
        else -> "Network"
      }
    return UpstreamStatus(hasInternet = hasInternet, transportLabel = transport)
  }
}
