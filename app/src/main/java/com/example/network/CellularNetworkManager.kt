package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Dns

data class NetworkBindingState(
  val isCellularBound: Boolean = false,
  val hasDefaultNetwork: Boolean = false,
)

/**
 * Requests and retains an explicit TRANSPORT_CELLULAR Network handle via ConnectivityManager
 * so sockets continue flowing over cellular data even when Wi-Fi/Hotspot disconnects.
 * Automatically falls back to the active default network if cellular data is unavailable.
 */
class CellularNetworkManager(
  context: Context,
  private val onNetworkSwitch: () -> Unit,
) {
  private val connectivityManager =
    context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

  @Volatile private var cellularNetwork: Network? = null
  @Volatile private var defaultNetwork: Network? = null
  @Volatile private var started = false

  private val _bindingState = MutableStateFlow(NetworkBindingState())
  val bindingState: StateFlow<NetworkBindingState> = _bindingState.asStateFlow()

  private val cellularCallback =
    object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        val wasNull = cellularNetwork == null
        cellularNetwork = network
        updateState()
        if (wasNull) {
          onNetworkSwitch()
        }
      }

      override fun onLost(network: Network) {
        if (cellularNetwork == network) {
          cellularNetwork = null
          updateState()
          onNetworkSwitch()
        }
      }
    }

  private val defaultCallback =
    object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        defaultNetwork = network
        updateState()
        onNetworkSwitch()
      }

      override fun onLost(network: Network) {
        if (defaultNetwork == network) {
          defaultNetwork = null
          updateState()
          onNetworkSwitch()
        }
      }
    }

  fun start() {
    if (started) return
    val cm = connectivityManager ?: return
    started = true

    val cellularRequest =
      NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        .build()

    runCatching { cm.requestNetwork(cellularRequest, cellularCallback) }
    runCatching { cm.registerDefaultNetworkCallback(defaultCallback) }
  }

  fun stop() {
    if (!started) return
    started = false
    val cm = connectivityManager
    if (cm != null) {
      runCatching { cm.unregisterNetworkCallback(cellularCallback) }
      runCatching { cm.unregisterNetworkCallback(defaultCallback) }
    }
    cellularNetwork = null
    defaultNetwork = null
    _bindingState.value = NetworkBindingState()
  }

  private fun updateState() {
    _bindingState.value =
      NetworkBindingState(
        isCellularBound = cellularNetwork != null,
        hasDefaultNetwork = defaultNetwork != null,
      )
  }

  fun createBoundSocket(): Socket {
    val preferred = cellularNetwork ?: defaultNetwork
    if (preferred != null) {
      val bound = runCatching { preferred.socketFactory.createSocket() }.getOrNull()
      if (bound != null) return bound
    }
    return Socket()
  }

  fun resolveHost(hostname: String): List<InetAddress> {
    val preferred = cellularNetwork ?: defaultNetwork
    if (preferred != null) {
      val resolved = runCatching { preferred.getAllByName(hostname).toList() }.getOrNull()
      if (!resolved.isNullOrEmpty()) return resolved
    }
    return Dns.SYSTEM.lookup(hostname)
  }

  val delegatingSocketFactory: SocketFactory =
    object : SocketFactory() {
      override fun createSocket(): Socket = createBoundSocket()

      override fun createSocket(host: String?, port: Int): Socket {
        val preferred = cellularNetwork ?: defaultNetwork
        if (preferred != null && host != null) {
          val sock = runCatching { preferred.socketFactory.createSocket(host, port) }.getOrNull()
          if (sock != null) return sock
        }
        return getDefault().createSocket(host, port)
      }

      override fun createSocket(
        host: String?,
        port: Int,
        localHost: InetAddress?,
        localPort: Int,
      ): Socket {
        val preferred = cellularNetwork ?: defaultNetwork
        if (preferred != null && host != null) {
          val sock =
            runCatching {
                preferred.socketFactory.createSocket(host, port, localHost, localPort)
              }
              .getOrNull()
          if (sock != null) return sock
        }
        return getDefault().createSocket(host, port, localHost, localPort)
      }

      override fun createSocket(host: InetAddress?, port: Int): Socket {
        val preferred = cellularNetwork ?: defaultNetwork
        if (preferred != null && host != null) {
          val sock = runCatching { preferred.socketFactory.createSocket(host, port) }.getOrNull()
          if (sock != null) return sock
        }
        return getDefault().createSocket(host, port)
      }

      override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
      ): Socket {
        val preferred = cellularNetwork ?: defaultNetwork
        if (preferred != null && address != null) {
          val sock =
            runCatching {
                preferred.socketFactory.createSocket(address, port, localAddress, localPort)
              }
              .getOrNull()
          if (sock != null) return sock
        }
        return getDefault().createSocket(address, port, localAddress, localPort)
      }
    }

  val delegatingDns: Dns =
    object : Dns {
      override fun lookup(hostname: String): List<InetAddress> = resolveHost(hostname)
    }
}
