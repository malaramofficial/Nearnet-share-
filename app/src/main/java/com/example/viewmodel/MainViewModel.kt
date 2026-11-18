package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BridgePreferencesRepository
import com.example.data.BridgeSettings
import com.example.network.NetworkAddressInfo
import com.example.network.NetworkInterfaceInspector
import com.example.network.ProxyConnectivityTester
import com.example.network.ProxyMetricsSnapshot
import com.example.network.TunnelTestResult
import com.example.network.UpstreamStatus
import com.example.service.ProxyBridgeService
import com.example.service.ServiceBridgeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
  val settings: BridgeSettings = BridgeSettings(),
  val proxyMetrics: ProxyMetricsSnapshot = ProxyBridgeService.metricsState.value,
  val activeServiceMode: ServiceBridgeMode = ProxyBridgeService.activeModeState.value,
  val activeAddresses: List<NetworkAddressInfo> = emptyList(),
  val upstreamStatus: UpstreamStatus = UpstreamStatus(false, "Checking"),
  val tunnelTestResult: TunnelTestResult = TunnelTestResult.Idle,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

  private val prefs = BridgePreferencesRepository(application)

  private val activeAddresses = MutableStateFlow<List<NetworkAddressInfo>>(emptyList())
  private val upstreamStatus = MutableStateFlow(UpstreamStatus(false, "Checking"))
  private val tunnelTestResult = MutableStateFlow<TunnelTestResult>(TunnelTestResult.Idle)

  val uiState: StateFlow<MainUiState> =
    combine(
      prefs.settingsFlow,
      ProxyBridgeService.metricsState,
      ProxyBridgeService.activeModeState,
      activeAddresses,
      upstreamStatus,
      tunnelTestResult,
    ) { values ->
      val settings = values[0] as BridgeSettings
      val metrics = values[1] as ProxyMetricsSnapshot
      val serviceMode = values[2] as ServiceBridgeMode
      @Suppress("UNCHECKED_CAST")
      val addrs = values[3] as List<NetworkAddressInfo>
      val upstream = values[4] as UpstreamStatus
      val testRes = values[5] as TunnelTestResult
      MainUiState(
        settings = settings,
        proxyMetrics = metrics,
        activeServiceMode = serviceMode,
        activeAddresses = addrs,
        upstreamStatus = upstream,
        tunnelTestResult = testRes,
      )
    }.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = MainUiState(),
    )

  init {
    viewModelScope.launch { prefs.ensureRoomCodeExists() }
    refreshNetworkState()
  }

  fun refreshNetworkState() {
    viewModelScope.launch(Dispatchers.IO) {
      val addresses = NetworkInterfaceInspector.getActiveIpv4Addresses()
      val upstream = NetworkInterfaceInspector.checkUpstreamInternet(getApplication())
      activeAddresses.value = addresses
      upstreamStatus.value = upstream
    }
  }

  fun toggleLanguage() {
    viewModelScope.launch { prefs.toggleLanguage() }
  }

  fun updateRelayConfig(useRelayMode: Boolean, relayUrl: String, roomCode: String) {
    viewModelScope.launch { prefs.updateRelayConfig(useRelayMode, relayUrl, roomCode) }
  }

  fun regenerateRoomCode() {
    viewModelScope.launch { prefs.regenerateRoomCode() }
  }

  fun updateProxyConfig(port: Int, requirePin: Boolean, pin: String) {
    viewModelScope.launch { prefs.updateProxyConfig(port, requirePin, pin) }
  }

  fun startDirectHost(port: Int, requirePin: Boolean, pin: String) {
    val ctx = getApplication<Application>()
    val effectivePin = if (requirePin) pin.takeIf { it.isNotBlank() } else null
    ProxyBridgeService.startDirectHost(ctx, port = port, pin = effectivePin)
    refreshNetworkState()
  }

  fun startRelayHost(relayUrl: String, roomCode: String, requirePin: Boolean, pin: String) {
    val ctx = getApplication<Application>()
    val effectivePin = if (requirePin) pin.takeIf { it.isNotBlank() } else null
    ProxyBridgeService.startRelayHost(ctx, relayUrl = relayUrl, roomCode = roomCode, pin = effectivePin)
  }

  fun startRelayFriend(localPort: Int, relayUrl: String, roomCode: String, pin: String?) {
    val ctx = getApplication<Application>()
    ProxyBridgeService.startRelayFriend(
      ctx,
      localPort = localPort,
      relayUrl = relayUrl,
      roomCode = roomCode,
      pin = pin?.takeIf { it.isNotBlank() },
    )
  }

  fun stopBridge() {
    ProxyBridgeService.stop(getApplication())
    refreshNetworkState()
  }

  fun updateTestTarget(host: String, port: Int) {
    viewModelScope.launch { prefs.updateTestTarget(host, port) }
  }

  fun runProxyTunnelTest(host: String, port: Int, pin: String?) {
    viewModelScope.launch {
      tunnelTestResult.value = TunnelTestResult.Testing
      tunnelTestResult.value = ProxyConnectivityTester.testProxyTunnel(host, port, pin)
    }
  }
}
