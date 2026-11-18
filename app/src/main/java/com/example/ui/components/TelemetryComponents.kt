package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.i18n.UiStrings
import com.example.network.InterfaceKind
import com.example.network.NetworkAddressInfo
import com.example.network.UpstreamStatus
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalSignalColors

@Composable
fun BridgeHeroCard(
  isBridgeActive: Boolean,
  port: Int,
  upstreamStatus: UpstreamStatus,
  strings: UiStrings,
  modifier: Modifier = Modifier,
) {
  val signalColors = LocalSignalColors.current
  val infiniteTransition = rememberInfiniteTransition(label = "pulse")
  val pulseAlpha by
    infiniteTransition.animateFloat(
      initialValue = 0.45f,
      targetValue = 1.0f,
      animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
      label = "pulseAlpha",
    )

  val statusColor by
    animateColorAsState(
      targetValue =
        if (isBridgeActive) signalColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
      label = "statusColor",
    )

  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.large,
    border =
      BorderStroke(
        width = 1.dp,
        color =
          if (isBridgeActive) signalColors.success.copy(alpha = 0.65f)
          else MaterialTheme.colorScheme.outline,
      ),
    color = MaterialTheme.colorScheme.surfaceVariant,
  ) {
    Box(modifier = Modifier.fillMaxWidth().height(154.dp)) {
      Image(
        painter = painterResource(id = R.drawable.img_bridge_hero),
        contentDescription = strings.heroTagline,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().alpha(0.7f),
      )

      Box(
        modifier =
          Modifier.fillMaxSize()
            .background(
              Brush.verticalGradient(
                colors =
                  listOf(
                    MaterialTheme.colorScheme.background.copy(alpha = 0.25f),
                    MaterialTheme.colorScheme.background.copy(alpha = 0.9f),
                  )
              )
            )
      )

      Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.6f)),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Box(
                modifier =
                  Modifier.size(9.dp)
                    .clip(CircleShape)
                    .background(statusColor)
                    .alpha(if (isBridgeActive) pulseAlpha else 1f)
              )
              Text(
                text =
                  if (isBridgeActive) "${strings.statusActive} :$port" else strings.statusStandby,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
              )
            }
          }

          Surface(
            shape = CircleShape,
            color =
              if (upstreamStatus.hasInternet) {
                signalColors.successContainer.copy(alpha = 0.92f)
              } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f)
              },
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Icon(
                imageVector =
                  if (upstreamStatus.hasInternet) Icons.Filled.CheckCircle
                  else Icons.Outlined.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint =
                  if (upstreamStatus.hasInternet) signalColors.success
                  else MaterialTheme.colorScheme.error,
              )
              Text(
                text =
                  if (upstreamStatus.hasInternet) upstreamStatus.transportLabel
                  else strings.upstreamOffline,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }
          }
        }

        Text(
          text = strings.heroTagline,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onBackground,
          fontWeight = FontWeight.SemiBold,
        )
      }
    }
  }
}

@Composable
fun SectionCard(
  modifier: Modifier = Modifier,
  borderColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
  containerColor: Color = MaterialTheme.colorScheme.surface,
  content: @Composable ColumnScope.() -> Unit,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.medium,
    color = containerColor,
    border = BorderStroke(1.dp, borderColor),
    tonalElevation = 2.dp,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      content = content,
    )
  }
}

@Composable
fun SectionHeader(
  icon: ImageVector,
  title: String,
  subtitle: String? = null,
  accentColor: Color = MaterialTheme.colorScheme.primary,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = accentColor.copy(alpha = 0.14f),
      modifier = Modifier.size(40.dp),
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = accentColor,
          modifier = Modifier.size(22.dp),
        )
      }
    }
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Bold,
      )
      if (!subtitle.isNullOrBlank()) {
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
fun CopyableIpRow(
  addressInfo: NetworkAddressInfo,
  port: Int,
  strings: UiStrings,
  onCopy: (String) -> Unit,
  onShare: (String) -> Unit,
) {
  val signalColors = LocalSignalColors.current
  val fullEndpoint = "${addressInfo.ipAddress}:$port"
  val isTunnel = addressInfo.kind == InterfaceKind.REMOTE_TUNNEL
  val kindLabel =
    when (addressInfo.kind) {
      InterfaceKind.REMOTE_TUNNEL -> "Remote Tunnel (1–2km+)"
      InterfaceKind.LOCAL_NETWORK -> "Local Wi-Fi / LAN"
      InterfaceKind.CELLULAR_PRIVATE -> "Mobile CGNAT (Private)"
    }

  Surface(
    shape = MaterialTheme.shapes.small,
    color =
      if (isTunnel) signalColors.successContainer.copy(alpha = 0.45f)
      else MaterialTheme.colorScheme.surfaceVariant,
    border =
      BorderStroke(
        1.dp,
        if (isTunnel) signalColors.success.copy(alpha = 0.7f)
        else MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
      ),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = "$kindLabel (${addressInfo.interfaceName})",
          style = MaterialTheme.typography.labelMedium,
          color = if (isTunnel) signalColors.success else MaterialTheme.colorScheme.primary,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = fullEndpoint,
          fontFamily = JetBrainsMonoFamily,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
          onClick = { onCopy(fullEndpoint) },
          modifier =
            Modifier.minimumInteractiveComponentSize().testTag("copy_ip_${addressInfo.ipAddress}"),
        ) {
          Icon(
            imageVector = Icons.Filled.ContentCopy,
            contentDescription = strings.copyCode,
            tint = MaterialTheme.colorScheme.primary,
          )
        }
        Spacer(modifier = Modifier.width(2.dp))
        IconButton(
          onClick = { onShare(fullEndpoint) },
          modifier =
            Modifier.minimumInteractiveComponentSize().testTag("share_ip_${addressInfo.ipAddress}"),
        ) {
          Icon(
            imageVector = Icons.Filled.Share,
            contentDescription = strings.sendToFriend,
            tint = MaterialTheme.colorScheme.secondary,
          )
        }
      }
    }
  }
}
