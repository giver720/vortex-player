package com.vortex.player.ui.common

import android.media.MediaMetadataRetriever
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import coil.request.videoFrameOption
import com.vortex.player.data.MediaEntry

/**
 * Petición de miniatura para un medio.
 *
 * El detalle que importa: **no se pide el fotograma cero**. Muchísimos vídeos reales
 * empiezan con un fundido desde negro, una cortinilla oscura o un primer fotograma vacío,
 * así que extraer el instante 0 llena la biblioteca de rectángulos negros aunque todo
 * funcione. Tomando un fotograma ya entrado el vídeo se ve de qué va.
 */
@Composable
fun rememberThumbnailRequest(entry: MediaEntry): ImageRequest {
    val context = LocalContext.current
    return remember(entry.uri, entry.durationMs) {
        val offset = thumbnailOffsetMs(entry.durationMs)
        ImageRequest.Builder(context)
            .data(entry.uri)
            .crossfade(true)
            // La clave incluye el instante extraído. Sin esto, cambiar la heurística no
            // sirve de nada: la caché sigue devolviendo el fotograma viejo —negro— hasta
            // que caduque, y quien actualice la app no vería ninguna mejora.
            .memoryCacheKey("${entry.uri}#f$offset")
            .diskCacheKey("${entry.uri}#f$offset")
            .videoFrameMillis(offset)
            // OPTION_CLOSEST y no OPTION_CLOSEST_SYNC: el segundo salta al fotograma
            // clave más cercano, y con intervalos de diez segundos eso puede devolverte
            // el del instante cero, que es justo el negro que se quería evitar. Decodificar
            // hasta el fotograma pedido cuesta un poco más y acierta siempre.
            .videoFrameOption(MediaMetadataRetriever.OPTION_CLOSEST)
            .build()
    }
}

/**
 * Instante del que sacar la miniatura.
 *
 * Es una heurística, no una garantía: no hay forma barata de saber si un fotograma
 * concreto está en negro sin decodificarlo. El 20 % supera holgadamente las cabeceras,
 * los fundidos y las cortinillas de patrocinador que llevan casi todos los vídeos
 * reales, y el suelo de 3 s evita quedarse en el primer fotograma de un clip corto.
 * El techo de un minuto es para que en una película no haya que saltar media hora
 * dentro del fichero sólo para pintar una tarjeta.
 */
internal fun thumbnailOffsetMs(durationMs: Long): Long {
    if (durationMs <= 0) return 3_000
    val target = (durationMs * 0.20).toLong().coerceIn(3_000, 60_000)
    // En clips muy cortos el objetivo puede caer más allá del final.
    return target.coerceAtMost((durationMs * 0.9).toLong())
}
