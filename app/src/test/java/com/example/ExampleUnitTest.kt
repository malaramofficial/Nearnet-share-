package com.example

import com.example.network.BridgeCodeCodec
import com.example.network.DecodedBridgePayload
import com.example.network.RelayFrameType
import com.example.network.RelayProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleUnitTest {

  @Test
  fun `direct bridge code encodes and decodes with pin`() {
    val code = BridgeCodeCodec.encodeDirect("192.168.1.10", 8888, "4820")
    val decoded = BridgeCodeCodec.decode(code) as? DecodedBridgePayload.Direct
    assertNotNull(decoded)
    assertEquals("192.168.1.10", decoded?.host)
    assertEquals(8888, decoded?.port)
    assertEquals("4820", decoded?.pin)
  }

  @Test
  fun `relay bridge code encodes and decodes url room and pin`() {
    val code = BridgeCodeCodec.encodeRelay("https://netsetu-relay.onrender.com", "482915", "9911")
    val decoded = BridgeCodeCodec.decode(code) as? DecodedBridgePayload.Relay
    assertNotNull(decoded)
    assertEquals("https://netsetu-relay.onrender.com", decoded?.relayUrl)
    assertEquals("482915", decoded?.roomCode)
    assertEquals("9911", decoded?.pin)
  }

  @Test
  fun `bridge code decodes plain ip and port fallback`() {
    val decoded = BridgeCodeCodec.decode("192.168.43.1:9090") as? DecodedBridgePayload.Direct
    assertNotNull(decoded)
    assertEquals("192.168.43.1", decoded?.host)
    assertEquals(9090, decoded?.port)
    assertEquals("", decoded?.pin)
  }

  @Test
  fun `invalid bridge code returns null`() {
    assertNull(BridgeCodeCodec.decode("not-a-valid-code"))
  }

  @Test
  fun `relay protocol roundtrips binary frames`() {
    val payload = "connectivitycheck.gstatic.com:443".toByteArray(Charsets.UTF_8)
    val encoded = RelayProtocol.encodeFrame(42, RelayFrameType.OPEN, payload)
    val decoded = RelayProtocol.decodeFrame(encoded)
    assertNotNull(decoded)
    assertEquals(42, decoded?.streamId)
    assertEquals(RelayFrameType.OPEN, decoded?.type)
    assertArrayEquals(payload, decoded?.payload)
  }

  @Test
  fun `relay url normalizes https to wss`() {
    val ws =
      RelayProtocol.buildWebSocketUrl(
        baseUrl = "https://my-relay.onrender.com/",
        role = "host",
        roomCode = "482915",
        pin = "1234",
      )
    assertNotNull(ws)
    assertTrue(ws!!.startsWith("wss://my-relay.onrender.com/ws?"))
    assertTrue(ws.contains("role=host"))
    assertTrue(ws.contains("room=482915"))
  }
}
