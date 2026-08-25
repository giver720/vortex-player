package com.vortex.player.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vortex.player.audio.AudioSettings
import com.vortex.player.audio.EqPreset
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Smoke test nativo: abre PCM real, aplica EQ y atraviesa prepare/play/release en libVLC. */
@RunWith(AndroidJUnit4::class)
class VlcPlayerSmokeTest {

    @Test
    fun localAudioPreparesPlaysAndAcceptsEqualizer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val media = File(context.cacheDir, "vlc-smoke.wav").also(::writeSilentWav)
        val ready = CountDownLatch(1)
        val error = AtomicReference<PlaybackException?>()
        lateinit var player: VlcPlayer

        onMain {
            player = VlcPlayer(context)
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) ready.countDown()
                }

                override fun onPlayerError(playbackError: PlaybackException) {
                    error.set(playbackError)
                    ready.countDown()
                }
            })
            player.applyAudioSettings(
                AudioSettings(
                    enabled = true,
                    preset = EqPreset.ROCK,
                    boostOn = true,
                    boostDb = 6f,
                    limiterOn = true
                )
            )
            player.setMediaItem(MediaItem.fromUri(media.toURI().toString()))
            player.prepare()
            player.play()
        }

        assertTrue("VLC no llegó a READY en 10 segundos", ready.await(10, TimeUnit.SECONDS))
        assertNull(error.get())

        onMain {
            assertTrue(player.playbackState == Player.STATE_READY || player.isPlaying)
            assertEquals(200, player.appliedVlcVolumePercent)
            player.release()
        }
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    /** Un segundo de PCM mono 16-bit a 8 kHz, suficiente para probar el decodificador. */
    private fun writeSilentWav(file: File) {
        val sampleRate = 8_000
        val dataSize = sampleRate * 2
        FileOutputStream(file).use { output ->
            fun int32(value: Int) = output.write(
                byteArrayOf(
                    value.toByte(),
                    (value shr 8).toByte(),
                    (value shr 16).toByte(),
                    (value shr 24).toByte()
                )
            )
            fun int16(value: Int) = output.write(
                byteArrayOf(value.toByte(), (value shr 8).toByte())
            )
            output.write("RIFF".toByteArray())
            int32(36 + dataSize)
            output.write("WAVEfmt ".toByteArray())
            int32(16)
            int16(1)
            int16(1)
            int32(sampleRate)
            int32(sampleRate * 2)
            int16(2)
            int16(16)
            output.write("data".toByteArray())
            int32(dataSize)
            output.write(ByteArray(dataSize))
        }
    }
}
