package com.vortex.player.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Panel base de la interfaz: fondo elevado, borde fino y esquinas cortadas.
 * Es el ladrillo con el que se construyen tarjetas, hojas y el marco del popup.
 */
@Composable
fun HudPanel(
    modifier: Modifier = Modifier,
    accent: Color = LocalAccentColor.current,
    accentBorder: Boolean = false,
    contentPadding: Dp = 12.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .background(VortexPalette.GraphiteRaised, VortexShapes.medium)
            .border(
                width = if (accentBorder) 1.dp else 0.5.dp,
                color = if (accentBorder) accent.copy(alpha = 0.55f) else VortexPalette.Outline,
                shape = VortexShapes.medium
            )
            .padding(contentPadding),
        content = content
    )
}

/**
 * Resplandor dirigido que se dibuja *detrás* del contenido. Sustituye a las sombras de
 * Material: en un tema tan oscuro una sombra no se ve, pero un halo de color sí separa planos.
 */
fun Modifier.neonGlow(
    color: Color,
    radius: Dp = 24.dp,
    alpha: Float = 0.35f
): Modifier = this.drawBehind {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), Color.Transparent),
            center = center,
            radius = radius.toPx()
        ),
        radius = radius.toPx(),
        center = center
    )
}

/**
 * La marca Vórtex dibujada en Compose, para poder animarla: los dos brazos giran
 * lentamente mientras hay reproducción y se detienen en pausa.
 */
@Composable
fun VortexMark(
    modifier: Modifier = Modifier,
    spinning: Boolean = false,
    armA: Color = VortexPalette.Neon,
    armB: Color = VortexPalette.Cyan,
    strokeWidth: Dp = 6.dp
) {
    val transition = rememberInfiniteTransition(label = "vortex-mark")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "vortex-spin"
    )

    Box(
        modifier = modifier.drawBehind {
            val rotation = if (spinning) angle else 0f
            rotate(rotation) {
                drawVortexArm(startAngleDeg = -90f, color = armA, strokeWidth = strokeWidth.toPx())
                drawVortexArm(startAngleDeg = 90f, color = armB, strokeWidth = strokeWidth.toPx())
            }
            drawCircle(
                color = VortexPalette.TextHigh,
                radius = size.minDimension * 0.03f,
                center = center
            )
        }
    )
}

/**
 * Un brazo del vórtice: seis vértices en pasos de 60° con el radio decreciendo
 * linealmente hasta el centro. Al ser segmentos rectos, el resultado es angular.
 */
private fun DrawScope.drawVortexArm(
    startAngleDeg: Float,
    color: Color,
    strokeWidth: Float
) {
    val steps = 6
    val maxRadius = size.minDimension * 0.42f
    val path = Path()

    for (i in 0 until steps) {
        val radius = maxRadius * (1f - i / steps.toFloat() * 0.83f)
        val rad = Math.toRadians((startAngleDeg + i * 60f).toDouble())
        val x = center.x + radius * cos(rad).toFloat()
        val y = center.y + radius * sin(rad).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }

    // Halo primero, trazo nítido encima: dos pasadas de la misma geometría.
    drawPath(
        path = path,
        color = color.copy(alpha = 0.18f),
        style = Stroke(width = strokeWidth * 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
    drawPath(
        path = path,
        brush = Brush.linearGradient(
            colors = listOf(color, color.copy(alpha = 0.15f)),
            start = Offset(center.x, center.y - maxRadius),
            end = center
        ),
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}
