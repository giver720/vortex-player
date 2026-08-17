package com.vortex.player.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Por dónde está saliendo el sonido, agrupado en las tres familias que de verdad piden
 * curvas distintas.
 *
 * No se distingue entre unos auriculares Bluetooth y otros: lo que cambia radicalmente el
 * sonido es el tipo de salida, no el modelo. El altavoz del móvil no tiene graves y satura
 * enseguida; unos auriculares por cable los tienen de sobra. Guardar el mismo ecualizador
 * para ambos obliga a reajustarlo cada vez que se conecta algo, que es justo lo que esto
 * viene a evitar.
 */
enum class AudioOutput(val label: String) {
    SPEAKER("ALTAVOZ"),
    WIRED("CABLE"),
    BLUETOOTH("BLUETOOTH");

    companion object {

        private fun familyOf(type: Int): AudioOutput? = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_HEARING_AID -> BLUETOOTH

            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> WIRED

            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> SPEAKER

            else -> null
        }

        /**
         * Salida activa para música.
         *
         * En Android 12 y posteriores se pregunta directamente por dónde saldría un audio
         * con atributos de música, que es la respuesta exacta. Antes de eso no hay forma
         * de preguntarlo, así que se deduce de lo que haya conectado siguiendo el orden en
         * que Android encamina el sonido: si hay Bluetooth manda el Bluetooth, luego el
         * cable, y el altavoz sólo cuando no queda otra.
         */
        fun current(context: Context): AudioOutput {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return SPEAKER

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                runCatching { manager.getAudioDevicesForAttributes(attributes) }
                    .getOrNull()
                    ?.firstNotNullOfOrNull { familyOf(it.type) }
                    ?.let { return it }
            }

            val connected = runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
                .getOrNull()
                .orEmpty()
                .mapNotNull { familyOf(it.type) }
                .toSet()

            return when {
                BLUETOOTH in connected -> BLUETOOTH
                WIRED in connected -> WIRED
                else -> SPEAKER
            }
        }

        /**
         * La salida activa, y cada cambio posterior.
         *
         * Se avisa por conexión y desconexión de dispositivos porque es lo que Android
         * notifica; el `distinctUntilChanged` evita rehacer la cadena de efectos cuando lo
         * que cambia no altera la familia de salida —enchufar un segundo cacharro USB, por
         * ejemplo—, que sería reengancharlo todo para nada y con un corte audible.
         */
        fun observe(context: Context): Flow<AudioOutput> = callbackFlow {
            trySend(current(context))
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (manager == null) {
                awaitClose { }
                return@callbackFlow
            }
            val callback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    trySend(current(context))
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    trySend(current(context))
                }
            }
            manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
            awaitClose { runCatching { manager.unregisterAudioDeviceCallback(callback) } }
        }.distinctUntilChanged()
    }
}
