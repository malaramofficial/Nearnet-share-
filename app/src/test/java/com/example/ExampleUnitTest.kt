package com.example

import com.example.network.BridgeCodeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleUnitTest {

  @Test
  fun `bridge code encodes and decodes with pin`() {
    val code = BridgeCodeCodec.encode("100.88.14.21", 8888, "4820")
    val decoded = BridgeCodeCodec.decode(code)
    assertNotNull(decoded)
    assertEquals("100.88.14.21", decoded?.host)
    assertEquals(8888, decoded?.port)
    assertEquals("4820", decoded?.pin)
  }

  @Test
  fun `bridge code decodes plain ip and port fallback`() {
    val decoded = BridgeCodeCodec.decode("100.70.5.9:9090")
    assertNotNull(decoded)
    assertEquals("100.70.5.9", decoded?.host)
    assertEquals(9090, decoded?.port)
    assertEquals("", decoded?.pin)
  }

  @Test
  fun `invalid bridge code returns null`() {
    assertNull(BridgeCodeCodec.decode("not-a-valid-code"))
  }
}
