package com.vortex.player.ui.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import com.vortex.player.BuildConfig
import com.vortex.player.cast.CastCoordinator
import com.vortex.player.data.MediaRepository
import com.vortex.player.playback.PlaybackHub
import com.vortex.player.playback.PlaybackDiagnostics
import com.vortex.player.playback.PlaybackService
import com.vortex.player.subtitle.OnlineSubtitleResult
import com.vortex.player.subtitle.OnlineSubtitleTarget
import com.vortex.player.subtitle.OnlineSubtitleUiState
import com.vortex.player.subtitle.OpenSubtitlesClient
import com.vortex.player.subtitle.OpenSubtitlesKeyStore
import com.vortex.player.subtitle.SubtitleDocument
import com.vortex.player.subtitle.SubtitleDocumentLoader
import com.vortex.player.subtitle.SubtitleLanguageChoice
import com.vortex.player.subtitle.SubtitleTextSize
import com.vortex.player.subtitle.SubtitleTimeline
import com.vortex.player.ui.common.formatDuration
import com.vortex.player.ui.common.rememberPlayerUiState
import com.vortex.player.ui.cast.CastRouteButton
import com.vortex.player.ui.cast.RemoteCastOverlay
import com.vortex.player.ui.queue.QueueActivity
import com.vortex.player.ui.theme.VortexMark
import com.vortex.player.ui.theme.VortexPalette
import com.vortex.player.ui.theme.VortexShapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Cómo se encaja el vídeo en la pantalla, al estilo de VLC.
 *
 * Los tres primeros son modos de encaje relativos al vídeo real; el resto fuerzan una
 * relación fija, que es lo que hace falta cuando un fichero viene con metadatos de
 * aspecto erróneos y sale achatado pese a "ajustar".
 */
enum class AspectPreset(val label: String, val forcedRatio: Float?) {
    FIT("AJUSTAR", null),
    CROP("LLENAR", null),
    STRETCH("ESTIRAR", null),
    R16_9("16:9", 16f / 9f),
    R4_3("4:3", 4f / 3f),
    R18_9("18:9", 2f),
    R21_9("21:9", 21f / 9f),
    R1_1("1:1", 1f)
}

/** Aviso efímero en el centro de la pantalla durante un gesto. */
private data class Feedback(val label: String, val value: Float?, val stamp: Long)

