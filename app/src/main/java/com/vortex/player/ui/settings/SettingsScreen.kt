package com.vortex.player.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vortex.player.audio.AudioCapabilities
import com.vortex.player.audio.AudioOutput
import com.vortex.player.audio.AudioScope
import com.vortex.player.audio.AudioSettings
import com.vortex.player.audio.EqPreset
import com.vortex.player.ui.theme.VortexPalette
import com.vortex.player.ui.theme.VortexShapes

/**
 * Ajustes de sonido.
 *
 * Todo son efectos del procesador de audio del propio móvil, aplicados sobre la sesión
 * del reproductor: no se recodifica nada. La pantalla se construye a partir de lo que el
 * dispositivo dice soportar, para no mostrar controles que no harían nada.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
    val output by viewModel.output.collectAsStateWithLifecycle()
    val perOutput by viewModel.perOutput.collectAsStateWithLifecycle()
    val active = settings.enabled

    Box(Modifier.fillMaxSize().background(VortexPalette.Graphite)) {
        LazyColumn(
            contentPadding = PaddingValues(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                bottom = 30.dp + WindowInsets.navigationBars.asPaddingValues()
                    .calculateBottomPadding()
            )
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = "Volver",
                            tint = VortexPalette.TextHigh
                        )
                    }
                    Text(
                        text = "SONIDO",
                        style = MaterialTheme.typography.labelLarge,
                        color = VortexPalette.TextHigh
                    )
                }
            }

            item { MasterSwitch(settings, viewModel::setEnabled) }

            val caps = capabilities
            if (caps == null) {
                item { Notice("Pon algo a sonar para ver lo que admite tu dispositivo.") }
                return@LazyColumn
            }
            if (!caps.hasEqualizer && !caps.hasBassBoost &&
                !caps.hasVirtualizer && !caps.hasBoost
            ) {
                item {
                    Notice(
                        "Ahora mismo reproduce el motor VLC, que no expone una sesión de " +
                            "audio a la que aplicar efectos. Estos ajustes funcionan con " +
                            "Media3, que es el motor habitual."
                    )
                }
                return@LazyColumn
            }

            item { ScopeSelector(settings, caps, viewModel::setScope) }

            item {
                OutputProfileSelector(
                    output = output,
                    perOutput = perOutput,
                    enabled = active,
                    onToggle = viewModel::setPerOutput
                )
            }

            if (caps.advanced) {
                item { LimiterBadge(settings) }
            } else {
                item {
                    Notice(
                        "Tu versión de Android no permite el ecualizador de diez bandas ni " +
                            "el limitador. Se usan los efectos clásicos del sistema, y la " +
                            "amplificación se limita a la mitad para evitar distorsión."
                    )
                }
            }

            if (caps.hasEqualizer) {
                item {
                    EffectHeader(
                        title = "ECUALIZADOR",
                        on = settings.equalizerOn,
                        enabled = active,
                        onToggle = viewModel::toggleEqualizer
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        EqPreset.entries.forEach { preset ->
                            PresetChip(
                                label = preset.label,
                                selected = settings.preset == preset,
                                enabled = active && settings.equalizerOn
                            ) { viewModel.setPreset(preset) }
                        }
                    }
                    EqualizerCurve(
                        gains = settings.effectiveBands,
                        enabled = active && settings.equalizerOn,
                        onGainChange = viewModel::setBand,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                    if (settings.preset == null) {
                        Text(
                            text = "Curva ajustada a mano",
                            style = MaterialTheme.typography.labelSmall,
                            color = VortexPalette.Cyan,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (caps.hasBoost) {
                item {
                    EffectSlider(
                        title = "VOLUMEN EXTRA",
                        hint = if (caps.advanced) {
                            "Sube por encima del máximo del sistema. El limitador recorta " +
                                "los picos por ti, así que gana potencia sin distorsionar."
                        } else {
                            "Sube por encima del máximo del sistema. Sin limitador, pasarse " +
                                "distorsiona."
                        },
                        value = settings.boostDb,
                        max = AudioSettings.MAX_BOOST_DB,
                        readout = { "+%.0f dB".format(it) },
                        on = settings.boostOn,
                        enabled = active,
                        onToggle = viewModel::toggleBoost,
                        onChange = viewModel::setBoost
                    )
                }
            }

            if (caps.hasCompressor) {
                item {
                    EffectSlider(
                        title = "NIVELADOR",
                        hint = "Sube lo flojo sin tocar lo fuerte. Es lo que hace que una " +
                            "grabación pobre suene llena, y lo que más se nota en la calle " +
                            "o con ruido de fondo.",
                        value = settings.compressor,
                        max = 1f,
                        readout = { "${(it * 100).toInt()} %" },
                        on = settings.compressorOn,
                        enabled = active,
                        onToggle = viewModel::toggleCompressor,
                        onChange = viewModel::setCompressor
                    )
                }
            }

            if (caps.hasBassBoost) {
                item {
                    EffectSlider(
                        title = "GRAVES",
                        hint = "Realza el bajo. Con auriculares pequeños se nota mucho; " +
                            "pasado de vueltas emborrona la voz.",
                        value = settings.bassBoost.toFloat(),
                        max = AudioSettings.MAX_STRENGTH.toFloat(),
                        readout = { "${(it * 100 / AudioSettings.MAX_STRENGTH).toInt()} %" },
                        on = settings.bassBoostOn,
                        enabled = active,
                        onToggle = viewModel::toggleBassBoost,
                        onChange = { viewModel.setBassBoost(it.toInt()) }
                    )
                }
            }

            if (caps.hasEqualizer) {
                item {
                    EffectSlider(
                        title = "CLARIDAD",
                        hint = "Realza la presencia entre 2 y 8 kHz, que es donde están las " +
                            "consonantes. Rescata mezclas apagadas; pasado de vueltas, las " +
                            "eses molestan.",
                        value = settings.clarity.toFloat(),
                        max = AudioSettings.MAX_STRENGTH.toFloat(),
                        readout = { "${(it * 100 / AudioSettings.MAX_STRENGTH).toInt()} %" },
                        on = settings.clarityOn,
                        enabled = active,
                        onToggle = viewModel::toggleClarity,
                        onChange = { viewModel.setClarity(it.toInt()) }
                    )
                }
            }

            if (caps.hasVirtualizer) {
                item {
                    EffectSlider(
                        title = "AMBIENTE",
                        hint = "Reverberación corta, de sala pequeña a sala grande. Da aire " +
                            "a grabaciones secas. Es el único efecto que se añade aparte a " +
                            "la cadena, así que si algo sonara raro, empieza por apagarlo.",
                        value = settings.ambience.toFloat(),
                        max = AudioSettings.MAX_STRENGTH.toFloat(),
                        readout = { "${(it * 100 / AudioSettings.MAX_STRENGTH).toInt()} %" },
                        on = settings.ambienceOn,
                        enabled = active,
                        onToggle = viewModel::toggleAmbience,
                        onChange = { viewModel.setAmbience(it.toInt()) }
                    )
                }
            }

            if (caps.hasVirtualizer) {
                item {
                    EffectSlider(
                        title = "ESPACIALIDAD",
                        hint = "Ensancha la imagen estéreo para que no suene metido dentro " +
                            "de la cabeza. Pensado para auriculares; por altavoz apenas cambia.",
                        value = settings.virtualizer.toFloat(),
                        max = AudioSettings.MAX_STRENGTH.toFloat(),
                        readout = { "${(it * 100 / AudioSettings.MAX_STRENGTH).toInt()} %" },
                        on = settings.virtualizerOn,
                        enabled = active,
                        onToggle = viewModel::toggleVirtualizer,
                        onChange = { viewModel.setVirtualizer(it.toInt()) }
                    )
                }
            }

            item {
                Text(
                    text = "RESTABLECER TODO",
                    style = MaterialTheme.typography.labelLarge,
                    color = VortexPalette.Magenta,
                    modifier = Modifier
                        .padding(horizontal = 14.dp, vertical = 20.dp)
                        .clickable(onClick = viewModel::reset)
                        .padding(6.dp)
                )
            }
        }
    }
}

@Composable
private fun MasterSwitch(settings: AudioSettings, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .background(VortexPalette.GraphiteRaised, VortexShapes.medium)
            .border(
                1.dp,
                if (settings.enabled) {
                    VortexPalette.Neon.copy(alpha = 0.45f)
                } else {
                    VortexPalette.Outline
                },
                VortexShapes.medium
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            Icons.Filled.Headphones,
            contentDescription = null,
            tint = if (settings.enabled) VortexPalette.Neon else VortexPalette.TextLow
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = "PROCESADO DE AUDIO",
                style = MaterialTheme.typography.labelLarge,
                color = VortexPalette.TextHigh
            )
            Text(
                text = "Ecualizador, volumen extra, nivelador y espacialidad",
                style = MaterialTheme.typography.bodySmall,
                color = VortexPalette.TextLow
            )
        }
        Switch(
            checked = settings.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = VortexPalette.Graphite,
                checkedTrackColor = VortexPalette.Neon,
                uncheckedThumbColor = VortexPalette.TextLow,
                uncheckedTrackColor = VortexPalette.GraphiteHigh
            )
        )
    }
}

/**
 * Elegir si el procesado afecta sólo a Vórtex o a todo el audio del dispositivo.
 *
 * Lo global se consigue enganchándose a la mezcla de salida del sistema. Android lo
 * permite pero lo tiene marcado como desaconsejado, y hay fabricantes que lo ignoran sin
 * dar error, así que aquí se dice si el dispositivo lo aceptó en vez de darlo por hecho.
 */
