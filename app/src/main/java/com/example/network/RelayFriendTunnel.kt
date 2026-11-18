package com.example.network

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

class RelayFriendTunnel(
  private val localPort: Int,
  private val relayUrl: String,
  private val roomCode: String,
  private val pin: String?,
  private val scope: CoroutineScope,
  private val cellularManager: CellularNetworkManager? = null,
  private val onMetricsChanged: (ProxyMetricsSnapshot) -> Unit,
) {
  private val client =
    OkHttpClient.Builder()
      .pingInterval(12, TimeUnit.SECONDS)
      .connectTimeout(45, TimeUnit.SECONDS)
      .readTimeout(0, TimeUnit.MILLISECONDS)
      .apply {
        if (cellularManager != null) {
          socketFactory(cellularManager.delegatingSocketFactory)
          dns(cellularManager.delegatingDns)
        }
      }
      .build()

  private class LocalClientSession(
    val socket: Socket,
    val writeChannel: Channel<ByteArray>,
  )

  private val streamIdGen = AtomicInteger(1)
  private val pendingOpens = ConcurrentHashMap<Int, CompletableDeferred<Result<Unit>>>()
  private val sessions = ConcurrentHashMap<Int, LocalClientSession>()
  private val activeCount = AtomicInteger(0)
  private val totalBytes = AtomicLong(0L)

  private var serverSocket: ServerSocket? = null
  private var acceptJob: Job? = null
  private var reconnectJob: Job? = null

  @Volatile private var running = false
  @Volatile private var webSocket: WebSocket? = null
  @Volatile private var relayConnected = false
  @Volatile private var peerOnline = false
  @Volatile private var lastError: String? = null

  fun start() {
    if (running) return
    running = true
    lastError = null
    connectWebSocket()
    startLocalProxyListener()
  }

  fun stop() {
    running = false
    reconnectJob?.cancel()
    reconnectJob = null
    acceptJob?.cancel()
    acceptJob = null
    runCatching { serverSocket?.close() }
    serverSocket = null
    closeAllSessions()
    runCatching { webSocket?.close(1000, "Stopped") }
    webSocket = null
    relayConnected = false
    peerOnline = false
    emitMetrics()
  }

  private fun connectWebSocket() {
    val wsUrl = RelayProtocol.buildWebSocketUrl(relayUrl, "friend", roomCode, pin)
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
              RelayFrameType.OPEN_OK -> {
                pendingOpens.remove(frame.streamId)?.complete(Result.success(Unit))
              }
              RelayFrameType.OPEN_ERR -> {
                val msg = frame.payload.toString(Charsets.UTF_8)
                pendingOpens.remove(frame.streamId)?.complete(Result.failure(IOException(msg)))
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
            lastError =
              when {
                response != null -> "Relay HTTP ${response.code} ${response.message}".trim()
                !t.localizedMessage.isNullOrBlank() -> t.localizedMessage
                else -> "${t.javaClass.simpleName}: Relay connection lost"
              }
            closeAllSessions()
            emitMetrics()
            scheduleReconnect()
          }

          override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            relayConnected = false
            peerOnline = false
            if (code == 1008 && reason.isNotBlank()) {
              lastError = reason
            }
            closeAllSessions()
            emitMetrics()
            if (running && code != 1000 && code != 1008) {
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

  fun onNetworkChanged() {
    if (!running) return
    emitMetrics()
    if (!relayConnected) {
      reconnectJob?.cancel()
      reconnectJob =
        scope.launch(Dispatchers.IO) {
          delay(500)
          if (running && !relayConnected) {
            connectWebSocket()
          }
        }
    }
  }

  private fun startLocalProxyListener() {
    acceptJob =
      scope.launch(Dispatchers.IO) {
        try {
          val server = ServerSocket()
          server.reuseAddress = true
          server.bind(InetSocketAddress("127.0.0.1", localPort))
          serverSocket = server
          emitMetrics()

          while (isActive && running) {
            val clientSocket =
              try {
                server.accept()
              } catch (_: IOException) {
                break
              }
            launch(Dispatchers.IO) { handleLocalClient(clientSocket) }
          }
        } catch (e: Exception) {
          lastError = e.localizedMessage ?: "Failed to bind local port $localPort"
          running = false
          emitMetrics()
        }
      }
  }

  private suspend fun handleLocalClient(clientSocket: Socket) {
    val streamId = streamIdGen.getAndIncrement()
    try {
      clientSocket.soTimeout = 30_000
      val clientIn = BufferedInputStream(clientSocket.getInputStream())
      val clientOut = clientSocket.getOutputStream()

      val headerBlock = readHttpHeaderBlock(clientIn) ?: run {
        runCatching { clientSocket.close() }
        return
      }
      val lines = headerBlock.split("\r\n").filter { it.isNotEmpty() }
      if (lines.isEmpty()) {
        runCatching { clientSocket.close() }
        return
      }

      val requestLine = lines.first()
      val parts = requestLine.split(" ")
      if (parts.size < 3) {
        runCatching { clientSocket.close() }
        return
      }

      val method = parts[0].uppercase()
      val target = parts[1]
      val version = parts[2]
      val headers = lines.drop(1)

      val ws = webSocket
      if (ws == null || !relayConnected) {
        val err = "HTTP/1.1 503 Relay Not Connected\r\nConnection: close\r\n\r\n"
        clientOut.write(err.toByteArray(Charsets.US_ASCII))
        clientOut.flush()
        runCatching { clientSocket.close() }
        return
      }
      if (!peerOnline) {
        val err = "HTTP/1.1 502 Host Offline\r\nConnection: close\r\n\r\n"
        clientOut.write(err.toByteArray(Charsets.US_ASCII))
        clientOut.flush()
        runCatching { clientSocket.close() }
        return
      }

      val targetHostPort: String
      val initialPayload: ByteArray?
      if (method == "CONNECT") {
        targetHostPort = target
        initialPayload = null
      } else {
        val uri = runCatching { URI(target) }.getOrNull()
        val hostHeader =
          headers.firstOrNull { it.startsWith("Host:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()
        val host = uri?.host ?: hostHeader?.substringBefore(":") ?: run {
          runCatching { clientSocket.close() }
          return
        }
        val port =
          when {
            uri != null && uri.port > 0 -> uri.port
            hostHeader?.contains(":") == true -> hostHeader.substringAfter(":").toIntOrNull() ?: 80
            else -> 80
          }
        targetHostPort = "$host:$port"
        val path =
          buildString {
            val rawPath = uri?.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
            append(rawPath)
            if (!uri?.rawQuery.isNullOrEmpty()) append("?").append(uri?.rawQuery)
          }
        val rewritten = StringBuilder()
        rewritten.append("$method $path $version\r\n")
        val filtered =
          headers.filterNot { it.startsWith("Proxy-Connection:", ignoreCase = true) }
        if (filtered.none { it.startsWith("Host:", ignoreCase = true) }) {
          rewritten.append("Host: $host\r\n")
        }
        filtered.forEach { rewritten.append(it).append("\r\n") }
        rewritten.append("Connection: close\r\n\r\n")
        initialPayload = rewritten.toString().toByteArray(Charsets.ISO_8859_1)
      }

      val deferred = CompletableDeferred<Result<Unit>>()
      pendingOpens[streamId] = deferred
      val enqueued =
        ws.send(
          RelayProtocol.encodeFrame(
            streamId,
            RelayFrameType.OPEN,
            targetHostPort.toByteArray(Charsets.UTF_8),
          )
        )
      if (!enqueued) {
        pendingOpens.remove(streamId)
        val err = "HTTP/1.1 503 Relay Send Failed\r\nConnection: close\r\n\r\n"
        runCatching {
          clientOut.write(err.toByteArray(Charsets.US_ASCII))
          clientOut.flush()
          clientSocket.close()
        }
        return
      }

      val openResult =
        try {
          withTimeout(12_000) { deferred.await() }
        } catch (e: Exception) {
          pendingOpens.remove(streamId)
          Result.failure(e)
        }

      if (openResult.isFailure) {
        val err = "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n"
        runCatching {
          clientOut.write(err.toByteArray(Charsets.US_ASCII))
          clientOut.flush()
          clientSocket.close()
        }
        return
      }

      val writeChan = Channel<ByteArray>(capacity = 64)
      sessions[streamId] = LocalClientSession(clientSocket, writeChan)
      activeCount.incrementAndGet()
      emitMetrics()

      if (method == "CONNECT") {
        val ok = "$version 200 Connection Established\r\n\r\n"
        clientOut.write(ok.toByteArray(Charsets.US_ASCII))
        clientOut.flush()
      } else if (initialPayload != null) {
        totalBytes.addAndGet(initialPayload.size.toLong())
        ws.send(RelayProtocol.encodeFrame(streamId, RelayFrameType.DATA, initialPayload))
      }

      // Writer: Host -> Local Client
      val writerJob =
        scope.launch(Dispatchers.IO) {
          try {
            for (chunk in writeChan) {
              clientOut.write(chunk)
              clientOut.flush()
            }
          } catch (_: Exception) {
            // Closed
          } finally {
            closeSession(streamId, notifyPeer = true)
          }
        }

      // Reader: Local Client -> Host
      val buf = ByteArray(16 * 1024)
      try {
        while (running && sessions.containsKey(streamId)) {
          val activeWs = webSocket ?: break
          while (activeWs.queueSize() > 2 * 1024 * 1024 && running) {
            delay(15)
          }
          val read = clientIn.read(buf)
          if (read <= 0) break
          val slice = buf.copyOf(read)
          totalBytes.addAndGet(read.toLong())
          activeWs.send(RelayProtocol.encodeFrame(streamId, RelayFrameType.DATA, slice))
          if (totalBytes.get() % (64 * 1024) < read) {
            emitMetrics()
          }
        }
      } catch (_: IOException) {
        // Closed
      } finally {
        writerJob.cancel()
        closeSession(streamId, notifyPeer = true)
      }
    } catch (_: Exception) {
      closeSession(streamId, notifyPeer = true)
    }
  }

  private fun readHttpHeaderBlock(input: BufferedInputStream): String? {
    val bytes = ArrayList<Byte>(512)
    var prev3 = 0
    var prev2 = 0
    var prev1 = 0
    while (bytes.size < 16384) {
      val b = input.read()
      if (b == -1) return null
      bytes.add(b.toByte())
      if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
        return bytes.toByteArray().toString(Charsets.ISO_8859_1)
      }
      prev3 = prev2
      prev2 = prev1
      prev1 = b
    }
    return null
  }

  private fun closeSession(streamId: Int, notifyPeer: Boolean) {
    pendingOpens.remove(streamId)?.cancel()
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
    pendingOpens.keys.toList().forEach { pendingOpens.remove(it)?.cancel() }
    sessions.keys.toList().forEach { closeSession(it, notifyPeer = false) }
  }

  private fun emitMetrics() {
    onMetricsChanged(
      ProxyMetricsSnapshot(
        isRunning = running,
        port = localPort,
        activeConnections = activeCount.get().coerceAtLeast(0),
        totalBytesRelayed = totalBytes.get(),
        clientIps = if (peerOnline) listOf("Host Room $roomCode") else emptyList(),
        lastError = lastError,
        relayConnected = relayConnected,
        peerOnline = peerOnline,
        cellularBound = cellularManager?.bindingState?.value?.isCellularBound == true,
      )
    )
  }
}
