package com.example.network

import android.util.Base64

sealed interface DecodedBridgePayload {
  data class Direct(
    val host: String,
    val port: Int,
    val pin: String,
  ) : DecodedBridgePayload

  data class Relay(
    val relayUrl: String,
    val roomCode: String,
    val pin: String,
  ) : DecodedBridgePayload
}

object BridgeCodeCodec {
  private const val DIRECT_PREFIX = "NS-"
  private const val RELAY_PREFIX = "NSR-"

  fun encodeDirect(host: String, port: Int, pin: String?): String {
    val cleanHost = host.trim()
    val cleanPin = pin?.trim().orEmpty()
    val payload = "$cleanHost|$port|$cleanPin"
    val encoded =
      Base64.encodeToString(
        payload.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
      )
    return "$DIRECT_PREFIX$encoded"
  }

  fun encodeRelay(relayUrl: String, roomCode: String, pin: String?): String {
    val cleanUrl = relayUrl.trim()
    val cleanRoom = roomCode.trim()
    val cleanPin = pin?.trim().orEmpty()
    val payload = "$cleanUrl|$cleanRoom|$cleanPin"
    val encoded =
      Base64.encodeToString(
        payload.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
      )
    return "$RELAY_PREFIX$encoded"
  }

  fun decode(rawInput: String): DecodedBridgePayload? {
    val trimmed = rawInput.trim()
    if (trimmed.isEmpty()) return null

    if (trimmed.startsWith(RELAY_PREFIX, ignoreCase = true)) {
      val token = trimmed.substring(RELAY_PREFIX.length).trim()
      val decoded = decodeBase64Url(token) ?: return null
      val parts = decoded.split("|")
      if (parts.size < 2) return null
      val url = parts[0].trim()
      val room = parts[1].trim()
      val pin = parts.getOrNull(2)?.trim().orEmpty()
      if (url.isBlank() || room.isBlank()) return null
      return DecodedBridgePayload.Relay(relayUrl = url, roomCode = room, pin = pin)
    }

    if (trimmed.startsWith(DIRECT_PREFIX, ignoreCase = true)) {
      val token = trimmed.substring(DIRECT_PREFIX.length).trim()
      val decoded = decodeBase64Url(token) ?: return null
      val parts = decoded.split("|")
      if (parts.size < 2) return null
      val host = parts[0].trim()
      val port = parts[1].trim().toIntOrNull() ?: return null
      val pin = parts.getOrNull(2)?.trim().orEmpty()
      if (host.isBlank() || port !in 1..65535) return null
      return DecodedBridgePayload.Direct(host = host, port = port, pin = pin)
    }

    if (trimmed.contains(":")) {
      val host = trimmed.substringBeforeLast(":").trim()
      val port = trimmed.substringAfterLast(":").trim().toIntOrNull() ?: return null
      if (host.isNotBlank() && port in 1..65535) {
        return DecodedBridgePayload.Direct(host = host, port = port, pin = "")
      }
    }
    return null
  }

  private fun decodeBase64Url(token: String): String? =
    runCatching {
      String(
        Base64.decode(token, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
        Charsets.UTF_8,
      )
    }.getOrNull()
}