@Composable
private fun ScopeSelector(
    settings: AudioSettings,
    caps: AudioCapabilities,
    onScope: (AudioScope) -> Unit
) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
        Text(
            text = "APLICAR A",
            style = MaterialTheme.typography.labelMedium,
            color = VortexPalette.TextLow,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AudioScope.entries.forEach { scope ->
                PresetChip(
                    label = scope.label,
                    selected = settings.scope == scope,
                    enabled = settings.enabled
                ) { onScope(scope) }
            }
        }
        Text(
            text = when {
                settings.scope == AudioScope.VORTEX ->
                    "El procesado sólo afecta a lo que reproduce Vórtex."
                caps.systemWide ->
                    "Tu dispositivo aceptó procesar la salida del sistema: afecta también " +
                        "a YouTube, Spotify y demás apps."
                else ->
                    "Tu dispositivo no ha aceptado procesar la salida del sistema. Android " +
                        "lo desaconseja y algunos fabricantes lo bloquean; sólo se aplicará " +
                        "a Vórtex."
            },
            style = MaterialTheme.typography.bodySmall,
            color = when {
                settings.scope == AudioScope.VORTEX -> VortexPalette.TextLow
                caps.systemWide -> VortexPalette.Cyan
                else -> VortexPalette.Amber
            },
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/**
 * Perfil por salida.
 *
 * El altavoz del móvil y unos auriculares piden curvas distintas: el primero no tiene
 * graves y satura enseguida, los segundos los dan de sobra. Sin esto había que reajustar
 * el ecualizador cada vez que se conectaba algo. Se enseña siempre por dónde está saliendo
 * el sonido, aunque los perfiles estén apagados, porque es la mitad de la explicación de
 * por qué algo suena como suena.
 */
@Composable
private fun OutputProfileSelector(
    output: AudioOutput,
    perOutput: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "SALIDA · ${output.label}",
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) VortexPalette.TextHigh else VortexPalette.TextLow,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = perOutput,
                onCheckedChange = { if (enabled) onToggle(it) },
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = VortexPalette.Graphite,
                    checkedTrackColor = VortexPalette.Neon,
                    uncheckedThumbColor = VortexPalette.TextLow,
                    uncheckedTrackColor = VortexPalette.GraphiteHigh
                )
            )
        }
        Text(
            text = if (perOutput) {
                "Cada salida guarda sus propios ajustes. Estás editando el perfil de " +
                    "${output.label.lowercase()}; al conectar o desconectar algo, Vórtex " +
                    "cambia de perfil solo."
            } else {
                "Los mismos ajustes para todas las salidas. Actívalo si quieres una curva " +
                    "distinta para auriculares y para el altavoz."
            },
            style = MaterialTheme.typography.bodySmall,
            color = VortexPalette.TextLow,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** El limitador no se puede apagar, así que se informa de él en vez de ofrecerlo. */
@Composable
private fun LimiterBadge(settings: AudioSettings) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .background(VortexPalette.GraphiteHigh, VortexShapes.small)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Icon(
            Icons.Filled.Shield,
            contentDescription = null,
            tint = if (settings.enabled) VortexPalette.Cyan else VortexPalette.TextLow,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = "Limitador activo: impide que los picos recorten al amplificar.",
            style = MaterialTheme.typography.bodySmall,
            color = VortexPalette.TextMid
        )
    }
}

