package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class SignalExtendedColors(
  val success: Color,
  val onSuccess: Color,
  val successContainer: Color,
  val warning: Color,
  val warningContainer: Color,
  val cardElevated: Color,
)

val LocalSignalColors =
  staticCompositionLocalOf {
    SignalExtendedColors(
      success = SignalEmerald,
      onSuccess = Color.White,
      successContainer = SignalEmeraldContainer,
      warning = SignalAmber,
      warningContainer = SignalAmberContainer,
      cardElevated = MidnightCardHigh,
    )
  }

private val DarkColorScheme =
  darkColorScheme(
    primary = ElectricCyan,
    onPrimary = MidnightDeep,
    primaryContainer = ElectricCyanContainer,
    onPrimaryContainer = ElectricCyan,
    secondary = SignalAmber,
    onSecondary = MidnightDeep,
    secondaryContainer = SignalAmberContainer,
    onSecondaryContainer = SignalAmber,
    tertiary = SignalEmerald,
    onTertiary = MidnightDeep,
    tertiaryContainer = SignalEmeraldContainer,
    onTertiaryContainer = SignalEmerald,
    error = SignalCoral,
    onError = Color.White,
    errorContainer = SignalCoralContainer,
    onErrorContainer = SignalCoral,
    background = MidnightDeep,
    onBackground = TextPrimaryDark,
    surface = MidnightSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = MidnightCard,
    onSurfaceVariant = TextSecondaryDark,
    outline = OutlineDark,
  )

private val LightColorScheme =
  lightColorScheme(
    primary = PrimaryBlueTeal,
    onPrimary = Color.White,
    primaryContainer = PrimaryBlueTealContainer,
    onPrimaryContainer = PrimaryBlueTeal,
    secondary = AmberLight,
    onSecondary = Color.White,
    secondaryContainer = AmberLightContainer,
    onSecondaryContainer = AmberLight,
    tertiary = EmeraldLight,
    onTertiary = Color.White,
    tertiaryContainer = EmeraldLightContainer,
    onTertiaryContainer = EmeraldLight,
    error = CoralLight,
    onError = Color.White,
    errorContainer = CoralLightContainer,
    onErrorContainer = CoralLight,
    background = DaylightBg,
    onBackground = TextPrimaryLight,
    surface = DaylightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = DaylightCard,
    onSurfaceVariant = TextSecondaryLight,
    outline = OutlineLight,
  )

val NetSetuShapes =
  Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
  )

@Composable
fun NetSetuTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
  val extendedColors =
    if (darkTheme) {
      SignalExtendedColors(
        success = SignalEmerald,
        onSuccess = MidnightDeep,
        successContainer = SignalEmeraldContainer,
        warning = SignalAmber,
        warningContainer = SignalAmberContainer,
        cardElevated = MidnightCardHigh,
      )
    } else {
      SignalExtendedColors(
        success = EmeraldLight,
        onSuccess = Color.White,
        successContainer = EmeraldLightContainer,
        warning = AmberLight,
        warningContainer = AmberLightContainer,
        cardElevated = DaylightSurface,
      )
    }

  CompositionLocalProvider(LocalSignalColors provides extendedColors) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = NetSetuTypography,
      shapes = NetSetuShapes,
      content = content,
    )
  }
}
