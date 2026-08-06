package com.vortex.player.data

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * Borrado de ficheros de la mediateca.
 *
 * Desde Android 11 una app no puede borrar medios que no creó sin que el sistema pregunte
 * al usuario. En vez de pelear con ello, se usa [MediaStore.createDeleteRequest]: sale un
 * diálogo del propio sistema con la lista de ficheros, que es más honesto y además no
 * requiere ningún permiso extra.
 */
object MediaDeleter {

    /**
     * Devuelve el [IntentSender] que la actividad debe lanzar para pedir confirmación,
     * o `null` si el borrado ya se hizo directamente (Android 10 y anteriores).
     */
    fun requestDelete(context: Context, uris: List<Uri>): IntentSender? {
        if (uris.isEmpty()) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
        } else {
            uris.forEach { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            null
        }
    }
}
