package com.example.network

import android.util.Base64
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface TunnelTestResult {
  data object Idle : TunnelTestResult

  data object Testing : TunnelTestResult

  data class Success(val latencyMs: Long, val httpCode: Int) : TunnelTestResult

  data class Failure(val reason: String) : TunnelTestResult
}

object ProxyConnectivityTester {

  private const val TARGET_HOST = "connectivitycheck.gstatic.com"
  private const val TARGET_PORT = 443

  suspend fun testProxyTunnel(
    host: String,
    port: Int,
    pin: String?,
  ): TunnelTestResult =
    withContext(Dispatchers.IO) {
      val cleanHost = host.trim()
      if (cleanHost.isBlank() || port !in 1..65535) {
        return@withContext TunnelTestResult.Failure("Invalid host IP or port")
      }

      val start = System.currentTimeMillis()
      val rawSocket = Socket()
      var sslSocket: SSLSocket? = null

      try {
        rawSocket.connect(InetSocketAddress(cleanHost, port), 7000)
        rawSocket.soTimeout = 7000

        val rawOut = rawSocket.getOutputStream()
        val rawIn = rawSocket.getInputStream()

        val connectReq = buildString {
          append("CONNECT $TARGET_HOST:$TARGET_PORT HTTP/1.1\r\n")
          append("Host: $TARGET_HOST:$TARGET_PORT\r\n")
          if (!pin.isNullOrBlank()) {
            val encoded =
              Base64.encodeToString("friend:$pin".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            append("Proxy-Authorization: Basic $encoded\r\n")
          }
          append("\r\n")
        }
        rawOut.write(connectReq.toByteArray(Charsets.US_ASCII))
        rawOut.flush()

        val connectHeaders = readHeadersExact(rawIn)
          ?: return@withContext TunnelTestResult.Failure("Proxy closed connection")
        val connectStatusLine = connectHeaders.lineSequence().firstOrNull().orEmpty()
        val connectCode = connectStatusLine.split(" ").getOrNull(1)?.toIntOrNull()

        if (connectCode == 407) {
          return@withContext TunnelTestResult.Failure("PIN required or incorrect (HTTP 407)")
        }
        if (connectCode != 200) {
          return@withContext TunnelTestResult.Failure(
            connectStatusLine.ifBlank { "Proxy tunnel rejected ($connectCode)" }
          )
        }

        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        sslSocket =
          (factory.createSocket(rawSocket, TARGET_HOST, TARGET_PORT, true) as SSLSocket).apply {
            soTimeout = 7000
            startHandshake()
          }

        val tlsOut = sslSocket.getOutputStream()
        val tlsIn = sslSocket.getInputStream()
        val httpReq =
          "GET /generate_204 HTTP/1.1\r\n" +
            "Host: $TARGET_HOST\r\n" +
            "Connection: close\r\n\r\n"
        tlsOut.write(httpReq.toByteArray(Charsets.US_ASCII))
        tlsOut.flush()

        val respHeaders = readHeadersExact(tlsIn)
          ?: return@withContext TunnelTestResult.Failure("Empty HTTPS response from target")
        val statusLine = respHeaders.lineSequence().firstOrNull().orEmpty()
        val httpCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
        val elapsed = System.currentTimeMillis() - start

        if (httpCode in 200..399) {
          TunnelTestResult.Success(latencyMs = elapsed, httpCode = httpCode)
        } else {
          TunnelTestResult.Failure("HTTPS $httpCode (${elapsed}ms)")
        }
      } catch (e: Exception) {
        TunnelTestResult.Failure(e.localizedMessage ?: "Connection timed out")
      } finally {
        runCatching { sslSocket?.close() }
        runCatching { rawSocket.close() }
      }
    }

  private fun readHeadersExact(input: InputStream): String? {
    val buf = ArrayList<Byte>(256)
    var b3 = 0
    var b2 = 0
    var b1 = 0
    while (buf.size < 8192) {
      val b = input.read()
      if (b == -1) return null
      buf.add(b.toByte())
      if (b3 == '\r'.code && b2 == '\n'.code && b1 == '\r'.code && b == '\n'.code) {
        return buf.toByteArray().toString(Charsets.ISO_8859_1)
      }
      b3 = b2
      b2 = b1
      b1 = b
    }
    return null
  }
}
