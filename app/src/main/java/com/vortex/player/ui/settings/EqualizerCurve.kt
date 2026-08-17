package com.vortex.player.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vortex.player.audio.EQ_BANDS
import com.vortex.player.audio.EQ_MAX_DB
import com.vortex.player.audio.EQ_MIN_DB
import com.vortex.player.ui.theme.VortexPalette
import com.vortex.player.ui.theme.VortexShapes
import androidx.compose.foundation.Canvas
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Curva del ecualizador, arrastrable.
 *
 * Se dibuja la respuesta completa en vez de diez deslizadores sueltos porque la forma de
 * la curva es la información importante: de un vistazo se ve si se están hinchando los
 * graves o vaciando los medios, algo que diez barras verticales no comunican igual.
 */
@Composable
fun EqualizerCurve(
    gains: List<Float>,
    enabled: Boolean,
    onGainChange: (index: Int, gain: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var draggingBand by remember { mutableStateOf(-1) }
    val textMeasurer = rememberTextMeasurer()

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(190.dp)
                .clip(VortexShapes.medium)
                .background(VortexPalette.GraphiteRaised)
                .border(0.5.dp, VortexPalette.Outline, VortexShapes.medium)
                .pointerInput(enabled, gains.size) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDragStart = { offset ->
                            draggingBand = nearestBand(offset.x, size.width, gains.size)
                        },
                        onDragEnd = { draggingBand = -1 },
                        onDrag = { change, _ ->
                            change.consume()
                            val band = draggingBand
                            if (band >= 0) {
                                onGainChange(band, gainAt(change.position.y, size.height))
                            }
                        }
                    )
                }
                .pointerInput(enabled, gains.size) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        val band = nearestBand(offset.x, size.width, gains.size)
                        onGainChange(band, gainAt(offset.y, size.height))
                    }
                }
        ) {
            Canvas(Modifier.fillMaxWidth().height(190.dp)) {
                val width = size.width
                val height = size.height
                val step = width / gains.size
                val tint = if (enabled) VortexPalette.Neon else VortexPalette.Outline

                // Línea de 0 dB: la referencia contra la que se lee todo lo demás.
                drawLine(
                    color = VortexPalette.Outline,
                    start = Offset(0f, height / 2f),
                    end = Offset(width, height / 2f),
                    strokeWidth = 1f
                )

                val points = gains.mapIndexed { index, gain ->
                    val x = step * index + step / 2f
                    val normalized = (gain - EQ_MIN_DB) / (EQ_MAX_DB - EQ_MIN_DB)
                    Offset(x, height - normalized * height)
                }

                // Retícula vertical, una por banda.
                points.forEach { point ->
                    drawLine(
                        color = VortexPalette.Outline.copy(alpha = 0.5f),
                        start = Offset(point.x, 0f),
                        end = Offset(point.x, height),
                        strokeWidth = 1f
                    )
                }

                val curve = Path().apply {
                    moveTo(0f, points.first().y)
                    points.forEachIndexed { index, point ->
                        if (index == 0) {
                            lineTo(point.x, point.y)
                        } else {
                            // Curva suave entre bandas: el punto de control a media
                            // distancia evita los picos angulosos de una polilínea.
                            val previous = points[index - 1]
                            val midX = (previous.x + point.x) / 2f
                            cubicTo(midX, previous.y, midX, point.y, point.x, point.y)
                        }
                    }
                    lineTo(width, points.last().y)
                }

                val filled = Path().apply {
                    addPath(curve)
                    lineTo(width, height)
                    lineTo(0f, height)
                    close()
                }
                drawPath(
                    path = filled,
                    brush = Brush.verticalGradient(
                        listOf(tint.copy(alpha = 0.22f), Color.Transparent)
                    )
                )
                drawPath(
                    path = curve,
                    color = tint,
                    style = Stroke(width = 3f, cap = StrokeCap.Round)
                )

                points.forEachIndexed { index, point ->
                    drawCircle(color = tint, radius = 9f, center = point)
                    drawCircle(
                        color = VortexPalette.Graphite,
                        radius = 4f,
                        center = point
                    )

                    // El valor va pegado a su punto y no en una fila aparte: así se lee la
                    // banda y su ganancia de una sola mirada, sin cruzar la vista abajo y
                    // contar columnas para saber cuál es cuál.
                    val gain = gains[index]
                    val flat = abs(gain) < 0.05f
                    val measured = textMeasurer.measure(
                        text = if (flat) "0" else "%+.0f".format(gain),
                        style = TextStyle(
                            color = if (flat) VortexPalette.TextLow else VortexPalette.Cyan,
                            fontSize = 10.sp
                        )
                    )
                    drawText(
                        textLayoutResult = measured,
                        topLeft = Offset(
                            (point.x - measured.size.width / 2f)
                                .coerceIn(0f, width - measured.size.width),
                            (point.y - measured.size.height - 14f).coerceAtLeast(2f)
                        )
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            EQ_BANDS.forEach { frequency ->
                Text(
                    text = shortFrequency(frequency),
                    style = MaterialTheme.typography.labelSmall,
                    color = VortexPalette.TextLow,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

private fun nearestBand(x: Float, width: Int, bandCount: Int): Int {
    val step = width.toFloat() / bandCount
    return ((x / step).toInt()).coerceIn(0, bandCount - 1)
}

private fun gainAt(y: Float, height: Int): Float {
    val normalized = 1f - (y / height).coerceIn(0f, 1f)
    val raw = EQ_MIN_DB + normalized * (EQ_MAX_DB - EQ_MIN_DB)
    // A medio decibelio: más fino no se distingue de oído y hace imposible volver a cero.
    return (raw * 2f).roundToInt() / 2f
}

private fun shortFrequency(hz: Int): String =
    if (hz >= 1000) "${hz / 1000}k" else "$hz"
