package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import com.example.network.CellularNetworkManager
import com.example.network.ProxyBridgeServer
import com.example.network.ProxyMetricsSnapshot
import com.example.network.RelayFriendTunnel
import com.example.network.RelayHostTunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ServiceBridgeMode {
  HOST_DIRECT,
  HOST_RELAY,
  FRIEND_RELAY,
}

class ProxyBridgeService : Service() {

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var directServer: ProxyBridgeServer? = null
  private var relayHost: RelayHostTunnel? = null
  private var relayFriend: RelayFriendTunnel? = null
  private var cellularManager: CellularNetworkManager? = null
  private var wakeLock: PowerManager.WakeLock? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      stopAllInternal()
      return START_NOT_STICKY
    }

    val modeName = intent?.getStringExtra(EXTRA_MODE) ?: ServiceBridgeMode.HOST_DIRECT.name
    val mode =
      runCatching { ServiceBridgeMode.valueOf(modeName) }.getOrDefault(ServiceBridgeMode.HOST_DIRECT)
    val port = intent?.getIntExtra(EXTRA_PORT, 8888) ?: 8888
    val pin = intent?.getStringExtra(EXTRA_PIN)?.takeIf { it.isNotBlank() }
    val relayUrl = intent?.getStringExtra(EXTRA_RELAY_URL).orEmpty()
    val roomCode = intent?.getStringExtra(EXTRA_ROOM_CODE).orEmpty()

    val notification =
      buildNotification(port = port, bytesRelayed = 0L, activeClients = 0, mode = mode)
    ServiceCompat.startForeground(
      this,
      NOTIFICATION_ID,
      notification,
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
      } else {
        0
      },
    )

    stopActiveEnginesOnly()
    acquireBridgeWakeLock()
    _activeModeState.value = mode

    val netManager =
      CellularNetworkManager(this) {
        relayHost?.onNetworkChanged()
        relayFriend?.onNetworkChanged()
      }.also { it.start() }
    cellularManager = netManager

    val onMetrics: (ProxyMetricsSnapshot) -> Unit = { snapshot ->
      _metricsState.value = snapshot
      val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
      mgr?.notify(
        NOTIFICATION_ID,
        buildNotification(
          port = snapshot.port,
          bytesRelayed = snapshot.totalBytesRelayed,
          activeClients = snapshot.activeConnections,
          mode = mode,
        ),
      )
    }

    when (mode) {
      ServiceBridgeMode.HOST_DIRECT -> {
        val server =
          ProxyBridgeServer(
            port = port,
            requiredPin = pin,
            scope = serviceScope,
            onMetricsChanged = onMetrics,
          )
        directServer = server
        server.start()
      }
      ServiceBridgeMode.HOST_RELAY -> {
        val host =
          RelayHostTunnel(
            relayUrl = relayUrl,
            roomCode = roomCode,
            pin = pin,
            scope = serviceScope,
            cellularManager = netManager,
            onMetricsChanged = onMetrics,
          )
        relayHost = host
        host.start()
      }
      ServiceBridgeMode.FRIEND_RELAY -> {
        val friend =
          RelayFriendTunnel(
            localPort = port,
            relayUrl = relayUrl,
            roomCode = roomCode,
            pin = pin,
            scope = serviceScope,
            cellularManager = netManager,
            onMetricsChanged = onMetrics,
          )
        relayFriend = friend
        friend.start()
      }
    }

    return START_STICKY
  }

  private fun acquireBridgeWakeLock() {
    if (wakeLock?.isHeld == true) return
    val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
    wakeLock =
      pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NetSetu::BridgeWakeLock").apply {
        setReferenceCounted(false)
        // Safety cap 6 hours; re-acquired cleanly on restart
        acquire(6 * 60 * 60 * 1000L)
      }
  }

  private fun releaseBridgeWakeLock() {
    runCatching {
      if (wakeLock?.isHeld == true) {
        wakeLock?.release()
      }
    }
    wakeLock = null
  }

  private fun stopActiveEnginesOnly() {
    directServer?.stop()
    directServer = null
    relayHost?.stop()
    relayHost = null
    relayFriend?.stop()
    relayFriend = null
    cellularManager?.stop()
    cellularManager = null
    releaseBridgeWakeLock()
  }

  private fun stopAllInternal() {
    stopActiveEnginesOnly()
    _metricsState.value =
      ProxyMetricsSnapshot(
        isRunning = false,
        port = _metricsState.value.port,
        activeConnections = 0,
        totalBytesRelayed = _metricsState.value.totalBytesRelayed,
        clientIps = emptyList(),
        lastError = null,
        relayConnected = false,
        peerOnline = false,
        cellularBound = false,
      )
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    stopActiveEnginesOnly()
    serviceScope.cancel()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
        NotificationChannel(
          CHANNEL_ID,
          getString(R.string.notification_channel_bridge),
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = getString(R.string.notification_channel_bridge_desc)
        }
      val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
      mgr?.createNotificationChannel(channel)
    }
  }

  private fun buildNotification(
    port: Int,
    bytesRelayed: Long,
    activeClients: Int,
    mode: ServiceBridgeMode,
  ): Notification {
    val openIntent =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val stopIntent =
      PendingIntent.getService(
        this,
        1,
        Intent(this, ProxyBridgeService::class.java).apply { action = ACTION_STOP },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val formattedBytes = formatByteCount(bytesRelayed)
    val subtitle =
      when (mode) {
        ServiceBridgeMode.HOST_RELAY -> "Relay Host • $activeClients active • $formattedBytes"
        ServiceBridgeMode.FRIEND_RELAY -> "127.0.0.1:$port • $activeClients active • $formattedBytes"
        ServiceBridgeMode.HOST_DIRECT -> "Port $port • $activeClients active • $formattedBytes"
      }

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_stat_bridge)
      .setContentTitle(getString(R.string.notification_bridge_title))
      .setContentText(subtitle)
      .setOngoing(true)
      .setContentIntent(openIntent)
      .addAction(0, getString(R.string.notification_bridge_stop), stopIntent)
      .build()
  }

  companion object {
    const val CHANNEL_ID = "netsetu_bridge_channel"
    const val NOTIFICATION_ID = 4091
    const val ACTION_START = "com.example.action.START_BRIDGE"
    const val ACTION_STOP = "com.example.action.STOP_BRIDGE"
    const val EXTRA_MODE = "extra_mode"
    const val EXTRA_PORT = "extra_port"
    const val EXTRA_PIN = "extra_pin"
    const val EXTRA_RELAY_URL = "extra_relay_url"
    const val EXTRA_ROOM_CODE = "extra_room_code"

    private val _metricsState =
      MutableStateFlow(
        ProxyMetricsSnapshot(
          isRunning = false,
          port = 8888,
          activeConnections = 0,
          totalBytesRelayed = 0L,
          clientIps = emptyList(),
          lastError = null,
        )
      )
    val metricsState: StateFlow<ProxyMetricsSnapshot> = _metricsState.asStateFlow()

    private val _activeModeState = MutableStateFlow(ServiceBridgeMode.HOST_DIRECT)
    val activeModeState: StateFlow<ServiceBridgeMode> = _activeModeState.asStateFlow()

    fun startDirectHost(context: Context, port: Int, pin: String?) {
      startWithExtras(context) {
        putExtra(EXTRA_MODE, ServiceBridgeMode.HOST_DIRECT.name)
        putExtra(EXTRA_PORT, port)
        if (!pin.isNullOrBlank()) putExtra(EXTRA_PIN, pin)
      }
    }

    fun startRelayHost(context: Context, relayUrl: String, roomCode: String, pin: String?) {
      startWithExtras(context) {
        putExtra(EXTRA_MODE, ServiceBridgeMode.HOST_RELAY.name)
        putExtra(EXTRA_RELAY_URL, relayUrl)
        putExtra(EXTRA_ROOM_CODE, roomCode)
        if (!pin.isNullOrBlank()) putExtra(EXTRA_PIN, pin)
      }
    }

    fun startRelayFriend(
      context: Context,
      localPort: Int,
      relayUrl: String,
      roomCode: String,
      pin: String?,
    ) {
      startWithExtras(context) {
        putExtra(EXTRA_MODE, ServiceBridgeMode.FRIEND_RELAY.name)
        putExtra(EXTRA_PORT, localPort)
        putExtra(EXTRA_RELAY_URL, relayUrl)
        putExtra(EXTRA_ROOM_CODE, roomCode)
        if (!pin.isNullOrBlank()) putExtra(EXTRA_PIN, pin)
      }
    }

    private fun startWithExtras(context: Context, extras: Intent.() -> Unit) {
      val intent =
        Intent(context, ProxyBridgeService::class.java).apply {
          action = ACTION_START
          extras()
        }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stop(context: Context) {
      val intent =
        Intent(context, ProxyBridgeService::class.java).apply { action = ACTION_STOP }
      context.startService(intent)
    }

    fun formatByteCount(bytes: Long): String =
      when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
      }
  }
}
