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
      activeAddresses,
      upstreamStatus,
      tunnelTestResult,
    ) { settings, metrics, addrs, upstream, testRes ->
      MainUiState(
        settings = settings,
        proxyMetrics = metrics,
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

  fun updateProxyConfig(port: Int, requirePin: Boolean, pin: String) {
    viewModelScope.launch { prefs.updateProxyConfig(port, requirePin, pin) }
  }

  fun startOrStopBridge(currentlyRunning: Boolean, port: Int, requirePin: Boolean, pin: String) {
    val ctx = getApplication<Application>()
    if (currentlyRunning) {
      ProxyBridgeService.stop(ctx)
    } else {
      val effectivePin = if (requirePin) pin.takeIf { it.isNotBlank() } else null
      ProxyBridgeService.start(ctx, port = port, pin = effectivePin)
    }
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
