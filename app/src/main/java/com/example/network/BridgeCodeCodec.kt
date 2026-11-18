package com.example.network

import android.util.Base64

data class DecodedBridgeConfig(
  val host: String,
  val port: Int,
  val pin: String,
)

object BridgeCodeCodec {
  private const val PREFIX = "NS-"

  fun encode(host: String, port: Int, pin: String?): String {
    val cleanHost = host.trim()
    val cleanPin = pin?.trim().orEmpty()
    val payload = "$cleanHost|$port|$cleanPin"
    val encoded =
      Base64.encodeToString(
        payload.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
      )
    return "$PREFIX$encoded"
  }

  fun decode(rawInput: String): DecodedBridgeConfig? {
    val trimmed = rawInput.trim()
    if (trimmed.isEmpty()) return null

    if (trimmed.startsWith(PREFIX, ignoreCase = true)) {
      val token = trimmed.substring(PREFIX.length).trim()
      val decoded =
        runCatching {
            String(
              Base64.decode(token, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
              Charsets.UTF_8,
            )
          }
          .getOrNull() ?: return null
      val parts = decoded.split("|")
      if (parts.size < 2) return null
      val host = parts[0].trim()
      val port = parts[1].trim().toIntOrNull() ?: return null
      val pin = parts.getOrNull(2)?.trim().orEmpty()
      if (host.isBlank() || port !in 1..65535) return null
      return DecodedBridgeConfig(host = host, port = port, pin = pin)
    }

    // Fallback: also accept plain "IP:PORT" pasted by user
    if (trimmed.contains(":")) {
      val host = trimmed.substringBeforeLast(":").trim()
      val port = trimmed.substringAfterLast(":").trim().toIntOrNull() ?: return null
      if (host.isNotBlank() && port in 1..65535) {
        return DecodedBridgeConfig(host = host, port = port, pin = "")
      }
    }
    return null
  }
}
