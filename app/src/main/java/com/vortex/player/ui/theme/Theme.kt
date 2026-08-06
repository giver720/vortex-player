package com.vortex.player.ui.theme

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val VortexColorScheme = darkColorScheme(
    primary = VortexPalette.Neon,
    onPrimary = VortexPalette.Graphite,
    primaryContainer = VortexPalette.NeonDim,
    onPrimaryContainer = VortexPalette.TextHigh,
    secondary = VortexPalette.Cyan,
    onSecondary = VortexPalette.Graphite,
    error = VortexPalette.Magenta,
    onError = VortexPalette.Graphite,
    background = VortexPalette.Graphite,
    onBackground = VortexPalette.TextHigh,
    surface = VortexPalette.GraphiteRaised,
    onSurface = VortexPalette.TextHigh,
    surfaceVariant = VortexPalette.GraphiteHigh,
    onSurfaceVariant = VortexPalette.TextMid,
    outline = VortexPalette.Outline,
    outlineVariant = VortexPalette.Outline
)

/**
 * Las esquinas cortadas en diagonal (no redondeadas) son la firma de forma de Vórtex:
 * aparecen en tarjetas, botones y en el marco de la ventana flotante.
 */
val VortexShapes = Shapes(
    extraSmall = CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp),
    small = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
    medium = CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp),
    large = CutCornerShape(topStart = 18.dp, bottomEnd = 18.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * Color dominante extraído de la miniatura del medio en curso. Toda la UI lo respira:
 * bordes, glows y la barra de progreso viran hacia él, de modo que la app se "tiñe"
 * de lo que estás viendo sin dejar de ser reconociblemente Vórtex.
 */
val LocalAccentColor: ProvidableCompositionLocal<Color> =
    compositionLocalOf { VortexPalette.Neon }

@Composable
fun VortexTheme(
    accent: Color = VortexPalette.Neon,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalAccentColor provides accent) {
        MaterialTheme(
            colorScheme = VortexColorScheme,
            typography = VortexTypography,
            shapes = VortexShapes,
            content = content
        )
    }
}
