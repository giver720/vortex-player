package com.vortex.player.playback

import android.content.Context
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Traduce el modelo de pistas de ExoPlayer al vocabulario de [EngineControls].
 *
 * Los identificadores son `g<grupo>t<pista>` porque ExoPlayer no da un id estable por
 * pista; el par (grupo, índice) sí lo es dentro de un mismo medio.
 */
@UnstableApi
class ExoEngineControls(
    private val context: Context,
    private val player: ExoPlayer
) : EngineControls {

    private var surfaceView: SurfaceView? = null
    private var videoEnabled: Boolean = true

    override val engineName: String = "Media3"

    override val audioTracks: List<TrackOption>
        get() = optionsFor(C.TRACK_TYPE_AUDIO)

    override val subtitleTracks: List<TrackOption>
        get() = optionsFor(C.TRACK_TYPE_TEXT)

    private fun optionsFor(trackType: Int): List<TrackOption> {
        val out = mutableListOf<TrackOption>()
        player.currentTracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != trackType) return@forEachIndexed
            for (trackIndex in 0 until group.length) {
                if (!group.isTrackSupported(trackIndex)) continue
                val format = group.getTrackFormat(trackIndex)
                out += TrackOption(
                    id = "g${groupIndex}t$trackIndex",
                    label = trackLabel(format.language, format.label, out.size, trackType),
                    language = format.language,
                    selected = group.isTrackSelected(trackIndex)
                )
            }
        }
        return out
    }

    private fun trackLabel(
        language: String?,
        label: String?,
        ordinal: Int,
        trackType: Int
    ): String {
        label?.takeIf { it.isNotBlank() }?.let { return it }
        language?.takeIf { it.isNotBlank() && it != "und" }?.let { return it.uppercase() }
        val kind = if (trackType == C.TRACK_TYPE_AUDIO) "Audio" else "Subtítulo"
        return "$kind ${ordinal + 1}"
    }

    override fun selectAudioTrack(id: String) = applyOverride(id, C.TRACK_TYPE_AUDIO)

    override fun selectSubtitleTrack(id: String?) {
        if (id == null) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        } else {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
            applyOverride(id, C.TRACK_TYPE_TEXT)
        }
    }

    private fun applyOverride(id: String, trackType: Int) {
        val (groupIndex, trackIndex) = parseId(id) ?: return
        val group: Tracks.Group = player.currentTracks.groups.getOrNull(groupIndex) ?: return
        if (group.type != trackType) return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
    }

    private fun parseId(id: String): Pair<Int, Int>? {
        val match = Regex("""g(\d+)t(\d+)""").matchEntire(id) ?: return null
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }

    override val isVideoEnabled: Boolean get() = videoEnabled

    /**
     * Aquí está el truco del "MP4 como MP3" en Media3: se desactiva el tipo de pista
     * de vídeo en el selector, así que el decodificador de vídeo ni se instancia.
     * El audio sigue exactamente donde estaba, sin recolocar la posición.
     */
    override fun setVideoEnabled(enabled: Boolean) {
        videoEnabled = enabled
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
            .build()
        if (!enabled) detachVideoOutput()
    }

    override fun attachVideoOutput(container: FrameLayout) {
        if (!videoEnabled) return
        detachVideoOutput()
        val view = SurfaceView(context).also { surfaceView = it }
        container.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        player.setVideoSurfaceView(view)
    }

    override fun detachVideoOutput() {
        val view = surfaceView ?: return
        player.clearVideoSurfaceView(view)
        (view.parent as? ViewGroup)?.removeView(view)
        surfaceView = null
    }

    override fun addExternalSubtitle(uri: String) {
        val current = player.currentMediaItem ?: return
        val subtitle = androidx.media3.common.MediaItem.SubtitleConfiguration
            .Builder(android.net.Uri.parse(uri))
            .setMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val position = player.currentPosition
        player.setMediaItem(
            current.buildUpon().setSubtitleConfigurations(listOf(subtitle)).build(),
            position
        )
        player.prepare()
    }
}
