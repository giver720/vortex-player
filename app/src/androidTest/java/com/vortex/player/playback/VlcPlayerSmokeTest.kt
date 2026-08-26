package com.vortex.player.playback

import android.content.Context
import android.net.Uri
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vortex.player.audio.AudioSettings
import com.vortex.player.audio.EqPreset
import com.vortex.player.ui.player.PlayerActivity
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun h264AndHevcProduceAConfirmedVideoFrame() {
        assertFixtureProducesFrame("vortex-h264.mp4")
        assertFixtureProducesFrame("vortex-hevc.mp4")
    }

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
            // Buffering puede oscilar después de READY en el emulador, pero la intención
            // de reproducción y la ausencia de error deben mantenerse.
            assertTrue(player.playWhenReady)
            assertEquals(200, player.appliedVlcVolumePercent)
            player.release()
        }
    }

    @Test
    fun queueReorderKeepsTheActiveVlcMediaOpen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = File(context.cacheDir, "vlc-queue-first.wav").also(::writeSilentWav)
        val second = File(context.cacheDir, "vlc-queue-second.wav").also(::writeSilentWav)
        val firstItem = MediaItem.Builder()
            .setMediaId("first")
            .setUri(first.toURI().toString())
            .build()
        val secondItem = MediaItem.Builder()
            .setMediaId("second")
            .setUri(second.toURI().toString())
            .build()
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
            player.setMediaItems(listOf(firstItem, secondItem), 0, 0L)
            player.prepare()
            player.play()
        }

        assertTrue("VLC no llegó a READY en 10 segundos", ready.await(10, TimeUnit.SECONDS))
        assertNull(error.get())

        val switchedReady = CountDownLatch(1)
        onMain {
            val stateBeforeReorder = player.playbackState
            val playIntentBeforeReorder = player.playWhenReady
            assertTrue(player.replacePlaylistPreservingCurrent(listOf(secondItem, firstItem), 1))
            assertEquals("first", player.currentMediaItem?.mediaId)
            assertEquals(1, player.currentMediaItemIndex)
            assertEquals(stateBeforeReorder, player.playbackState)
            assertEquals(playIntentBeforeReorder, player.playWhenReady)

            assertFalse(player.replacePlaylistPreservingCurrent(listOf(secondItem), 0))
            assertEquals("first", player.currentMediaItem?.mediaId)
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) switchedReady.countDown()
                }
            })
            player.setMediaItem(secondItem)
            player.prepare()
            player.play()
        }

        assertTrue("VLC no abrió el siguiente medio", switchedReady.await(10, TimeUnit.SECONDS))
        assertNull(error.get())
        onMain {
            assertEquals("second", player.currentMediaItem?.mediaId)
            player.release()
        }
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private fun assertFixtureProducesFrame(assetName: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val media = File(context.cacheDir, assetName).also { output ->
            context.assets.open(assetName).use { input ->
                output.outputStream().use(input::copyTo)
            }
        }
        val firstFrame = CountDownLatch(1)
        val error = AtomicReference<PlaybackException?>()

        ActivityScenario.launch(PlayerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val output = FrameLayout(activity)
                activity.setContentView(output)
                val player = VlcPlayer(activity)
                activity.lifecycleScope.launch {
                    player.videoOutputReady.filter { it }.first()
                    firstFrame.countDown()
                }
                player.addListener(object : Player.Listener {
                    override fun onPlayerError(playbackError: PlaybackException) {
                        error.set(playbackError)
                        firstFrame.countDown()
                    }
                })
                player.attachVideoOutput(output)
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(media)))
                player.prepare()
                player.play()
                activity.lifecycle.addObserver(
                    object : androidx.lifecycle.DefaultLifecycleObserver {
                        override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                            player.release()
                        }
                    }
                )
            }

            assertTrue(
                "$assetName no produjo un fotograma confirmado",
                firstFrame.await(15, TimeUnit.SECONDS)
            )
            assertNull(error.get())
        }
    }

    /** Tres segundos de PCM mono 16-bit a 8 kHz, evitando que termine entre READY y la aserción. */
    private fun writeSilentWav(file: File) {
        val sampleRate = 8_000
        val dataSize = sampleRate * 2 * 3
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
