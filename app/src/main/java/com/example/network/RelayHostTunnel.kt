package com.example.network

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

class RelayHostTunnel(
  private val relayUrl: String,
  private val roomCode: String,
  private val pin: String?,
  private val scope: CoroutineScope,
  private val onMetricsChanged: (ProxyMetricsSnapshot) -> Unit,
) {
  private val client =
    OkHttpClient.Builder()
      .pingInterval(20, TimeUnit.SECONDS)
      .connectTimeout(12, TimeUnit.SECONDS)
      .readTimeout(0, TimeUnit.MILLISECONDS)
      .build()

  private class UpstreamSession(
    val socket: Socket,
    val writeChannel: Channel<ByteArray>,
  )

  private val sessions = ConcurrentHashMap<Int, UpstreamSession>()
  private val activeCount = AtomicInteger(0)
  private val totalBytes = AtomicLong(0L)

  @Volatile private var running = false
  @Volatile private var webSocket: WebSocket? = null
  @Volatile private var relayConnected = false
  @Volatile private var peerOnline = false
  @Volatile private var lastError: String? = null
  private var reconnectJob: Job? = null

  fun start() {
    if (running) return
    running = true
    lastError = null
    connectWebSocket()
  }

  fun stop() {
    running = false
    reconnectJob?.cancel()
    reconnectJob = null
    closeAllSessions()
    runCatching { webSocket?.close(1000, "Stopped") }
    webSocket = null
    relayConnected = false
    peerOnline = false
    emitMetrics()
  }

  private fun connectWebSocket() {
    val wsUrl = RelayProtocol.buildWebSocketUrl(relayUrl, "host", roomCode, pin)
    if (wsUrl == null) {
      lastError = "Invalid Relay Server URL or Room Code"
      running = false
      emitMetrics()
      return
    }

    val request = Request.Builder().url(wsUrl).build()
    webSocket =
      client.newWebSocket(
        request,
        object : WebSocketListener() {
          override fun onOpen(webSocket: WebSocket, response: Response) {
            relayConnected = true
            lastError = null
            emitMetrics()
          }

          override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching {
              val json = JSONObject(text)
              when (json.optString("type")) {
                "status" -> {
                  relayConnected = json.optBoolean("connected", true)
                  peerOnline = json.optBoolean("peerOnline", false)
                  if (!peerOnline) closeAllSessions()
                  emitMetrics()
                }
                "error" -> {
                  lastError = json.optString("message", "Relay error")
                  emitMetrics()
                }
              }
            }
          }

          override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val frame = RelayProtocol.decodeFrame(bytes) ?: return
            when (frame.type) {
              RelayFrameType.OPEN -> {
                val target = frame.payload.toString(Charsets.UTF_8)
                openUpstream(frame.streamId, target)
              }
              RelayFrameType.DATA -> {
                val session = sessions[frame.streamId] ?: return
                totalBytes.addAndGet(frame.payload.size.toLong())
                if (session.writeChannel.trySend(frame.payload).isFailure) {
                  closeSession(frame.streamId, notifyPeer = true)
                }
              }
              RelayFrameType.CLOSE -> closeSession(frame.streamId, notifyPeer = false)
              else -> Unit
            }
          }

          override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            relayConnected = false
            peerOnline = false
            lastError = t.localizedMessage ?: "Relay disconnected"
            closeAllSessions()
            emitMetrics()
            scheduleReconnect()
          }

          override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            relayConnected = false
            peerOnline = false
            closeAllSessions()
            emitMetrics()
            if (running && code != 1000) {
              scheduleReconnect()
            }
          }
        },
      )
  }

  private fun scheduleReconnect() {
    if (!running) return
    reconnectJob?.cancel()
    reconnectJob =
      scope.launch(Dispatchers.IO) {
        delay(3_000)
        if (running && !relayConnected) {
          connectWebSocket()
        }
      }
  }

  private fun openUpstream(streamId: Int, target: String) {
    scope.launch(Dispatchers.IO) {
      val host = target.substringBeforeLast(":").trim()
      val port = target.substringAfterLast(":", "443").trim().toIntOrNull() ?: 443
      val socket = Socket()
      try {
        socket.connect(InetSocketAddress(host, port), 12_000)
        socket.soTimeout = 60_000
        val writeChan = Channel<ByteArray>(capacity = 64)
        val session = UpstreamSession(socket, writeChan)
        sessions[streamId] = session
        activeCount.incrementAndGet()
        emitMetrics()

        webSocket?.send(RelayProtocol.encodeFrame(streamId, RelayFrameType.OPEN_OK))

        // Writer coroutine: friend -> internet
        val writerJob =
          launch(Dispatchers.IO) {
            try {
              val out = socket.getOutputStream()
              for (chunk in writeChan) {
                out.write(chunk)
                out.flush()
              }
            } catch (_: Exception) {
              // Socket closed
            } finally {
              closeSession(streamId, notifyPeer = true)
            }
          }

        // Reader loop: internet -> friend
        val inp = socket.getInputStream()
        val buf = ByteArray(16 * 1024)
        try {
          while (running && sessions.containsKey(streamId)) {
            val ws = webSocket ?: break
            while (ws.queueSize() > 2 * 1024 * 1024 && running) {
              delay(15)
            }
            val read = inp.read(buf)
            if (read <= 0) break
            val slice = buf.copyOf(read)
            totalBytes.addAndGet(read.toLong())
            ws.send(RelayProtocol.encodeFrame(streamId, RelayFrameType.DATA, slice))
            if (totalBytes.get() % (64 * 1024) < read) {
              emitMetrics()
            }
          }
        } catch (_: IOException) {
          // EOF / Closed
        } finally {
          writerJob.cancel()
          closeSession(streamId, notifyPeer = true)
        }
      } catch (e: Exception) {
        runCatching { socket.close() }
        val msg = (e.localizedMessage ?: "Connect failed").toByteArray(Charsets.UTF_8)
        webSocket?.send(RelayProtocol.encodeFrame(streamId, RelayFrameType.OPEN_ERR, msg))
      }
    }
  }

  private fun closeSession(streamId: Int, notifyPeer: Boolean) {
    val removed = sessions.remove(streamId)
    if (removed != null) {
      removed.writeChannel.close()
      runCatching { removed.socket.close() }
      activeCount.decrementAndGet().coerceAtLeast(0)
      if (notifyPeer) {
        webSocket?.send(RelayProtocol.encodeFrame(streamId, RelayFrameType.CLOSE))
      }
      emitMetrics()
    }
  }

  private fun closeAllSessions() {
    val keys = sessions.keys.toList()
    for (k in keys) {
      closeSession(k, notifyPeer = false)
    }
  }

  private fun emitMetrics() {
    onMetricsChanged(
      ProxyMetricsSnapshot(
        isRunning = running,
        port = 0,
        activeConnections = activeCount.get().coerceAtLeast(0),
        totalBytesRelayed = totalBytes.get(),
        clientIps = if (peerOnline) listOf("Relay Room $roomCode") else emptyList(),
        lastError = lastError,
        relayConnected = relayConnected,
        peerOnline = peerOnline,
      )
    )
  }
}
