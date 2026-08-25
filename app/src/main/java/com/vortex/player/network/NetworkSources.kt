package com.vortex.player.network

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

enum class NetworkProtocol(
    val label: String,
    val mimeType: String,
    val liveByDefault: Boolean
) {
    HTTP("HTTP", "video/*", false),
    HLS("HLS", "application/x-mpegURL", true),
    RTSP("RTSP", "video/*", true),
    RTMP("RTMP", "video/*", true),
    MMS("MMS", "video/*", true),
    UDP("UDP", "video/*", true),
    TCP("TCP", "video/*", true)
}

enum class NetworkMediaKind(val label: String, val fallbackMimeType: String) {
    VIDEO("VÍDEO", "video/*"),
    AUDIO("AUDIO", "audio/*")
}

data class NetworkSourceDraft(
    val url: String,
    val title: String,
    val protocol: NetworkProtocol,
    val mediaKind: NetworkMediaKind,
    /** Las URLs con secretos se reproducen, pero nunca se escriben en disco. */
    val canPersist: Boolean
)

data class NetworkSource(
    val url: String,
    val title: String,
    val protocol: NetworkProtocol,
    val mediaKind: NetworkMediaKind,
    val favorite: Boolean,
    val lastOpenedAtMs: Long
)

sealed interface NetworkSourceParseResult {
    data class Valid(val draft: NetworkSourceDraft) : NetworkSourceParseResult
    data class Invalid(val message: String) : NetworkSourceParseResult
}

/** Validación y clasificación compartidas por la UI y las pruebas JVM. */
object NetworkSourceParser {
    private val explicitScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val allowedSchemes = setOf("http", "https", "rtsp", "rtmp", "mms", "udp", "tcp")
    private val sensitiveNames = setOf(
        "token", "access_token", "auth", "authorization", "signature", "sig",
        "key", "api_key", "apikey", "password", "passwd"
    )

    fun parse(rawUrl: String, rawTitle: String = ""): NetworkSourceParseResult {
        val typed = rawUrl.trim()
        if (typed.isBlank()) return NetworkSourceParseResult.Invalid("Pega o escribe una URL")

        // Una dirección web pegada sin esquema se vuelve HTTPS. Los protocolos de vídeo
        // especializados sí deben venir explícitos para no adivinar una intención peligrosa.
        val candidate = if (explicitScheme.containsMatchIn(typed)) typed else "https://$typed"
        val uri = runCatching { URI(candidate).normalize() }.getOrNull()
            ?: return NetworkSourceParseResult.Invalid("La URL no tiene un formato válido")
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme !in allowedSchemes) {
            return NetworkSourceParseResult.Invalid("Protocolo no compatible: ${scheme.ifBlank { "—" }}")
        }
        if (uri.rawAuthority.isNullOrBlank()) {
            return NetworkSourceParseResult.Invalid("La URL necesita un servidor o una dirección IP")
        }

        val normalized = uri.toASCIIString()
        val protocol = when {
            scheme == "rtsp" -> NetworkProtocol.RTSP
            scheme == "rtmp" -> NetworkProtocol.RTMP
            scheme == "mms" -> NetworkProtocol.MMS
            scheme == "udp" -> NetworkProtocol.UDP
            scheme == "tcp" -> NetworkProtocol.TCP
            normalized.substringBefore('#').lowercase().contains(".m3u8") -> NetworkProtocol.HLS
            else -> NetworkProtocol.HTTP
        }
        val mediaKind = if (uri.path.orEmpty().substringAfterLast('.').lowercase() in AUDIO_EXTENSIONS) {
            NetworkMediaKind.AUDIO
        } else {
            NetworkMediaKind.VIDEO
        }
        val title = rawTitle.trim().ifBlank { defaultTitle(uri, protocol) }.take(100)
        return NetworkSourceParseResult.Valid(
            NetworkSourceDraft(
                url = normalized,
                title = title,
                protocol = protocol,
                mediaKind = mediaKind,
                canPersist = uri.rawUserInfo == null && !hasSensitiveQuery(uri.rawQuery)
            )
        )
    }

    private fun defaultTitle(uri: URI, protocol: NetworkProtocol): String {
        val host = uri.host ?: uri.rawAuthority?.substringAfter('@')?.substringBefore(':')
        val leaf = uri.path.orEmpty().trimEnd('/').substringAfterLast('/')
            .substringBeforeLast('.', missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
        return listOfNotNull(host, leaf).joinToString(" · ")
            .ifBlank { "Fuente ${protocol.label}" }
    }

    private fun hasSensitiveQuery(query: String?): Boolean = query.orEmpty()
        .split('&')
        .asSequence()
        .map { it.substringBefore('=').lowercase() }
        .any { name ->
            name in sensitiveNames ||
                name.contains("token") ||
                name.contains("signature") ||
                name.startsWith("x-amz-")
        }

    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus")
}

