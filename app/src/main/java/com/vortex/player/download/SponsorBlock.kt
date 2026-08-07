package com.vortex.player.download

/**
 * Qué hacer con los segmentos que la comunidad de SponsorBlock ha marcado en un vídeo.
 *
 * yt-dlp consulta la API de SponsorBlock por su cuenta; aquí sólo se decide la política.
 */
enum class SponsorMode(val label: String, val description: String) {
    OFF(
        "DESACTIVADO",
        "Descarga el vídeo tal cual, con sus patrocinios e intros."
    ),
    MARK(
        "MARCAR",
        "No recorta nada: deja los segmentos como capítulos para poder saltarlos a mano."
    ),
    REMOVE(
        "ELIMINAR",
        "Recorta los segmentos del archivo. El resultado ya no los contiene."
    )
}

/**
 * Categorías de SponsorBlock. El nombre técnico es el que entiende yt-dlp y no debe
 * traducirse; la etiqueta es lo que ve el usuario.
 */
enum class SponsorCategory(val id: String, val label: String, val hint: String) {
    SPONSOR("sponsor", "PATROCINIO", "Publicidad pagada dentro del vídeo"),
    SELFPROMO("selfpromo", "AUTOPROMO", "Su propio merchandising, Patreon o canal"),
    INTERACTION("interaction", "SUSCRÍBETE", "El recordatorio de dar like y suscribirse"),
    INTRO("intro", "INTRO", "Cabecera o pantalla de espera del principio"),
    OUTRO("outro", "OUTRO", "Créditos y tarjetas finales"),
    PREVIEW("preview", "AVANCE", "Resumen de lo que se verá después"),
    FILLER("filler", "RELLENO", "Bromas y digresiones que no aportan al tema"),
    MUSIC_OFFTOPIC("music_offtopic", "NO MUSICAL", "En vídeos musicales, lo que no es la canción");

    companion object {
        /** Lo que casi todo el mundo quiere fuera de un vídeo normal. */
        val DEFAULT_VIDEO = setOf(SPONSOR, SELFPROMO, INTERACTION)

        /**
         * Para audio venido de un vídeo musical, lo que sobra es todo lo que no sea la
         * canción: la charla previa, la dedicatoria del final y los patrocinios.
         */
        val DEFAULT_AUDIO = setOf(SPONSOR, SELFPROMO, INTERACTION, MUSIC_OFFTOPIC, INTRO, OUTRO)

        fun parse(csv: String): Set<SponsorCategory> {
            if (csv.isBlank()) return emptySet()
            val ids = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            return entries.filter { it.id in ids }.toSet()
        }

        fun serialize(categories: Set<SponsorCategory>): String =
            categories.joinToString(",") { it.id }
    }
}

/** La política completa asociada a una descarga. */
data class SponsorSettings(
    val mode: SponsorMode = SponsorMode.OFF,
    val categories: Set<SponsorCategory> = SponsorCategory.DEFAULT_VIDEO
) {
    /** Sin categorías no hay nada que marcar ni que quitar, por mucho modo que se elija. */
    val isActive: Boolean get() = mode != SponsorMode.OFF && categories.isNotEmpty()

    val categoriesCsv: String get() = SponsorCategory.serialize(categories)
}