@Composable
private fun EffectHeader(
    title: String,
    on: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    readout: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 8.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled && on) VortexPalette.TextHigh else VortexPalette.TextLow,
            modifier = Modifier.weight(1f)
        )
        readout?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled && on) VortexPalette.Neon else VortexPalette.TextLow,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        Switch(
            checked = on,
            onCheckedChange = { onToggle() },
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = VortexPalette.Graphite,
                checkedTrackColor = VortexPalette.Neon,
                uncheckedThumbColor = VortexPalette.TextLow,
                uncheckedTrackColor = VortexPalette.GraphiteHigh
            )
        )
    }
}

@Composable
private fun EffectSlider(
    title: String,
    hint: String,
    value: Float,
    max: Float,
    readout: (Float) -> String,
    on: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onChange: (Float) -> Unit
) {
    Column {
        EffectHeader(
            title = title,
            on = on,
            enabled = enabled,
            onToggle = onToggle,
            readout = if (value > 0f) readout(value) else null
        )
        Slider(
            value = value.coerceIn(0f, max),
            onValueChange = onChange,
            valueRange = 0f..max,
            // Basta con el interruptor maestro. Condicionarlo además al del efecto dejaba
            // el deslizador muerto: para moverlo había que encender antes un efecto que,
            // con el valor a cero, no hacía nada. Mover el deslizador ya lo enciende.
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = VortexPalette.Neon,
                activeTrackColor = VortexPalette.Neon,
                inactiveTrackColor = VortexPalette.Outline
            ),
            modifier = Modifier.padding(horizontal = 14.dp)
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = VortexPalette.TextLow,
            modifier = Modifier.padding(horizontal = 14.dp)
        )
    }
}

@Composable
private fun PresetChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = when {
            !enabled -> VortexPalette.TextLow
            selected -> VortexPalette.Graphite
            else -> VortexPalette.TextMid
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(
                if (selected && enabled) VortexPalette.Neon else VortexPalette.GraphiteHigh,
                VortexShapes.small
            )
            .border(0.5.dp, VortexPalette.Outline, VortexShapes.small)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp)
    )
}

@Composable
private fun Notice(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = VortexPalette.TextLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .background(VortexPalette.GraphiteRaised, VortexShapes.medium)
            .border(0.5.dp, VortexPalette.Outline, VortexShapes.medium)
            .padding(12.dp)
    )
}