@Composable
@SuppressLint("UnsafeOptInUsageError")
fun PlayerScreen(
    inPip: Boolean,
    onBack: () -> Unit,
    onEnterPip: () -> Unit,
    onRequestPopup: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity

    val player by PlaybackHub.player.collectAsStateWithLifecycle()
    val queue by PlaybackHub.queue.collectAsStateWithLifecycle()
    val queueIndex by PlaybackHub.currentIndex.collectAsStateWithLifecycle()
    val mediaRepository = remember(context) { MediaRepository.get(context) }
    val library by mediaRepository.library.collectAsStateWithLifecycle()
    val controls by PlaybackHub.controls.collectAsStateWithLifecycle()
    val emptyDiagnostics = remember { MutableStateFlow(PlaybackDiagnostics()) }
    val diagnostics by (controls?.diagnostics ?: emptyDiagnostics).collectAsStateWithLifecycle()
    val entry by PlaybackHub.currentEntry.collectAsStateWithLifecycle()
    val audioOnly by PlaybackHub.audioOnly.collectAsStateWithLifecycle()
    val repeat by PlaybackHub.repeat.collectAsStateWithLifecycle()
    val shuffle by PlaybackHub.shuffle.collectAsStateWithLifecycle()
    val castState by CastCoordinator.state.collectAsStateWithLifecycle()
    val uiState by rememberPlayerUiState(player)

    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var preset by remember { mutableStateOf(AspectPreset.FIT) }
    var userZoom by remember { mutableStateOf(1f) }
    var feedback by remember { mutableStateOf<Feedback?>(null) }
    var seekPreviewMs by remember { mutableStateOf<Long?>(null) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    val subtitleScope = rememberCoroutineScope()
    var primarySubtitleName by remember(entry?.id) { mutableStateOf<String?>(null) }
    var secondarySubtitle by remember(entry?.id) { mutableStateOf<SubtitleDocument?>(null) }
    var subtitleDelayMs by remember(entry?.id) { mutableLongStateOf(0L) }
    var secondaryDelayMs by remember(entry?.id) { mutableLongStateOf(0L) }
    var secondaryTextSize by remember(entry?.id) { mutableStateOf(SubtitleTextSize.MEDIUM) }
    var secondaryBackground by remember(entry?.id) { mutableStateOf(true) }
    var subtitleStatus by remember(entry?.id) { mutableStateOf<String?>(null) }
    val openSubtitlesClient = remember { OpenSubtitlesClient(BuildConfig.VERSION_NAME) }
    var onlineConfigured by remember { mutableStateOf(OpenSubtitlesKeyStore.read(context) != null) }
    var onlineApiKeyDraft by remember { mutableStateOf("") }
    var onlineQuery by remember(entry?.id) { mutableStateOf(entry?.title.orEmpty()) }
    var onlineLanguage by remember { mutableStateOf(SubtitleLanguageChoice.SPANISH) }
    var onlineSearching by remember { mutableStateOf(false) }
    var onlineDownloadingId by remember { mutableStateOf<Long?>(null) }
    var onlineResults by remember(entry?.id) { mutableStateOf(emptyList<OnlineSubtitleResult>()) }

    LaunchedEffect(castState.connected) {
        if (castState.connected) {
            panel = null
            controlsVisible = true
        }
    }

    val primarySubtitleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        subtitleScope.launch {
            subtitleStatus = "CARGANDO SUBTÍTULO…"
            runCatching { SubtitleDocumentLoader.load(context, uri) }
                .onSuccess { document ->
                    controls?.addExternalSubtitle(document.localUri.toString())
                    primarySubtitleName = document.displayName
                    subtitleStatus = "PISTA PRINCIPAL CARGADA"
                }
                .onFailure { error ->
                    subtitleStatus = "ERROR · ${error.message ?: "No se pudo abrir el archivo"}"
                }
        }
    }
    val secondarySubtitleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        subtitleScope.launch {
            subtitleStatus = "CARGANDO SEGUNDO SUBTÍTULO…"
            runCatching {
                SubtitleDocumentLoader.load(context, uri).also {
                    require(it.cues.isNotEmpty()) {
                        "El segundo subtítulo debe ser un SRT o WebVTT válido"
                    }
                }
            }.onSuccess { document ->
                secondarySubtitle = document
                secondaryDelayMs = 0L
                subtitleStatus = "SEGUNDO SUBTÍTULO CARGADO"
            }.onFailure { error ->
                subtitleStatus = "ERROR · ${error.message ?: "No se pudo leer el archivo"}"
            }
        }
    }

    LaunchedEffect(entry?.id, controls) {
        subtitleDelayMs = controls?.subtitleDelayMs ?: 0L
    }

    // Los controles se esconden solos, pero nunca mientras hay un panel abierto.
    LaunchedEffect(controlsVisible, uiState.isPlaying, panel) {
        if (controlsVisible && uiState.isPlaying && panel == null) {
            delay(3_500)
            controlsVisible = false
        }
    }

    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(900)
            feedback = null
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {

        VideoSurface(
            audioOnly = audioOnly,
            preset = preset,
            userZoom = userZoom,
            videoAspect = uiState.aspectRatio,
            modifier = Modifier.fillMaxSize()
        )

        secondarySubtitle?.let { document ->
            SecondarySubtitleOverlay(
                document = document,
                positionMs = uiState.positionMs - secondaryDelayMs,
                textSize = secondaryTextSize,
                background = secondaryBackground
            )
        }

        if (audioOnly) {
            AudioOnlyBackdrop(
                title = entry?.title.orEmpty(),
                playing = uiState.isPlaying
            )
        }

        if (!inPip && !castState.connected) {
            GestureLayer(
                locked = locked,
                onToggleControls = { controlsVisible = !controlsVisible },
                onSeekBy = { deltaMs ->
                    val p = player ?: return@GestureLayer
                    val target = (p.currentPosition + deltaMs)
                        .coerceIn(0L, uiState.durationMs.coerceAtLeast(0L))
                    p.seekTo(target)
                    feedback = Feedback(
                        label = (if (deltaMs > 0) "+" else "") + (deltaMs / 1000) + "s",
                        value = null,
                        stamp = System.nanoTime()
                    )
                },
                onSeekDrag = { deltaMs, committed ->
                    val p = player ?: return@GestureLayer
                    val base = seekPreviewMs ?: p.currentPosition
                    val target = (base + deltaMs).coerceIn(0L, uiState.durationMs.coerceAtLeast(0L))
                    if (committed) {
                        p.seekTo(target)
                        seekPreviewMs = null
                    } else {
                        seekPreviewMs = target
                    }
                },
                onBrightness = { delta ->
                    activity ?: return@GestureLayer
                    val level = adjustBrightness(activity, delta)
                    feedback = Feedback("BRILLO", level, System.nanoTime())
                },
                onVolume = { delta ->
                    val level = adjustVolume(context, delta)
                    feedback = Feedback("VOLUMEN", level, System.nanoTime())
                },
                onZoom = { factor ->
                    userZoom = (userZoom * factor).coerceIn(0.5f, 3f)
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Vista previa de búsqueda mientras se arrastra en horizontal.
        seekPreviewMs?.let { preview ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = formatDuration(preview) + "  /  " + formatDuration(uiState.durationMs),
                    style = MaterialTheme.typography.headlineMedium,
                    color = VortexPalette.TextHigh,
                    modifier = Modifier
                        .background(VortexPalette.Graphite.copy(alpha = 0.82f), VortexShapes.medium)
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                )
            }
        }

        feedback?.let { fb -> FeedbackHud(fb.label, fb.value) }

        if (!inPip && castState.connected) {
            RemoteCastOverlay(state = castState, onBack = onBack)
        } else if (!inPip) {
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                ControlsOverlay(
                    title = entry?.title.orEmpty(),
                    engineName = (controls?.engineName ?: "—") +
                        if (controls != null) " · ${diagnostics.decoder.shortLabel}" else "",
                    positionMs = uiState.positionMs,
                    durationMs = uiState.durationMs,
                    bufferedProgress = uiState.bufferedProgress,
                    isPlaying = uiState.isPlaying,
                    isBuffering = uiState.isBuffering,
                    locked = locked,
                    audioOnly = audioOnly,
                    speed = uiState.speed,
                    preset = preset,
                    repeat = repeat,
                    shuffle = shuffle,
                    queueCount = queue.size,
                    onBack = onBack,
                    onToggleLock = { locked = !locked },
                    onPlayPause = { PlaybackService.togglePlayPause(context) },
                    onSeekTo = { fraction ->
                        val p = player ?: return@ControlsOverlay
                        if (uiState.durationMs > 0) {
                            p.seekTo((uiState.durationMs * fraction).toLong())
                        }
                    },
                    onPrevious = { player?.seekToPreviousMediaItem() },
                    onNext = { player?.seekToNextMediaItem() },
                    onCycleRepeat = { PlaybackService.cycleRepeat() },
                    onToggleShuffle = { PlaybackService.toggleShuffle() },
                    onToggleAudioOnly = { PlaybackService.setAudioOnly(!audioOnly) },
                    onOpenAspect = { panel = Panel.ASPECT },
                    onOpenQueue = { QueueActivity.open(context) },
                    onOpenPanel = { panel = it },
                    onPip = onEnterPip,
                    onPopup = onRequestPopup,
                    castButton = { CastRouteButton() }
                )
            }
        }

        panel?.takeIf { !castState.connected }?.let { open ->
            PlayerPanel(
                panel = open,
                onDismiss = { panel = null },
                onSpeed = { value ->
                    player?.setPlaybackSpeed(value)
                    panel = null
                },
                onSleep = { minutes ->
                    PlaybackService.setSleepTimer(minutes?.let { it * 60_000L })
                    panel = null
                },
                onSelectAudio = { id ->
                    controls?.selectAudioTrack(id)
                    panel = null
                },
                onSelectSubtitle = { id ->
                    controls?.selectSubtitleTrack(id)
                    primarySubtitleName = null
                    subtitleStatus = if (id == null) {
                        "SUBTÍTULOS PRINCIPALES DESACTIVADOS"
                    } else {
                        "PISTA PRINCIPAL SELECCIONADA"
                    }
                },
                primarySubtitleName = primarySubtitleName,
                subtitleDelayMs = subtitleDelayMs,
                secondarySubtitleName = secondarySubtitle?.displayName,
                secondaryDelayMs = secondaryDelayMs,
                secondaryTextSize = secondaryTextSize,
                secondaryBackground = secondaryBackground,
                subtitleStatus = subtitleStatus,
                onlineSubtitleState = OnlineSubtitleUiState(
                    configured = onlineConfigured,
                    apiKeyDraft = onlineApiKeyDraft,
                    query = onlineQuery,
                    language = onlineLanguage,
                    searching = onlineSearching,
                    downloadingFileId = onlineDownloadingId,
                    results = onlineResults
                ),
                onLoadPrimarySubtitle = { primarySubtitleLauncher.launch(SUBTITLE_MIME_TYPES) },
                onLoadSecondarySubtitle = { secondarySubtitleLauncher.launch(SUBTITLE_MIME_TYPES) },
                onRemoveSecondarySubtitle = {
                    secondarySubtitle = null
                    subtitleStatus = "SEGUNDO SUBTÍTULO QUITADO"
                },
                onAdjustSubtitleDelay = { delta ->
                    val next = (subtitleDelayMs + delta).coerceIn(-60_000L, 60_000L)
                    controls?.setSubtitleDelayMs(next)
                    subtitleDelayMs = controls?.subtitleDelayMs ?: next
                },
                onAdjustSecondaryDelay = { delta ->
                    secondaryDelayMs = (secondaryDelayMs + delta).coerceIn(-60_000L, 60_000L)
                },
                onCycleSecondaryTextSize = {
                    val values = SubtitleTextSize.entries
                    secondaryTextSize = values[(secondaryTextSize.ordinal + 1) % values.size]
                },
                onToggleSecondaryBackground = {
                    secondaryBackground = !secondaryBackground
                },
                onOnlineApiKeyChange = { onlineApiKeyDraft = it.take(256) },
                onSaveOnlineApiKey = {
                    runCatching { OpenSubtitlesKeyStore.write(context, onlineApiKeyDraft) }
                        .onSuccess {
                            onlineConfigured = true
                            onlineApiKeyDraft = ""
                            subtitleStatus = "API KEY DE OPENSUBTITLES GUARDADA Y CIFRADA"
                        }
                        .onFailure { error ->
                            subtitleStatus = "ERROR · ${error.message ?: "No se pudo guardar la API key"}"
                        }
                },
                onClearOnlineApiKey = {
                    OpenSubtitlesKeyStore.clear(context)
                    onlineConfigured = false
                    onlineResults = emptyList()
                    onlineApiKeyDraft = ""
                    subtitleStatus = "API KEY DE OPENSUBTITLES ELIMINADA"
                },
                onOnlineQueryChange = { onlineQuery = it.take(160) },
                onCycleOnlineLanguage = {
                    val values = SubtitleLanguageChoice.entries
                    onlineLanguage = values[(onlineLanguage.ordinal + 1) % values.size]
                },
                onSearchOnlineSubtitles = {
                    subtitleScope.launch {
                        onlineSearching = true
                        subtitleStatus = "BUSCANDO EN OPENSUBTITLES…"
                        runCatching {
                            val key = OpenSubtitlesKeyStore.read(context)
                                ?: error("Configura primero tu API key de OpenSubtitles")
                            openSubtitlesClient.search(key, onlineQuery, onlineLanguage)
                        }.onSuccess { results ->
                            onlineResults = results
                            subtitleStatus = if (results.isEmpty()) {
                                "SIN RESULTADOS · PRUEBA OTRO TÍTULO O IDIOMA"
                            } else {
                                "${results.size} RESULTADOS EN OPENSUBTITLES"
                            }
                        }.onFailure { error ->
                            onlineResults = emptyList()
                            subtitleStatus = "ERROR · ${error.message ?: "Falló la búsqueda online"}"
                        }
                        onlineSearching = false
                    }
                },
                onDownloadOnlineSubtitle = { result, target ->
                    if (onlineDownloadingId == null) {
                        subtitleScope.launch {
                            onlineDownloadingId = result.fileId
                            subtitleStatus = "DESCARGANDO ${result.language.uppercase()}…"
                            runCatching {
                                val key = OpenSubtitlesKeyStore.read(context)
                                    ?: error("Configura primero tu API key de OpenSubtitles")
                                val download = openSubtitlesClient.download(key, result.fileId)
                                val document = SubtitleDocumentLoader.fromBytes(
                                    context = context,
                                    displayName = download.fileName,
                                    bytes = download.bytes
                                )
                                if (target == OnlineSubtitleTarget.SECONDARY) {
                                    require(document.cues.isNotEmpty()) {
                                        "El segundo subtítulo descargado no es SRT/WebVTT válido"
                                    }
                                }
                                Triple(document, download.remaining, target)
                            }.onSuccess { (document, remaining, destination) ->
                                if (destination == OnlineSubtitleTarget.PRIMARY) {
                                    controls?.addExternalSubtitle(document.localUri.toString())
                                    primarySubtitleName = document.displayName
                                } else {
                                    secondarySubtitle = document
                                    secondaryDelayMs = 0L
                                }
                                val quota = remaining?.let { " · $it DESCARGAS RESTANTES" }.orEmpty()
                                subtitleStatus = if (destination == OnlineSubtitleTarget.PRIMARY) {
                                    "SUBTÍTULO ONLINE CARGADO COMO PRINCIPAL$quota"
                                } else {
                                    "SUBTÍTULO ONLINE CARGADO COMO SEGUNDO$quota"
                                }
                            }.onFailure { error ->
                                subtitleStatus = "ERROR · ${error.message ?: "Falló la descarga del subtítulo"}"
                            }
                            onlineDownloadingId = null
                        }
                    }
                },
                diagnostics = diagnostics,
                onRetrySafeMode = {
                    controls?.retryInSafeMode()
                    subtitleStatus = null
                },
                currentPreset = preset,
                onSelectAspect = { chosen ->
                    preset = chosen
                    // Un preset nuevo parte de cero: mezclar zoom manual y encaje
                    // automático es la receta para no volver a encuadrar bien.
                    userZoom = 1f
                    panel = null
                    feedback = Feedback(chosen.label, null, System.nanoTime())
                }
            )
        }
    }

}

