package com.vortex.player.ui.player

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import com.vortex.player.data.MediaEntry
import com.vortex.player.network.NetworkSourceParseResult
import com.vortex.player.network.NetworkSourceParser
import com.vortex.player.playback.PlaybackHub
import com.vortex.player.playback.PlaybackService
import com.vortex.player.popup.PopupService
import com.vortex.player.ui.theme.VortexTheme

class PlayerActivity : FragmentActivity() {

    private val inPip = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        goImmersive()
        // Ver un vídeo no debería apagar la pantalla a los 30 s.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        handleViewIntent(intent)

        setContent {
            VortexTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    PlayerScreen(
                        inPip = inPip.value,
                        onBack = { finish() },
                        onEnterPip = ::enterPipIfPossible,
                        onRequestPopup = ::launchPopup
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    /** Apertura desde otra app ("Abrir con → Vórtex"): la cola es ese único fichero. */
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        PlaybackService.play(
            context = this,
            entries = listOf(externalEntry(uri, intent.type)),
            startIndex = 0
        )
    }

    private fun externalEntry(uri: Uri, mime: String?): MediaEntry {
        val name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        val networkDraft = (NetworkSourceParser.parse(uri.toString(), name)
            as? NetworkSourceParseResult.Valid)?.draft
        return MediaEntry(
            id = uri.hashCode().toLong(),
            uri = uri,
            title = name.ifBlank { "Reproducción externa" },
            displayName = name,
            durationMs = 0L,
            sizeBytes = 0L,
            mimeType = mime ?: "video/*",
            width = 0,
            height = 0,
            folderPath = "",
            folderName = "Externo",
            dateAddedSec = System.currentTimeMillis() / 1000,
            // Sin metadatos fiables asumimos vídeo: si resulta ser audio, el motor
            // simplemente no entrega imagen y la UI ya contempla ese caso.
            isVideo = mime?.startsWith("audio/") != true,
            persistable = networkDraft?.canPersist ?: true
        )
    }

    private fun goImmersive() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun enterPipIfPossible() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val player = PlaybackHub.player.value
        val width = player?.videoSize?.width ?: 16
        val height = player?.videoSize?.height ?: 9
        val ratio = if (width > 0 && height > 0) {
            // Android rechaza relaciones fuera de [0.42, 2.39].
            Rational(width, height).coerceToPipRange()
        } else {
            Rational(16, 9)
        }
        runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(ratio).build()
            )
        }
    }

    private fun Rational.coerceToPipRange(): Rational {
        val value = numerator.toFloat() / denominator
        return when {
            value < 0.42f -> Rational(42, 100)
            value > 2.39f -> Rational(239, 100)
            else -> this
        }
    }

    /** Ventana flotante propia: a diferencia de PiP, sobrevive fuera de la app y es redimensionable. */
    private fun launchPopup() {
        if (PopupService.canDrawOverlays(this)) {
            PopupService.show(this)
            finish()
        } else {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Si sales de la app con el vídeo en marcha, se va a PiP en vez de pararse.
        val playing = PlaybackHub.player.value?.isPlaying == true
        val hasVideo = PlaybackHub.audioOnly.value.not() &&
            (PlaybackHub.player.value?.videoSize?.width ?: 0) > 0
        if (playing && hasVideo && !PlaybackHub.popupVisible.value) enterPipIfPossible()
    }
}
