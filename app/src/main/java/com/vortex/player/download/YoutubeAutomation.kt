package com.vortex.player.download

import java.io.File

/** Configuración automática para los desafíos modernos de YouTube, sin cuentas ni cookies. */
object YoutubeAutomation {
    const val EJS_COMPONENT = "ejs:github"
    const val PUBLIC_CLIENT_ARGUMENT = "youtube:player_client=android_vr"
    const val RECOVERY_STATUS = "YouTube pidió verificación · probando modo automático…"
    const val RECOVERY_FAILED = "ERROR: YouTube bloqueó también el modo automático sin cuenta"

    fun quickJsExecutable(nativeLibraryDir: String): String? =
        File(nativeLibraryDir, "libqjs.so")
            .takeIf { it.isFile }
            ?.absolutePath

    fun supportsEjs(version: String): Boolean {
        val match = Regex("(\\d{4})\\.(\\d{2})\\.(\\d{2})").find(version) ?: return false
        val (year, month, day) = match.destructured
        return year.toInt() * 10_000 + month.toInt() * 100 + day.toInt() >= 20_251_112
    }

    fun isBotChallenge(message: String): Boolean {
        val value = message.lowercase()
        return "confirm you're not a bot" in value ||
            "confirm you’re not a bot" in value ||
            ("youtube" in value && "use --cookies" in value)
    }
}