@Composable
private fun SecondarySubtitleOverlay(
    document: SubtitleDocument,
    positionMs: Long,
    textSize: SubtitleTextSize,
    background: Boolean
) {
    val active = SubtitleTimeline.activeText(document.cues, positionMs) ?: return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 104.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Text(
            text = active,
            color = Color.White,
            fontSize = (22f * textSize.scale).sp,
            style = MaterialTheme.typography.titleMedium.copy(
                shadow = Shadow(color = Color.Black, blurRadius = 7f)
            ),
            modifier = if (background) {
                Modifier
                    .background(Color.Black.copy(alpha = 0.68f), VortexShapes.small)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            } else {
                Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
            }
        )
    }
}

private val SUBTITLE_MIME_TYPES = arrayOf(
    "application/x-subrip",
    "text/vtt",
    "text/plain",
    "application/octet-stream"
)

/**
 * Superficie de vídeo. [AspectRatioFrameLayout] conserva el encuadre elegido y dentro se
 * monta la única salida disponible, [org.videolan.libvlc.util.VLCVideoLayout].
 */
@SuppressLint("UnsafeOptInUsageError")
@Composable
private fun VideoSurface(
    audioOnly: Boolean,
    preset: AspectPreset,
    userZoom: Float,
    videoAspect: Float,
    modifier: Modifier = Modifier
) {
    val controls by PlaybackHub.controls.collectAsStateWithLifecycle()

    // El motor ya enganchado, guardado fuera del sistema de estado: si fuera `State`,
    // escribirlo desde `update` provocaría otra recomposición y con ella un ciclo de
    // enganche/desenganche que deja la superficie muerta y el vídeo en negro.
    val attachedTo = remember { arrayOfNulls<Any>(1) }

    // La clave debe ser el motor real. Si la primera composición ocurre antes de que el
    // servicio publique sus controles, capturar `null` con una clave constante impediría
    // soltar la superficie al salir y VLC intentaría volver sobre una vista destruida.
    DisposableEffect(controls) {
        onDispose {
            controls?.detachVideoOutput()
            attachedTo[0] = null
        }
    }

    if (audioOnly) return

    AndroidView(
        factory = { ctx -> AspectRatioFrameLayout(ctx) },
        update = { view ->
            val engine = controls
            if (attachedTo[0] !== engine) {
                engine?.attachVideoOutput(view)
                attachedTo[0] = engine
            }
            view.setAspectRatio(preset.forcedRatio ?: videoAspect)
            view.resizeMode = when (preset) {
                AspectPreset.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                AspectPreset.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        // El zoom por pellizco se aplica encima del modo de encaje, no en su lugar.
        modifier = modifier.graphicsLayer {
            scaleX = userZoom
            scaleY = userZoom
        }
    )
}

/** Fondo del modo solo-audio: sin superficie de vídeo, sólo la marca latiendo. */
@Composable
private fun AudioOnlyBackdrop(title: String, playing: Boolean) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(VortexPalette.GraphiteRaised, Color.Black)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            VortexMark(modifier = Modifier.size(150.dp), spinning = playing)
            Text(
                text = "SOLO AUDIO",
                style = MaterialTheme.typography.labelMedium,
                color = VortexPalette.Cyan,
                modifier = Modifier.padding(top = 26.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = VortexPalette.TextMid,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp, start = 40.dp, end = 40.dp)
            )
        }
    }
}

