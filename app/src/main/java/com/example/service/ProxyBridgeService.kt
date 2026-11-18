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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import com.example.network.ProxyBridgeServer
import com.example.network.ProxyMetricsSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ProxyBridgeService : Service() {

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var proxyServer: ProxyBridgeServer? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      stopBridgeInternal()
      return START_NOT_STICKY
    }

    val port = intent?.getIntExtra(EXTRA_PORT, 8888) ?: 8888
    val pin = intent?.getStringExtra(EXTRA_PIN)?.takeIf { it.isNotBlank() }

    val notification = buildNotification(port = port, bytesRelayed = 0L, activeClients = 0)
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

    proxyServer?.stop()
    val server =
      ProxyBridgeServer(
        port = port,
        requiredPin = pin,
        scope = serviceScope,
        onMetricsChanged = { snapshot ->
          _metricsState.value = snapshot
          val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
          mgr?.notify(
            NOTIFICATION_ID,
            buildNotification(
              port = snapshot.port,
              bytesRelayed = snapshot.totalBytesRelayed,
              activeClients = snapshot.activeConnections,
            ),
          )
        },
      )
    proxyServer = server
    server.start()

    return START_STICKY
  }

  private fun stopBridgeInternal() {
    proxyServer?.stop()
    proxyServer = null
    _metricsState.value =
      ProxyMetricsSnapshot(
        isRunning = false,
        port = _metricsState.value.port,
        activeConnections = 0,
        totalBytesRelayed = _metricsState.value.totalBytesRelayed,
        clientIps = emptyList(),
        lastError = null,
      )
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    proxyServer?.stop()
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
  ): Notification {
    val openIntent =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
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

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_stat_bridge)
      .setContentTitle(getString(R.string.notification_bridge_title))
      .setContentText("Port $port • $activeClients active • $formattedBytes")
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
    const val EXTRA_PORT = "extra_port"
    const val EXTRA_PIN = "extra_pin"

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

    fun start(context: Context, port: Int, pin: String?) {
      val intent =
        Intent(context, ProxyBridgeService::class.java).apply {
          action = ACTION_START
          putExtra(EXTRA_PORT, port)
          if (!pin.isNullOrBlank()) {
            putExtra(EXTRA_PIN, pin)
          }
        }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stop(context: Context) {
      val intent =
        Intent(context, ProxyBridgeService::class.java).apply {
          action = ACTION_STOP
        }
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
