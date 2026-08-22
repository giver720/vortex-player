package com.vortex.player.spotify

import org.json.JSONArray
import org.json.JSONObject

/** Encuentra el objeto principal aunque Spotify lo mueva durante un despliegue A/B. */
internal object SpotifyEntityParser {

    private const val MAX_JSON_NODES = 2_500
    private const val MAX_JSON_DEPTH = 14
    private const val MAX_SERIALIZED_JSON_CHARS = 1_000_000

    /**
     * Se prueban primero las rutas configuradas. Algunas respuestas móviles envían la
     * misma entidad dentro de un estado hidratado distinto; el respaldo la reconoce por
     * forma e identificador sin depender de un nombre de contenedor concreto.
     */
    fun entityOf(
        root: JSONObject,
        paths: List<String>,
        kind: SpotifyKind,
        expectedId: String
    ): JSONObject? = paths.firstNotNullOfOrNull { path ->
        objectAt(root, path)?.takeIf { it.looksLikeEntity(kind, expectedId) }
    }
        ?: findEntity(root, kind, expectedId)

    private fun objectAt(root: JSONObject, path: String): JSONObject? {
        var current: Any = root
        for (segment in path.split('/').drop(1)) {
            current = when (current) {
                is JSONObject -> current.opt(segment)
                is String -> current.toJsonObject()?.opt(segment)
                else -> null
            } ?: return null
        }
        return when (current) {
            is JSONObject -> current
            is String -> current.toJsonObject()
            else -> null
        }
    }

    private data class JsonNode(val value: Any, val depth: Int)

    private fun findEntity(
        root: JSONObject,
        kind: SpotifyKind,
        expectedId: String
    ): JSONObject? {
        val pending = ArrayDeque<JsonNode>().apply { add(JsonNode(root, 0)) }
        var visited = 0
        while (pending.isNotEmpty() && visited++ < MAX_JSON_NODES) {
            val (value, depth) = pending.removeFirst()
            if (depth > MAX_JSON_DEPTH) continue
            when (value) {
                is JSONObject -> {
                    if (value.looksLikeEntity(kind, expectedId)) return value
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        value.opt(keys.next())?.let { child ->
                            if (child is JSONObject || child is JSONArray || child is String) {
                                pending.add(JsonNode(child, depth + 1))
                            }
                        }
                    }
                }
                is JSONArray -> for (index in 0 until value.length()) {
                    value.opt(index)?.let { child ->
                        if (child is JSONObject || child is JSONArray || child is String) {
                            pending.add(JsonNode(child, depth + 1))
                        }
                    }
                }
                is String -> value.toJsonObject()?.let {
                    pending.add(JsonNode(it, depth + 1))
                }
            }
        }
        return null
    }

    private fun JSONObject.looksLikeEntity(kind: SpotifyKind, expectedId: String): Boolean {
        val sameId = optString("id") == expectedId ||
            optString("uri").substringAfterLast(':') == expectedId
        return when (kind) {
            SpotifyKind.TRACK -> sameId &&
                optString("title").ifBlank { optString("name") }.isNotBlank()
            SpotifyKind.ALBUM,
            SpotifyKind.PLAYLIST -> {
                val tracks = optJSONArray("trackList")
                tracks != null && optString("name").ifBlank { optString("title") }.isNotBlank() &&
                    (sameId || tracks.length() > 0)
            }
        }
    }

    private fun String.toJsonObject(): JSONObject? {
        val trimmed = trim()
        if (!trimmed.startsWith('{') || trimmed.length > MAX_SERIALIZED_JSON_CHARS) return null
        return runCatching { JSONObject(trimmed) }.getOrNull()
    }
}
