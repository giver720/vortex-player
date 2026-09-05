package com.vortex.player.spotify

import org.json.JSONObject

internal object SpotifyPageCursor {
    fun nextOffset(page: JSONObject, requestedOffset: Int): Int? {
        val count = page.getJSONArray("items").length()
        val total = page.getInt("total")
        check(total >= 0 && page.optInt("offset", requestedOffset) == requestedOffset) {
            "Spotify devolvió una página fuera de orden"
        }
        val next = requestedOffset.toLong() + count
        val hasMore = !page.isNull("next") && page.optString("next").isNotBlank()
        if (!hasMore) {
            check(next >= total) { "Spotify devolvió una lista incompleta; se conserva la copia anterior" }
            return null
        }
        check(count > 0 && next <= Int.MAX_VALUE && next < total) {
            "Spotify devolvió una página que no avanza"
        }
        return next.toInt()
    }
}
