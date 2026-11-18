package com.example.network

import java.net.URLEncoder
import java.nio.ByteBuffer
import okio.ByteString
import okio.ByteString.Companion.toByteString

enum class RelayFrameType(val code: Byte) {
  OPEN(1),
  DATA(2),
  CLOSE(3),
  OPEN_OK(4),
  OPEN_ERR(5);

  companion object {
    fun fromCode(code: Byte): RelayFrameType? = entries.firstOrNull { it.code == code }
  }
}

data class RelayFrame(
  val streamId: Int,
  val type: RelayFrameType,
  val payload: ByteArray,
)

object RelayProtocol {

  fun encodeFrame(streamId: Int, type: RelayFrameType, payload: ByteArray = ByteArray(0)): ByteString {
    val buffer = ByteBuffer.allocate(5 + payload.size)
    buffer.putInt(streamId)
    buffer.put(type.code)
    if (payload.isNotEmpty()) {
      buffer.put(payload)
    }
    return buffer.array().toByteString()
  }

  fun decodeFrame(bytes: ByteString): RelayFrame? {
    if (bytes.size < 5) return null
    val raw = bytes.toByteArray()
    val buffer = ByteBuffer.wrap(raw)
    val streamId = buffer.getInt()
    val type = RelayFrameType.fromCode(buffer.get()) ?: return null
    val payload = ByteArray(raw.size - 5)
    buffer.get(payload)
    return RelayFrame(streamId = streamId, type = type, payload = payload)
  }

  fun buildWebSocketUrl(
    baseUrl: String,
    role: String,
    roomCode: String,
    pin: String?,
  ): String? {
    val trimmed = baseUrl.trim().trimEnd('/')
    if (trimmed.isBlank() || roomCode.isBlank()) return null

    val wsBase =
      when {
        trimmed.startsWith("wss://", ignoreCase = true) -> trimmed
        trimmed.startsWith("ws://", ignoreCase = true) -> trimmed
        trimmed.startsWith("https://", ignoreCase = true) -> "wss://" + trimmed.substring(8)
        trimmed.startsWith("http://", ignoreCase = true) -> "ws://" + trimmed.substring(7)
        else -> "wss://$trimmed"
      }

    val cleanBase = if (wsBase.endsWith("/ws")) wsBase else "$wsBase/ws"
    val encRoom = URLEncoder.encode(roomCode.trim(), "UTF-8")
    val encPin = URLEncoder.encode(pin?.trim().orEmpty(), "UTF-8")
    return "$cleanBase?role=$role&room=$encRoom&pin=$encPin"
  }
}
