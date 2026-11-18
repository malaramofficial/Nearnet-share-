package com.example.network

import android.util.Base64
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ProxyMetricsSnapshot(
  val isRunning: Boolean,
  val port: Int,
  val activeConnections: Int,
  val totalBytesRelayed: Long,
  val clientIps: List<String>,
  val lastError: String?,
  val relayConnected: Boolean = false,
  val peerOnline: Boolean = false,
)

class ProxyBridgeServer(
  private val port: Int,
  private val requiredPin: String?,
  private val scope: CoroutineScope,
  private val onMetricsChanged: (ProxyMetricsSnapshot) -> Unit,
) {
  private var serverSocket: ServerSocket? = null
  private var acceptJob: Job? = null

  private val activeConnections = AtomicInteger(0)
  private val totalBytes = AtomicLong(0L)
  private val recentClients = Collections.synchronizedSet(LinkedHashSet<String>())

  @Volatile private var running = false
  @Volatile private var lastError: String? = null

  fun start() {
    if (running) return
    running = true
    lastError = null

    acceptJob =
      scope.launch(Dispatchers.IO) {
        try {
          val server = ServerSocket()
          server.reuseAddress = true
          server.bind(InetSocketAddress("0.0.0.0", port))
          serverSocket = server
          emitMetrics()

          while (isActive && running) {
            val clientSocket =
              try {
                server.accept()
              } catch (_: IOException) {
                break
              }
            launch(Dispatchers.IO) { handleClient(clientSocket) }
          }
        } catch (e: Exception) {
          lastError = e.localizedMessage ?: "Failed to bind port $port"
          running = false
          emitMetrics()
        }
      }
  }

  fun stop() {
    running = false
    runCatching { serverSocket?.close() }
    serverSocket = null
    acceptJob?.cancel()
    acceptJob = null
    activeConnections.set(0)
    emitMetrics()
  }

  private suspend fun handleClient(clientSocket: Socket) {
    val clientIp = clientSocket.inetAddress?.hostAddress ?: "unknown"
    synchronized(recentClients) {
      if (recentClients.size > 12) {
        recentClients.clear()
      }
      recentClients.add(clientIp)
    }
    activeConnections.incrementAndGet()
    emitMetrics()

    try {
      clientSocket.soTimeout = 30_000
      val clientIn = BufferedInputStream(clientSocket.getInputStream())
      val clientOut = clientSocket.getOutputStream()

      val headerBlock = readHttpHeaderBlock(clientIn) ?: return
      val lines = headerBlock.split("\r\n").filter { it.isNotEmpty() }
      if (lines.isEmpty()) return

      val requestLine = lines.first()
      val parts = requestLine.split(" ")
      if (parts.size < 3) return

      val method = parts[0].uppercase()
      val target = parts[1]
      val version = parts[2]
      val headers = lines.drop(1)

      if (!requiredPin.isNullOrEmpty() && !isAuthorized(headers, requiredPin)) {
        val resp =
          "HTTP/1.1 407 Proxy Authentication Required\r\n" +
            "Proxy-Authenticate: Basic realm=\"NetSetu\"\r\n" +
            "Content-Length: 0\r\n" +
            "Connection: close\r\n\r\n"
        clientOut.write(resp.toByteArray(Charsets.US_ASCII))
        clientOut.flush()
        return
      }

      if (method == "CONNECT") {
        handleConnectTunnel(target, version, clientIn, clientOut)
      } else {
        handlePlainHttp(method, target, version, headers, clientIn, clientOut)
      }
    } catch (_: Exception) {
      // Client closed or timed out cleanly
    } finally {
      runCatching { clientSocket.close() }
      activeConnections.decrementAndGet().coerceAtLeast(0)
      emitMetrics()
    }
  }

  private suspend fun handleConnectTunnel(
    target: String,
    version: String,
    clientIn: InputStream,
    clientOut: OutputStream,
  ) {
    val hostPort = target.split(":")
    val host = hostPort[0]
    val targetPort = hostPort.getOrNull(1)?.toIntOrNull() ?: 443

    val upstream = Socket()
    try {
      upstream.connect(InetSocketAddress(host, targetPort), 12_000)
      upstream.soTimeout = 60_000
      val okResponse = "$version 200 Connection Established\r\n\r\n"
      clientOut.write(okResponse.toByteArray(Charsets.US_ASCII))
      clientOut.flush()

      val upstreamIn = upstream.getInputStream()
      val upstreamOut = upstream.getOutputStream()

      coroutineScope {
        val up = async(Dispatchers.IO) { relayStream(clientIn, upstreamOut) }
        val down = async(Dispatchers.IO) { relayStream(upstreamIn, clientOut) }
        up.await()
        down.await()
      }
    } catch (_: Exception) {
      val failResp = "$version 502 Bad Gateway\r\nConnection: close\r\n\r\n"
      runCatching {
        clientOut.write(failResp.toByteArray(Charsets.US_ASCII))
        clientOut.flush()
      }
    } finally {
      runCatching { upstream.close() }
    }
  }

  private suspend fun handlePlainHttp(
    method: String,
    target: String,
    version: String,
    headers: List<String>,
    clientIn: InputStream,
    clientOut: OutputStream,
  ) {
    val uri = runCatching { URI(target) }.getOrNull()
    val hostHeader = headers.firstOrNull { it.startsWith("Host:", ignoreCase = true) }
      ?.substringAfter(":")?.trim()

    val host = uri?.host ?: hostHeader?.substringBefore(":") ?: return
    val targetPort =
      when {
        uri != null && uri.port > 0 -> uri.port
        hostHeader?.contains(":") == true -> hostHeader.substringAfter(":").toIntOrNull() ?: 80
        else -> 80
      }

    val path =
      buildString {
        val rawPath = uri?.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        append(rawPath)
        if (!uri?.rawQuery.isNullOrEmpty()) {
          append("?").append(uri?.rawQuery)
        }
      }

    val upstream = Socket()
    try {
      upstream.connect(InetSocketAddress(host, targetPort), 12_000)
      upstream.soTimeout = 30_000
      val upstreamOut = upstream.getOutputStream()
      val upstreamIn = upstream.getInputStream()

      val filteredHeaders =
        headers.filterNot {
          it.startsWith("Proxy-Connection:", ignoreCase = true) ||
            it.startsWith("Proxy-Authorization:", ignoreCase = true)
        }

      val requestBuilder = StringBuilder()
      requestBuilder.append("$method $path $version\r\n")
      if (filteredHeaders.none { it.startsWith("Host:", ignoreCase = true) }) {
        requestBuilder.append("Host: $host\r\n")
      }
      for (h in filteredHeaders) {
        requestBuilder.append(h).append("\r\n")
      }
      requestBuilder.append("Connection: close\r\n\r\n")

      val headerBytes = requestBuilder.toString().toByteArray(Charsets.ISO_8859_1)
      upstreamOut.write(headerBytes)
      upstreamOut.flush()
      totalBytes.addAndGet(headerBytes.size.toLong())

      coroutineScope {
        val up = async(Dispatchers.IO) { relayStream(clientIn, upstreamOut) }
        val down = async(Dispatchers.IO) { relayStream(upstreamIn, clientOut) }
        down.await()
        up.cancel()
      }
    } catch (_: Exception) {
      val failResp = "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n"
      runCatching {
        clientOut.write(failResp.toByteArray(Charsets.US_ASCII))
        clientOut.flush()
      }
    } finally {
      runCatching { upstream.close() }
    }
  }

  private fun relayStream(input: InputStream, output: OutputStream) {
    val buffer = ByteArray(16 * 1024)
    try {
      while (running) {
        val read = input.read(buffer)
        if (read <= 0) break
        output.write(buffer, 0, read)
        output.flush()
        val total = totalBytes.addAndGet(read.toLong())
        if (total % (64 * 1024) < read) {
          emitMetrics()
        }
      }
    } catch (_: IOException) {
      // Stream finished
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

  private fun isAuthorized(headers: List<String>, pin: String): Boolean {
    val authHeader =
      headers.firstOrNull { it.startsWith("Proxy-Authorization:", ignoreCase = true) }
        ?: return false
    val token = authHeader.substringAfter("Basic ", "").trim()
    if (token.isEmpty()) return false
    val decoded =
      runCatching { String(Base64.decode(token, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
        ?: return false
    val providedSecret = decoded.substringAfter(":", decoded)
    return providedSecret == pin
  }

  private fun emitMetrics() {
    val clientsCopy = synchronized(recentClients) { recentClients.toList() }
    onMetricsChanged(
      ProxyMetricsSnapshot(
        isRunning = running,
        port = port,
        activeConnections = activeConnections.get().coerceAtLeast(0),
        totalBytesRelayed = totalBytes.get(),
        clientIps = clientsCopy,
        lastError = lastError,
      )
    )
  }
}
