package com.vortex.player.ui.common

import java.util.Locale

/** "1:04:07" para lo largo, "4:07" para lo corto. Nunca "01:04:07": ocupa y no aporta. */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/** Tiempo restante en la forma compacta que se usa en el HUD: "-12:30". */
fun formatRemaining(positionMs: Long, durationMs: Long): String =
    if (durationMs <= 0) "" else "-" + formatDuration((durationMs - positionMs).coerceAtLeast(0))

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit >= 2) {
        String.format(Locale.US, "%.1f %s", value, units[unit])
    } else {
        String.format(Locale.US, "%.0f %s", value, units[unit])
    }
}
