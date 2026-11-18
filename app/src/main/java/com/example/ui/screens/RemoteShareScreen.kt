package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.i18n.UiStrings
import com.example.network.BridgeCodeCodec
import com.example.network.DecodedBridgePayload
import com.example.network.InterfaceKind
import com.example.network.TunnelTestResult
import com.example.service.ProxyBridgeService
import com.example.service.ServiceBridgeMode
import com.example.ui.components.BridgeHeroCard
import com.example.ui.components.CopyableIpRow
import com.example.ui.components.SectionCard
import com.example.ui.components.SectionHeader
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalSignalColors
import com.example.viewmodel.MainUiState
import com.example.viewmodel.MainViewModel

private enum class RoleMode {
  HOST,
  FRIEND,
}

@Composable
fun RemoteShareScreen(
  uiState: MainUiState,
  strings: UiStrings,
  viewModel: MainViewModel,
  contentPadding: PaddingValues = PaddingValues(16.dp),
) {
  var selectedRole by rememberSaveable { mutableStateOf(RoleMode.HOST) }
  val metrics = uiState.proxyMetrics
  val settings = uiState.settings

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = contentPadding,
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    item {
      BridgeHeroCard(
        isBridgeActive = metrics.isRunning,
        port = if (metrics.isRunning && metrics.port > 0) metrics.port else settings.proxyPort,
        upstreamStatus = uiState.upstreamStatus,
        strings = strings,
      )
    }

    item {
      SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
          selected = selectedRole == RoleMode.HOST,
          onClick = { selectedRole = RoleMode.HOST },
          shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
          modifier = Modifier.minimumInteractiveComponentSize().testTag("mode_host_btn"),
        ) {
          Text(strings.modeHost, maxLines = 1)
        }
        SegmentedButton(
          selected = selectedRole == RoleMode.FRIEND,
          onClick = { selectedRole = RoleMode.FRIEND },
          shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
          modifier = Modifier.minimumInteractiveComponentSize().testTag("mode_friend_btn"),
        ) {
          Text(strings.modeFriend, maxLines = 1)
        }
      }
    }

    if (selectedRole == RoleMode.HOST) {
      item {
        HostRoleContent(
          uiState = uiState,
          strings = strings,
          viewModel = viewModel,
        )
      }
    } else {
      item {
        FriendRoleContent(
          uiState = uiState,
          strings = strings,
          viewModel = viewModel,
        )
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HostRoleContent(
  uiState: MainUiState,
  strings: UiStrings,
  viewModel: MainViewModel,
) {
  val context = LocalContext.current
  val signalColors = LocalSignalColors.current
  val settings = uiState.settings
  val metrics = uiState.proxyMetrics

  var useRelay by rememberSaveable(settings.useRelayMode) { mutableStateOf(settings.useRelayMode) }
  var relayUrl by rememberSaveable(settings.relayUrl) { mutableStateOf(settings.relayUrl) }
  var roomCode by rememberSaveable(settings.roomCode) { mutableStateOf(settings.roomCode) }
  var selectedPort by rememberSaveable(settings.proxyPort) { mutableStateOf(settings.proxyPort) }
  var requirePin by rememberSaveable(settings.requirePin) { mutableStateOf(settings.requirePin) }
  var pinInput by rememberSaveable(settings.proxyPin) { mutableStateOf(settings.proxyPin) }
  var showDeployHelp by rememberSaveable { mutableStateOf(false) }
  var relayUrlError by rememberSaveable { mutableStateOf(false) }

  val isHostRunning =
    metrics.isRunning && uiState.activeServiceMode != ServiceBridgeMode.FRIEND_RELAY

  val startAction: () -> Unit = {
    if (isHostRunning) {
      viewModel.stopBridge()
    } else if (useRelay) {
      if (relayUrl.isBlank()) {
        relayUrlError = true
      } else {
        relayUrlError = false
        viewModel.updateRelayConfig(true, relayUrl, roomCode)
        viewModel.startRelayHost(relayUrl, roomCode, requirePin, pinInput)
      }
    } else {
      viewModel.startDirectHost(selectedPort, requirePin, pinInput)
    }
  }

  val notificationPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
      startAction()
    }

  val shareCode: String? =
    if (useRelay) {
      if (relayUrl.isNotBlank() && roomCode.isNotBlank()) {
        BridgeCodeCodec.encodeRelay(relayUrl, roomCode, if (requirePin) pinInput else null)
      } else null
    } else {
      val localAddr =
        uiState.activeAddresses.firstOrNull { it.kind == InterfaceKind.LOCAL_NETWORK }
      localAddr?.let {
        BridgeCodeCodec.encodeDirect(it.ipAddress, selectedPort, if (requirePin) pinInput else null)
      }
    }

  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    // Method selector: Self-Reliant Relay vs Direct IP
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      FilterChip(
        selected = useRelay,
        enabled = !isHostRunning,
        onClick = {
          useRelay = true
          viewModel.updateRelayConfig(true, relayUrl, roomCode)
        },
        label = { Text(strings.relayMethodLabel) },
        modifier = Modifier.weight(1f).testTag("host_mode_relay_chip"),
      )
      FilterChip(
        selected = !useRelay,
        enabled = !isHostRunning,
        onClick = {
          useRelay = false
          viewModel.updateRelayConfig(false, relayUrl, roomCode)
        },
        label = { Text(strings.directMethodLabel) },
        modifier = Modifier.weight(1f).testTag("host_mode_direct_chip"),
      )
    }

    // 1. Host Bridge Control
    SectionCard(
      borderColor =
        if (isHostRunning) signalColors.success.copy(alpha = 0.7f)
        else MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
    ) {
      SectionHeader(
        icon = if (useRelay) Icons.Filled.CloudQueue else Icons.Filled.Hub,
        title = if (useRelay) strings.hostRelayTitle else strings.hostDirectTitle,
        subtitle = if (useRelay) strings.hostRelayDesc else strings.hostDirectDesc,
        accentColor = MaterialTheme.colorScheme.primary,
      )

      if (useRelay) {
        OutlinedTextField(
          value = relayUrl,
          onValueChange = {
            relayUrl = it
            relayUrlError = false
            viewModel.updateRelayConfig(true, it, roomCode)
          },
          enabled = !isHostRunning,
          label = { Text(strings.relayUrlLabel) },
          placeholder = { Text(strings.relayUrlHint) },
          isError = relayUrlError,
          supportingText =
            if (relayUrlError) {
              { Text(strings.relayUrlMissingError) }
            } else null,
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("relay_url_input"),
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedTextField(
            value = roomCode,
            onValueChange = { next ->
              val clean = next.filter { it.isLetterOrDigit() }.take(10)
              roomCode = clean
              viewModel.updateRelayConfig(true, relayUrl, clean)
            },
            enabled = !isHostRunning,
            label = { Text(strings.roomCodeLabel) },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag("room_code_input"),
          )
          IconButton(
            onClick = {
              if (!isHostRunning) {
                viewModel.regenerateRoomCode()
              }
            },
            enabled = !isHostRunning,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("regen_room_btn"),
          ) {
            Icon(
              imageVector = Icons.Filled.Autorenew,
              contentDescription = strings.newRoomBtn,
              tint = MaterialTheme.colorScheme.primary,
            )
          }
        }

        TextButton(
          onClick = { showDeployHelp = !showDeployHelp },
          modifier = Modifier.testTag("toggle_deploy_help_btn"),
        ) {
          Icon(
            imageVector = Icons.Filled.Info,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(strings.howToDeployRelayTitle)
        }

        AnimatedVisibility(visible = showDeployHelp) {
          Surface(
            shape = MaterialTheme.shapes.small,
            color = signalColors.warningContainer.copy(alpha = 0.45f),
            border = BorderStroke(1.dp, signalColors.warning.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = strings.howToDeployRelayBody,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.padding(12.dp),
            )
          }
        }
      } else {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "${strings.portLabel}:",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          listOf(8888, 8080, 9090).forEach { portOption ->
            FilterChip(
              selected = selectedPort == portOption,
              onClick = {
                selectedPort = portOption
                viewModel.updateProxyConfig(portOption, requirePin, pinInput)
              },
              enabled = !isHostRunning,
              label = { Text(portOption.toString(), fontFamily = JetBrainsMonoFamily) },
              modifier = Modifier.testTag("port_chip_$portOption"),
            )
          }
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(18.dp),
          )
          Text(
            text = strings.pinProtectLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
        Switch(
          checked = requirePin,
          enabled = !isHostRunning,
          onCheckedChange = { checked ->
            requirePin = checked
            viewModel.updateProxyConfig(selectedPort, checked, pinInput)
          },
          modifier = Modifier.testTag("pin_lock_switch"),
        )
      }

      AnimatedVisibility(visible = requirePin) {
        OutlinedTextField(
          value = pinInput,
          onValueChange = { next ->
            val digits = next.filter { it.isDigit() }.take(6)
            pinInput = digits
            viewModel.updateProxyConfig(selectedPort, requirePin, digits)
          },
          enabled = !isHostRunning,
          label = { Text(strings.pinValueLabel) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.fillMaxWidth().testTag("proxy_pin_input"),
        )
      }

      if (isHostRunning && useRelay) {
        val statusLabel =
          when {
            metrics.relayConnected && metrics.peerOnline -> strings.relayConnectedOnline
            metrics.relayConnected -> strings.relayWaitingPeer
            else -> strings.relayConnecting
          }
        RelayStatusBanner(
          statusText = statusLabel,
          isPeerOnline = metrics.peerOnline,
          cellularBound = metrics.cellularBound,
          strings = strings,
          testTagPrefix = "host_relay",
        )
      }

      if (!metrics.lastError.isNullOrBlank()) {
        Text(
          text = metrics.lastError,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }

      Button(
        onClick = {
          if (!isHostRunning && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
          } else {
            startAction()
          }
        },
        colors =
          ButtonDefaults.buttonColors(
            containerColor =
              if (isHostRunning) MaterialTheme.colorScheme.error
              else MaterialTheme.colorScheme.primary,
            contentColor =
              if (isHostRunning) MaterialTheme.colorScheme.onError
              else MaterialTheme.colorScheme.onPrimary,
          ),
        modifier =
          Modifier.fillMaxWidth()
            .height(54.dp)
            .minimumInteractiveComponentSize()
            .testTag("toggle_bridge_btn"),
      ) {
        Icon(
          imageVector = if (isHostRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
          contentDescription = null,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = if (isHostRunning) strings.stopBridge else strings.startBridge,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
        )
      }
    }

    // 2. Share Bridge Code
    SectionCard {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = strings.shareCardTitle,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = strings.shareCardDesc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (!useRelay) {
          TextButton(
            onClick = { viewModel.refreshNetworkState() },
            modifier = Modifier.minimumInteractiveComponentSize().testTag("refresh_ips_btn"),
          ) {
            Icon(
              imageVector = Icons.Filled.Refresh,
              contentDescription = strings.refreshInterfaces,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(strings.refreshInterfaces)
          }
        }
      }

      if (shareCode != null) {
        Surface(
          shape = MaterialTheme.shapes.small,
          color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(
              text = strings.bridgeCodeLabel,
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
            )
            Text(
              text = shareCode,
              fontFamily = JetBrainsMonoFamily,
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              OutlinedButton(
                onClick = {
                  copyTextToClipboard(context, shareCode)
                  Toast.makeText(context, strings.copiedFeedback, Toast.LENGTH_SHORT).show()
                },
                modifier =
                  Modifier.weight(1f).minimumInteractiveComponentSize().testTag("copy_code_btn"),
              ) {
                Icon(
                  imageVector = Icons.Filled.ContentCopy,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(strings.copyCode)
              }
              Button(
                onClick = {
                  shareBridgeInvite(
                    context = context,
                    bridgeCode = shareCode,
                    roomOrEndpoint = if (useRelay) "Room $roomCode" else "Port $selectedPort",
                    pin = if (requirePin) pinInput else null,
                  )
                },
                modifier =
                  Modifier.weight(1f).minimumInteractiveComponentSize().testTag("send_friend_btn"),
              ) {
                Icon(
                  imageVector = Icons.Filled.Share,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(strings.sendToFriend)
              }
            }
          }
        }
      } else if (useRelay) {
        Surface(
          shape = MaterialTheme.shapes.small,
          color = MaterialTheme.colorScheme.surfaceVariant,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = strings.relayUrlMissingError,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
          )
        }
      }

      if (!useRelay) {
        val localAddresses =
          uiState.activeAddresses.filter { it.kind == InterfaceKind.LOCAL_NETWORK }
        if (localAddresses.isEmpty()) {
          Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = strings.noReachableIpHint,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(12.dp),
            )
          }
        } else {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            localAddresses.forEach { addr ->
              CopyableIpRow(
                addressInfo = addr,
                port = selectedPort,
                strings = strings,
                onCopy = { endpoint ->
                  copyTextToClipboard(context, endpoint)
                  Toast.makeText(context, strings.copiedFeedback, Toast.LENGTH_SHORT).show()
                },
                onShare = { endpoint ->
                  val ip = endpoint.substringBefore(":")
                  val code =
                    BridgeCodeCodec.encodeDirect(ip, selectedPort, if (requirePin) pinInput else null)
                  shareBridgeInvite(context, code, endpoint, if (requirePin) pinInput else null)
                },
              )
            }
          }
        }
      }
    }

    // 3. Live Telemetry
    SectionCard {
      SectionHeader(
        icon = Icons.Filled.Speed,
        title = strings.liveTelemetryTitle,
        accentColor = signalColors.success,
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        MetricTile(
          label = strings.activeSocketsLabel,
          value = metrics.activeConnections.toString(),
          modifier = Modifier.weight(1f),
        )
        MetricTile(
          label = strings.dataRelayedLabel,
          value = ProxyBridgeService.formatByteCount(metrics.totalBytesRelayed),
          modifier = Modifier.weight(1f),
        )
      }

      Text(
        text = strings.connectedClientsLabel,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (metrics.clientIps.isEmpty()) {
        Text(
          text = strings.noClientsYet,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          metrics.clientIps.forEach { ip ->
            AssistChip(onClick = {}, label = { Text(ip, fontFamily = JetBrainsMonoFamily) })
          }
        }
      }
    }
  }
}

@Composable
private fun FriendRoleContent(
  uiState: MainUiState,
  strings: UiStrings,
  viewModel: MainViewModel,
) {
  val context = LocalContext.current
  val signalColors = LocalSignalColors.current
  val settings = uiState.settings
  val metrics = uiState.proxyMetrics

  var useRelay by rememberSaveable { mutableStateOf(true) }
  var codeInput by rememberSaveable { mutableStateOf("") }
  var codeError by rememberSaveable { mutableStateOf(false) }

  // Relay fields
  var relayUrl by rememberSaveable(settings.relayUrl) { mutableStateOf(settings.relayUrl) }
  var roomCode by rememberSaveable { mutableStateOf("") }

  // Direct fields
  var hostIp by rememberSaveable(settings.testTargetHost) {
    mutableStateOf(settings.testTargetHost)
  }
  var portText by rememberSaveable(settings.testTargetPort) {
    mutableStateOf(settings.testTargetPort.toString())
  }
  var pinText by rememberSaveable { mutableStateOf("") }

  val isFriendRelayRunning =
    metrics.isRunning && uiState.activeServiceMode == ServiceBridgeMode.FRIEND_RELAY

  val notificationPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
      viewModel.startRelayFriend(
        localPort = 8888,
        relayUrl = relayUrl,
        roomCode = roomCode,
        pin = pinText.ifBlank { null },
      )
    }

  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      FilterChip(
        selected = useRelay,
        onClick = { useRelay = true },
        label = { Text(strings.relayMethodLabel) },
        modifier = Modifier.weight(1f).testTag("friend_mode_relay_chip"),
      )
      FilterChip(
        selected = !useRelay,
        onClick = { useRelay = false },
        label = { Text(strings.directMethodLabel) },
        modifier = Modifier.weight(1f).testTag("friend_mode_direct_chip"),
      )
    }

    // 1. Paste Code & Connect
    SectionCard {
      SectionHeader(
        icon = Icons.Filled.Key,
        title = strings.friendPasteTitle,
        subtitle = strings.friendPasteDesc,
        accentColor = MaterialTheme.colorScheme.primary,
      )

      OutlinedTextField(
        value = codeInput,
        onValueChange = {
          codeInput = it
          codeError = false
        },
        label = { Text(strings.pasteCodeLabel) },
        isError = codeError,
        supportingText =
          if (codeError) {
            { Text(strings.invalidCodeError) }
          } else null,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("paste_bridge_code_input"),
      )

      OutlinedButton(
        onClick = {
          when (val decoded = BridgeCodeCodec.decode(codeInput)) {
            null -> codeError = true
            is DecodedBridgePayload.Relay -> {
              codeError = false
              useRelay = true
              relayUrl = decoded.relayUrl
              roomCode = decoded.roomCode
              pinText = decoded.pin
              viewModel.updateRelayConfig(true, decoded.relayUrl, decoded.roomCode)
              Toast.makeText(context, strings.codeAppliedFeedback, Toast.LENGTH_SHORT).show()
            }
            is DecodedBridgePayload.Direct -> {
              codeError = false
              useRelay = false
              hostIp = decoded.host
              portText = decoded.port.toString()
              pinText = decoded.pin
              viewModel.updateTestTarget(decoded.host, decoded.port)
              Toast.makeText(context, strings.codeAppliedFeedback, Toast.LENGTH_SHORT).show()
            }
          }
        },
        modifier =
          Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("apply_code_btn"),
      ) {
        Text(strings.applyCodeBtn, fontWeight = FontWeight.SemiBold)
      }

      if (useRelay) {
        OutlinedTextField(
          value = relayUrl,
          onValueChange = { relayUrl = it },
          enabled = !isFriendRelayRunning,
          label = { Text(strings.relayUrlLabel) },
          placeholder = { Text(strings.relayUrlHint) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("friend_relay_url_input"),
        )
        OutlinedTextField(
          value = roomCode,
          onValueChange = { roomCode = it.filter { ch -> ch.isLetterOrDigit() }.take(10) },
          enabled = !isFriendRelayRunning,
          label = { Text(strings.roomCodeLabel) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("friend_room_code_input"),
        )
        OutlinedTextField(
          value = pinText,
          onValueChange = { pinText = it.filter { ch -> ch.isDigit() }.take(6) },
          enabled = !isFriendRelayRunning,
          label = { Text(strings.optionalPinLabel) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.fillMaxWidth().testTag("tester_pin_input"),
        )

        if (isFriendRelayRunning) {
          val statusLabel =
            when {
              metrics.relayConnected && metrics.peerOnline -> strings.relayFriendHostOnline
              metrics.relayConnected -> strings.relayFriendHostOffline
              else -> strings.relayConnecting
            }
          RelayStatusBanner(
            statusText = "$statusLabel (127.0.0.1:8888)",
            isPeerOnline = metrics.peerOnline,
            cellularBound = metrics.cellularBound,
            strings = strings,
            testTagPrefix = "friend_relay",
          )
        }

        if (!metrics.lastError.isNullOrBlank()) {
          Text(
            text = metrics.lastError,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }

        Button(
          onClick = {
            if (isFriendRelayRunning) {
              viewModel.stopBridge()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
              viewModel.startRelayFriend(8888, relayUrl, roomCode, pinText.ifBlank { null })
            }
          },
          enabled = isFriendRelayRunning || (relayUrl.isNotBlank() && roomCode.isNotBlank()),
          colors =
            ButtonDefaults.buttonColors(
              containerColor =
                if (isFriendRelayRunning) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
              contentColor =
                if (isFriendRelayRunning) MaterialTheme.colorScheme.onError
                else MaterialTheme.colorScheme.onPrimary,
            ),
          modifier =
            Modifier.fillMaxWidth()
              .height(52.dp)
              .minimumInteractiveComponentSize()
              .testTag("toggle_friend_relay_btn"),
        ) {
          Icon(
            imageVector = if (isFriendRelayRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            contentDescription = null,
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text =
              if (isFriendRelayRunning) strings.stopRelayTunnelBtn
              else strings.connectRelayTunnelBtn,
            fontWeight = FontWeight.Bold,
          )
        }
      } else {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          OutlinedTextField(
            value = hostIp,
            onValueChange = {
              hostIp = it
              viewModel.updateTestTarget(it, portText.toIntOrNull() ?: 8888)
            },
            label = { Text(strings.targetHostLabel) },
            singleLine = true,
            modifier = Modifier.weight(2f).testTag("tester_host_input"),
          )
          OutlinedTextField(
            value = portText,
            onValueChange = { next ->
              val digits = next.filter { it.isDigit() }.take(5)
              portText = digits
              viewModel.updateTestTarget(hostIp, digits.toIntOrNull() ?: 8888)
            },
            label = { Text(strings.targetPortLabel) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f).testTag("tester_port_input"),
          )
        }

        OutlinedTextField(
          value = pinText,
          onValueChange = { pinText = it.filter { ch -> ch.isDigit() }.take(6) },
          label = { Text(strings.optionalPinLabel) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.fillMaxWidth().testTag("tester_pin_input"),
        )
      }

      // Live Tunnel Connectivity Test button (tests 127.0.0.1:8888 in Relay Mode or hostIp:port in Direct Mode)
      val testHost = if (useRelay) "127.0.0.1" else hostIp
      val testPort = if (useRelay) 8888 else (portText.toIntOrNull() ?: 8888)
      val testPin = if (useRelay) null else pinText.ifBlank { null }
      val isTesting = uiState.tunnelTestResult is TunnelTestResult.Testing

      OutlinedButton(
        onClick = { viewModel.runProxyTunnelTest(testHost, testPort, testPin) },
        enabled = !isTesting && ((!useRelay && hostIp.isNotBlank()) || isFriendRelayRunning),
        modifier =
          Modifier.fillMaxWidth()
            .height(50.dp)
            .minimumInteractiveComponentSize()
            .testTag("run_tunnel_test_btn"),
      ) {
        if (isTesting) {
          CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
          Spacer(modifier = Modifier.width(8.dp))
          Text(strings.testingTunnel)
        } else {
          Icon(imageVector = Icons.Filled.NetworkCheck, contentDescription = null)
          Spacer(modifier = Modifier.width(8.dp))
          Text(strings.runTunnelTest, fontWeight = FontWeight.Bold)
        }
      }

      when (val result = uiState.tunnelTestResult) {
        is TunnelTestResult.Success -> {
          Surface(
            shape = MaterialTheme.shapes.small,
            color = signalColors.successContainer,
            border = BorderStroke(1.dp, signalColors.success.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth().testTag("tunnel_test_success"),
          ) {
            Row(
              modifier = Modifier.padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = signalColors.success,
              )
              Column {
                Text(
                  text = strings.testSuccessPrefix,
                  style = MaterialTheme.typography.labelLarge,
                  color = signalColors.success,
                )
                Text(
                  text = "HTTPS ${result.httpCode} • ${result.latencyMs} ms",
                  fontFamily = JetBrainsMonoFamily,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }
            }
          }
        }
        is TunnelTestResult.Failure -> {
          Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.errorContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth().testTag("tunnel_test_failure"),
          ) {
            Row(
              modifier = Modifier.padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
              )
              Column {
                Text(
                  text = strings.testFailurePrefix,
                  style = MaterialTheme.typography.labelLarge,
                  color = MaterialTheme.colorScheme.error,
                )
                Text(
                  text = result.reason,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                )
              }
            }
          }
        }
        else -> Unit
      }
    }

    // 2. Apply System Wi-Fi Proxy
    SectionCard {
      SectionHeader(
        icon = Icons.Filled.PhoneAndroid,
        title = strings.friendApplyTitle,
        subtitle = if (useRelay) strings.friendApplyRelaySteps else strings.friendApplyDirectSteps,
        accentColor = MaterialTheme.colorScheme.secondary,
      )

      val proxyHostDisplay = if (useRelay) "127.0.0.1" else hostIp.trim().ifBlank { "—" }
      val proxyPortDisplay = if (useRelay) "8888" else portText.ifBlank { "8888" }

      Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(12.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "$proxyHostDisplay : $proxyPortDisplay",
            fontFamily = JetBrainsMonoFamily,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
          )
          OutlinedButton(
            onClick = {
              copyTextToClipboard(context, proxyHostDisplay)
              Toast.makeText(context, strings.copiedFeedback, Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.minimumInteractiveComponentSize().testTag("copy_friend_host_btn"),
          ) {
            Text(strings.copyCode)
          }
        }
      }

      OutlinedButton(
        onClick = {
          runCatching {
            context.startActivity(
              Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
              }
            )
          }
        },
        modifier =
          Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("open_wifi_btn"),
      ) {
        Icon(
          imageVector = Icons.Filled.Wifi,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(strings.openWifiSettings)
      }
    }
  }
}

@Composable
private fun RelayStatusBanner(
  statusText: String,
  isPeerOnline: Boolean,
  cellularBound: Boolean,
  strings: UiStrings,
  testTagPrefix: String,
) {
  val signalColors = LocalSignalColors.current
  Surface(
    shape = MaterialTheme.shapes.small,
    color =
      if (isPeerOnline) signalColors.successContainer
      else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
    modifier = Modifier.fillMaxWidth().testTag("${testTagPrefix}_status_banner"),
  ) {
    Column(
      modifier = Modifier.padding(10.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
        text = statusText,
        style = MaterialTheme.typography.labelLarge,
        color = if (isPeerOnline) signalColors.success else MaterialTheme.colorScheme.primary,
      )
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.testTag("${testTagPrefix}_route_badge"),
      ) {
        Icon(
          imageVector = Icons.Filled.SignalCellularAlt,
          contentDescription = null,
          tint =
            if (cellularBound) signalColors.success
            else signalColors.warning,
          modifier = Modifier.size(16.dp),
        )
        Text(
          text = if (cellularBound) strings.networkRouteCellular else strings.networkRouteFallback,
          style = MaterialTheme.typography.bodySmall,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
      if (!cellularBound) {
        Text(
          text = strings.networkRouteHint,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun MetricTile(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier,
    shape = MaterialTheme.shapes.small,
    color = MaterialTheme.colorScheme.surfaceVariant,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
  ) {
    Column(
      modifier = Modifier.padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text = value,
        fontFamily = JetBrainsMonoFamily,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
      )
    }
  }
}

private fun copyTextToClipboard(context: Context, text: String) {
  val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
  clipboard?.setPrimaryClip(ClipData.newPlainText("NetSetu Bridge", text))
}

private fun shareBridgeInvite(
  context: Context,
  bridgeCode: String,
  roomOrEndpoint: String,
  pin: String?,
) {
  val pinLine = if (!pin.isNullOrBlank()) "\nPIN: $pin" else ""
  val message =
    "NetSetu Internet Bridge ($roomOrEndpoint):\n" +
      "Code: $bridgeCode$pinLine\n\n" +
      "1. Kisi ke Hotspot se connect ho jaao\n" +
      "2. NetSetu -> Friend mode me ye Code paste karke Connect dabao"
  val intent =
    Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_TEXT, message)
    }
  context.startActivity(Intent.createChooser(intent, "Send NetSetu Code"))
}
