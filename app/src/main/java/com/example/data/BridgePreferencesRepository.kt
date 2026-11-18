package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.i18n.AppLanguage
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "netsetu_prefs")

data class BridgeSettings(
  val language: AppLanguage = AppLanguage.HINDI,
  val useRelayMode: Boolean = true,
  val relayUrl: String = "",
  val roomCode: String = "482915",
  val proxyPort: Int = 8888,
  val requirePin: Boolean = false,
  val proxyPin: String = "4820",
  val testTargetHost: String = "",
  val testTargetPort: Int = 8888,
)

class BridgePreferencesRepository(private val context: Context) {

  private object Keys {
    val LANGUAGE = stringPreferencesKey("language")
    val USE_RELAY_MODE = booleanPreferencesKey("use_relay_mode")
    val RELAY_URL = stringPreferencesKey("relay_url")
    val ROOM_CODE = stringPreferencesKey("room_code")
    val PROXY_PORT = intPreferencesKey("proxy_port")
    val REQUIRE_PIN = booleanPreferencesKey("require_pin")
    val PROXY_PIN = stringPreferencesKey("proxy_pin")
    val TEST_TARGET_HOST = stringPreferencesKey("test_target_host")
    val TEST_TARGET_PORT = intPreferencesKey("test_target_port")
  }

  val settingsFlow: Flow<BridgeSettings> =
    context.dataStore.data.map { prefs ->
      val lang =
        when (prefs[Keys.LANGUAGE]) {
          AppLanguage.ENGLISH.name -> AppLanguage.ENGLISH
          else -> AppLanguage.HINDI
        }
      BridgeSettings(
        language = lang,
        useRelayMode = prefs[Keys.USE_RELAY_MODE] ?: true,
        relayUrl = prefs[Keys.RELAY_URL] ?: "",
        roomCode = prefs[Keys.ROOM_CODE] ?: generateRoomCode(),
        proxyPort = prefs[Keys.PROXY_PORT] ?: 8888,
        requirePin = prefs[Keys.REQUIRE_PIN] ?: false,
        proxyPin = prefs[Keys.PROXY_PIN] ?: "4820",
        testTargetHost = prefs[Keys.TEST_TARGET_HOST] ?: "",
        testTargetPort = prefs[Keys.TEST_TARGET_PORT] ?: 8888,
      )
    }

  suspend fun ensureRoomCodeExists() {
    context.dataStore.edit { prefs ->
      if (prefs[Keys.ROOM_CODE].isNullOrBlank()) {
        prefs[Keys.ROOM_CODE] = generateRoomCode()
      }
    }
  }

  suspend fun toggleLanguage() {
    context.dataStore.edit { prefs ->
      val current = prefs[Keys.LANGUAGE]
      prefs[Keys.LANGUAGE] =
        if (current == AppLanguage.ENGLISH.name) AppLanguage.HINDI.name else AppLanguage.ENGLISH.name
    }
  }

  suspend fun updateRelayConfig(useRelayMode: Boolean, relayUrl: String, roomCode: String) {
    context.dataStore.edit { prefs ->
      prefs[Keys.USE_RELAY_MODE] = useRelayMode
      prefs[Keys.RELAY_URL] = relayUrl.trim()
      if (roomCode.isNotBlank()) {
        prefs[Keys.ROOM_CODE] = roomCode.trim()
      }
    }
  }

  suspend fun regenerateRoomCode(): String {
    val next = generateRoomCode()
    context.dataStore.edit { prefs -> prefs[Keys.ROOM_CODE] = next }
    return next
  }

  suspend fun updateProxyConfig(port: Int, requirePin: Boolean, pin: String) {
    context.dataStore.edit { prefs ->
      prefs[Keys.PROXY_PORT] = port.coerceIn(1024, 65535)
      prefs[Keys.REQUIRE_PIN] = requirePin
      prefs[Keys.PROXY_PIN] = pin.take(8)
    }
  }

  suspend fun updateTestTarget(host: String, port: Int) {
    context.dataStore.edit { prefs ->
      prefs[Keys.TEST_TARGET_HOST] = host.trim()
      prefs[Keys.TEST_TARGET_PORT] = port.coerceIn(1, 65535)
    }
  }

  private fun generateRoomCode(): String = Random.nextInt(100000, 999999).toString()
}