/**
 * Capa de gestos. Mitad izquierda: brillo. Mitad derecha: volumen. Arrastre horizontal:
 * búsqueda. Doble toque lateral: ±10 s. Con el bloqueo activo todo queda inerte salvo
 * el toque que devuelve el candado.
 */
@Composable
private fun GestureLayer(
    locked: Boolean,
    onToggleControls: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekDrag: (Long, Boolean) -> Unit,
    onBrightness: (Float) -> Unit,
    onVolume: (Float) -> Unit,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragAxis by remember { mutableStateOf(DragAxis.NONE) }
    var horizontalAccum by remember { mutableStateOf(0f) }

    Box(
        modifier
            .pointerInput(locked) {
                detectTapGestures(
                    onTap = { onToggleControls() },
                    onDoubleTap = { offset ->
                        if (locked) return@detectTapGestures
                        val third = size.width / 3f
                        when {
                            offset.x < third -> onSeekBy(-10_000L)
                            offset.x > third * 2 -> onSeekBy(10_000L)
                            else -> onToggleControls()
                        }
                    }
                )
            }
            .pointerInput(locked) {
                if (locked) return@pointerInput
                detectDragGestures(
                    onDragStart = {
                        dragAxis = DragAxis.NONE
                        horizontalAccum = 0f
                    },
                    onDragEnd = {
                        if (dragAxis == DragAxis.HORIZONTAL) onSeekDrag(0L, true)
                        dragAxis = DragAxis.NONE
                    },
                    onDrag = { change, drag ->
                        change.consume()
                        // El eje se fija en el primer movimiento claro y no cambia hasta
                        // soltar; si no, el gesto "tiembla" entre buscar y subir volumen.
                        if (dragAxis == DragAxis.NONE) {
                            dragAxis = if (abs(drag.x) > abs(drag.y)) {
                                DragAxis.HORIZONTAL
                            } else {
                                DragAxis.VERTICAL
                            }
                        }
                        when (dragAxis) {
                            DragAxis.HORIZONTAL -> {
                                horizontalAccum += drag.x
                                // Toda la anchura de la pantalla equivale a 90 s de búsqueda.
                                val deltaMs = (horizontalAccum / size.width * 90_000f).toLong()
                                onSeekDrag(deltaMs, false)
                            }
                            DragAxis.VERTICAL -> {
                                val delta = -drag.y / size.height
                                if (change.position.x < size.width / 2f) {
                                    onBrightness(delta)
                                } else {
                                    onVolume(delta)
                                }
                            }
                            DragAxis.NONE -> Unit
                        }
                    }
                )
            }
    )
}