/** Reglas deterministas para favoritos e historial, sin dependencias Android. */
object NetworkSourceLibraryPolicy {
    const val MAX_RECENT = 25
    const val MAX_FAVORITES = 100

    fun recordOpened(
        current: List<NetworkSource>,
        draft: NetworkSourceDraft,
        nowMs: Long
    ): List<NetworkSource> {
        if (!draft.canPersist) return normalized(current)
        val previous = current.firstOrNull { it.url == draft.url }
        val updated = NetworkSource(
            url = draft.url,
            title = draft.title,
            protocol = draft.protocol,
            mediaKind = draft.mediaKind,
            favorite = previous?.favorite == true,
            lastOpenedAtMs = nowMs.coerceAtLeast(0L)
        )
        return normalized(current.filterNot { it.url == draft.url } + updated)
    }

    fun saveFavorite(
        current: List<NetworkSource>,
        draft: NetworkSourceDraft,
        nowMs: Long
    ): List<NetworkSource> {
        if (!draft.canPersist) return normalized(current)
        val previous = current.firstOrNull { it.url == draft.url }
        val updated = NetworkSource(
            url = draft.url,
            title = draft.title,
            protocol = draft.protocol,
            mediaKind = draft.mediaKind,
            favorite = true,
            lastOpenedAtMs = previous?.lastOpenedAtMs ?: nowMs.coerceAtLeast(0L)
        )
        return normalized(current.filterNot { it.url == draft.url } + updated)
    }

    fun toggleFavorite(current: List<NetworkSource>, url: String): List<NetworkSource> =
        normalized(current.map { if (it.url == url) it.copy(favorite = !it.favorite) else it })

    fun remove(current: List<NetworkSource>, url: String): List<NetworkSource> =
        normalized(current.filterNot { it.url == url })

    fun clearRecent(current: List<NetworkSource>): List<NetworkSource> =
        normalized(current.filter { it.favorite })

    fun normalized(entries: List<NetworkSource>): List<NetworkSource> {
        val unique = entries.groupBy { it.url }.values.map { duplicates ->
            duplicates.maxByOrNull { it.lastOpenedAtMs }!!.copy(
                favorite = duplicates.any { it.favorite }
            )
        }
        val favorites = unique.filter { it.favorite }
            .sortedByDescending { it.lastOpenedAtMs }
            .take(MAX_FAVORITES)
        val recent = unique.filterNot { it.favorite }
            .sortedByDescending { it.lastOpenedAtMs }
            .take(MAX_RECENT)
        return favorites + recent
    }
}

object NetworkSourceCodec {
    private const val VERSION = 1

    fun encode(sources: List<NetworkSource>): String = JSONObject().apply {
        put("version", VERSION)
        put("sources", JSONArray().apply {
            NetworkSourceLibraryPolicy.normalized(sources).forEach { source ->
                put(JSONObject().apply {
                    put("url", source.url)
                    put("title", source.title)
                    put("protocol", source.protocol.name)
                    put("mediaKind", source.mediaKind.name)
                    put("favorite", source.favorite)
                    put("lastOpenedAtMs", source.lastOpenedAtMs)
                })
            }
        })
    }.toString()

    fun decode(json: String): List<NetworkSource> = runCatching {
        val root = JSONObject(json)
        if (root.optInt("version", -1) !in 1..VERSION) return emptyList()
        val values = root.optJSONArray("sources") ?: return emptyList()
        buildList {
            for (index in 0 until values.length()) {
                val item = values.optJSONObject(index) ?: continue
                val parsed = NetworkSourceParser.parse(
                    item.optString("url"),
                    item.optString("title")
                ) as? NetworkSourceParseResult.Valid ?: continue
                // Una versión anterior o un archivo manipulado nunca debe hacer que se
                // persistan credenciales accidentalmente.
                if (!parsed.draft.canPersist) continue
                add(
                    NetworkSource(
                        url = parsed.draft.url,
                        title = parsed.draft.title,
                        protocol = item.optString("protocol")
                            .let { runCatching { NetworkProtocol.valueOf(it) }.getOrNull() }
                            ?: parsed.draft.protocol,
                        mediaKind = item.optString("mediaKind")
                            .let { runCatching { NetworkMediaKind.valueOf(it) }.getOrNull() }
                            ?: parsed.draft.mediaKind,
                        favorite = item.optBoolean("favorite", false),
                        lastOpenedAtMs = item.optLong("lastOpenedAtMs", 0L)
                    )
                )
            }
        }.let(NetworkSourceLibraryPolicy::normalized)
    }.getOrDefault(emptyList())
}
