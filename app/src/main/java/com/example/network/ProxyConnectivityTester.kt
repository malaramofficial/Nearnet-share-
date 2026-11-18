package com.example.network

import android.util.Base64
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface TunnelTestResult {
  data object Idle : TunnelTestResult

  data object Testing : TunnelTestResult

  data class Success(val latencyMs: Long, val httpCode: Int) : TunnelTestResult

  data class Failure(val reason: String) : TunnelTestResult
}

object ProxyConnectivityTester {

  suspend fun testProxyTunnel(
    host: String,
    port: Int,
    pin: String?,
  ): TunnelTestResult =
    withContext(Dispatchers.IO) {
      if (host.isBlank() || port !in 1..65535) {
        return@withContext TunnelTestResult.Failure("Invalid host IP or port")
      }
      val start = System.currentTimeMillis()
      var conn: HttpURLConnection? = null
      try {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(host.trim(), port))
        val url = URL("http://connectivitycheck.gstatic.com/generate_204")
        conn = (url.openConnection(proxy) as HttpURLConnection).apply {
          connectTimeout = 7000
          readTimeout = 7000
          instanceFollowRedirects = false
          useCaches = false
          if (!pin.isNullOrBlank()) {
            val encoded =
              Base64.encodeToString("friend:$pin".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            setRequestProperty("Proxy-Authorization", "Basic $encoded")
          }
        }
        val code = conn.responseCode
        val elapsed = System.currentTimeMillis() - start
        if (code in 200..399) {
          TunnelTestResult.Success(latencyMs = elapsed, httpCode = code)
        } else {
          TunnelTestResult.Failure("HTTP $code (${elapsed}ms)")
        }
      } catch (e: Exception) {
        TunnelTestResult.Failure(e.localizedMessage ?: "Connection timed out")
      } finally {
        conn?.disconnect()
      }
    }
}