private enum class DragAxis { NONE, HORIZONTAL, VERTICAL }

/** Devuelve el nivel resultante en 0..1 para poder pintarlo. */
private fun adjustBrightness(activity: Activity, delta: Float): Float {
    val params: WindowManager.LayoutParams = activity.window.attributes
    val current = params.screenBrightness.takeIf { it >= 0f } ?: 0.5f
    val next = (current + delta).coerceIn(0.01f, 1f)
    params.screenBrightness = next
    activity.window.attributes = params
    return next
}

private fun adjustVolume(context: Context, delta: Float): Float {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    val next = (current + delta * max).toInt().coerceIn(0, max)
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
    return next.toFloat() / max
}

@Composable
private fun FeedbackHud(label: String, value: Float?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(VortexPalette.Graphite.copy(alpha = 0.85f), VortexShapes.medium)
                .padding(horizontal = 22.dp, vertical = 14.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = VortexPalette.TextMid)
            if (value != null) {
                Text(
                    text = "${(value * 100).toInt()}%",
                    style = MaterialTheme.typography.headlineMedium,
                    color = VortexPalette.Neon,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Box(
                    Modifier
                        .padding(top = 8.dp)
                        .width(120.dp)
                        .height(3.dp)
                        .background(VortexPalette.Outline)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(value)
                            .fillMaxSize()
                            .background(VortexPalette.Neon)
                    )
                }
            }
        }
    }
}
