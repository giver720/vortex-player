package com.vortex.player.download

/**
 * Lee el avance dentro de una lista a partir de la salida de yt-dlp.
 *
 * Una lista entera se descarga en un único proceso de yt-dlp, así que en la cola es un
 * solo trabajo por mucho que contenga cuarenta vídeos. Sin esto, lo único que se veía era
 * el porcentaje del fichero en curso, que vuelve a cero en cada pista: parecía que la
 * descarga se reiniciaba sola y no había forma de saber por dónde iba.
 *
 * Se saca de la salida en vez de consultarlo aparte a propósito: yt-dlp ya anuncia la
 * posición y el total, de modo que enterarse no cuesta ni una llamada de red ni un
 * segundo de espera añadido antes de empezar a bajar.
 */
object PlaylistProgress {

    /**
     * Secuencias de color de yt-dlp. Se quitan antes de leer nada porque se cuelan entre
     * las cifras ("item ESC[36m7ESC[0m of 24") y partirían los números por la mitad.
     */
    private val ANSI = Regex("\\x1B\\[[;\\d]*m")

    /** "Downloading item 3 of 24". Las versiones antiguas dicen "video" en vez de "item". */
    private val POSITION = Regex("Downloading (?:item|video) (\\d+) of (\\d+)")

    private val DESTINATION = Regex("Destination:\\s*(.+)$")
    private val MERGING = Regex("Merging formats into \"?(.+?)\"?$")

    /** Sufijo del formato concreto (`.f313`) que yt-dlp añade a cada pista suelta. */
    private val FORMAT_SUFFIX = Regex("\\.f\\d+$")

    /** Numeración que antepone la plantilla de lista; en pantalla ya se numera aparte. */
    private val INDEX_PREFIX = Regex("^\\d{3} - ")

    /** Posición y total del elemento que empieza, o `null` si la línea no lo dice. */
    fun position(line: String): Pair<Int, Int>? {
        val match = POSITION.find(ANSI.replace(line, "")) ?: return null
        val index = match.groupValues[1].toIntOrNull() ?: return null
        val total = match.groupValues[2].toIntOrNull() ?: return null
        return if (index > 0 && total > 0) index to total else null
    }

    /**
     * Avance de la lista entera, de 0 a 1. Es lo que hay que enseñar cuando hay lista: el
     * porcentaje del fichero en curso vuelve a cero en cada pista y, a solas, da la
     * impresión de que la descarga se reinicia sin parar.
     *
     * Sin lista devuelve el progreso del fichero tal cual, así que sirve para los dos casos.
     */
    fun overall(index: Int, count: Int, fileProgress: Float): Float =
        if (count > 1 && index > 0) {
            ((index - 1 + fileProgress.coerceIn(0f, 1f)) / count).coerceIn(0f, 1f)
        } else {
            fileProgress.coerceIn(0f, 1f)
        }

    /**
     * Nombre del elemento en curso, deducido del fichero que yt-dlp está escribiendo.
     *
     * Se mira también la línea del mezclador porque en un vídeo con pistas separadas hay
     * un "Destination" por pista, y el nombre bueno —sin el sufijo del formato— es el que
     * aparece al unirlas.
     */
    fun itemName(line: String): String? {
        val clean = ANSI.replace(line, "").trim()
        val match = MERGING.find(clean) ?: DESTINATION.find(clean) ?: return null
        val name = match.groupValues[1].trim().trimEnd('"')
            .substringAfterLast('/')
            .substringBeforeLast('.')
        return FORMAT_SUFFIX.replace(name, "")
            .let { INDEX_PREFIX.replace(it, "") }
            .trim()
            .takeIf { it.isNotBlank() }
    }
}
