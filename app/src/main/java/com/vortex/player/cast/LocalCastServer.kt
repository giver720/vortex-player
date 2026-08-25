package com.vortex.player.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import com.vortex.player.data.MediaEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Puente efímero para que el receptor Cast pueda leer un `content://` del teléfono.
 * Sólo expone los medios de la cola Cast actual, cada uno bajo un token aleatorio, y entiende
 * GET/HEAD/OPTIONS + Range.
 */
object LocalCastServer {
    private data class SharedMedia(
        val uri: Uri,
        val mimeType: String,
        val length: Long,
        val route: String
    )

    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private var acceptJob: Job? = null
    @Volatile private var shared: Map<String, SharedMedia> = emptyMap()

    @Synchronized
    fun start(context: Context, entry: MediaEntry, contentType: String): Result<String> =
        startQueue(context, listOf(entry to contentType)).map { endpoints ->
            endpoints.getValue(entry.uri.toString())
        }

    /** Abre un único servidor para toda la cola; permite que el receptor precargue la siguiente. */
    @Synchronized
    fun startQueue(
        context: Context,
        entries: List<Pair<MediaEntry, String>>
    ): Result<Map<String, String>> = runCatching {
        stopInternal()
        require(entries.isNotEmpty()) { "La cola local está vacía" }
        val app = context.applicationContext
        val address = findLanIpv4(app)
            ?: error("No se encontró una dirección IPv4 accesible desde el televisor")
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(0))
        }
        val mediaByRoute = linkedMapOf<String, SharedMedia>()
        val endpointByUri = linkedMapOf<String, String>()
        entries.distinctBy { it.first.uri.toString() }.forEach { (entry, contentType) ->
            val length = resolveLength(app, entry)
            require(length > 0L) {
                "No se pudo conocer el tamaño de ${entry.title.ifBlank { entry.displayName }}"
            }
            val token = UUID.randomUUID().toString().replace("-", "")
            val safeName = URLEncoder.encode(
                entry.displayName.ifBlank { entry.title.ifBlank { "media" } },
                StandardCharsets.UTF_8.name()
            ).replace("+", "%20")
            val route = "/media/$token/$safeName"
            mediaByRoute[route] = SharedMedia(
                uri = entry.uri,
                mimeType = contentType,
                length = length,
                route = route
            )
            endpointByUri[entry.uri.toString()] =
                "http://${address.hostAddress}:${socket.localPort}$route"
        }
        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serverSocket = socket
        scope = serverScope
        shared = mediaByRoute
        acceptJob = serverScope.launch {
            while (isActive) {
                val client = try {
                    socket.accept()
                } catch (_: SocketException) {
                    break
                }
                launch { serve(app, client, mediaByRoute) }
            }
        }
        endpointByUri
    }

    @Synchronized
    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        runCatching { serverSocket?.close() }
        acceptJob?.cancel()
        scope?.cancel()
        serverSocket = null
        acceptJob = null
        scope = null
        shared = emptyMap()
    }

    private fun serve(context: Context, socket: Socket, mediaByRoute: Map<String, SharedMedia>) {
        socket.use { client ->
            client.soTimeout = 15_000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            val requestLine = readHttpLine(input) ?: return
            val request = requestLine.split(' ')
            if (request.size < 2) return writeError(output, 400, "Bad Request", 0L)
            val method = request[0].uppercase()
            val route = request[1].substringBefore('?')
            val headers = linkedMapOf<String, String>()
            var headerCount = 0
            while (headerCount++ < MAX_HEADER_LINES) {
                val line = readHttpLine(input) ?: break
                if (line.isBlank()) break
                val name = line.substringBefore(':', "").trim().lowercase()
                if (name.isNotBlank()) headers[name] = line.substringAfter(':', "").trim()
            }

            val media = mediaByRoute[route]
                ?: return writeError(output, 404, "Not Found", 0L)
            if (method == "OPTIONS") {
                writeHeaders(output, 204, "No Content", media.mimeType, 0L, null, media.length)
                output.flush()
                return
            }
            if (method != "GET" && method != "HEAD") {
                return writeError(output, 405, "Method Not Allowed", media.length)
            }

            val rangeResult = HttpRangePolicy.parse(headers["range"], media.length)
            if (rangeResult == HttpRangeResult.Invalid) {
                return writeError(output, 416, "Range Not Satisfiable", media.length)
            }
            val range = when (rangeResult) {
                is HttpRangeResult.Partial -> rangeResult.range
                HttpRangeResult.Full -> HttpByteRange(0L, media.length - 1L)
                HttpRangeResult.Invalid -> return
            }
            val partial = rangeResult is HttpRangeResult.Partial
            writeHeaders(
                output = output,
                status = if (partial) 206 else 200,
                reason = if (partial) "Partial Content" else "OK",
                mimeType = media.mimeType,
                contentLength = range.length,
                range = range.takeIf { partial },
                totalLength = media.length
            )
            output.flush()
            if (method == "HEAD") return

            openMedia(context, media.uri).use { opened ->
                opened.seek(range.start)
                copyExactly(opened.input, output, range.length)
                output.flush()
            }
        }
    }

    private class OpenedMedia(
        val input: FileInputStream,
        private val startOffset: Long,
        private val owner: AutoCloseable?
    ) : AutoCloseable {
        fun seek(position: Long) {
            input.channel.position(startOffset + position)
        }

        override fun close() {
            runCatching { input.close() }
            runCatching { owner?.close() }
        }
    }

    private fun openMedia(context: Context, uri: Uri): OpenedMedia {
        if (uri.scheme == "file") {
            return OpenedMedia(FileInputStream(requireNotNull(uri.path)), 0L, null)
        }
        val asset = context.contentResolver.openAssetFileDescriptor(uri, "r")
            ?: error("Android no permitió abrir el medio")
        return OpenedMedia(FileInputStream(asset.fileDescriptor), asset.startOffset, asset)
    }

    private fun resolveLength(context: Context, entry: MediaEntry): Long {
        // MediaStore ya entregó este dato durante el escaneo. Usarlo evita abrir cientos
        // de descriptores en el hilo principal al enviar una playlist local grande.
        if (entry.sizeBytes > 0L) return entry.sizeBytes
        if (entry.uri.scheme == "file") {
            return entry.uri.path?.let(::File)?.length()?.takeIf { it > 0L }
                ?: 0L
        }
        val descriptorLength = runCatching {
            context.contentResolver.openAssetFileDescriptor(entry.uri, "r")?.use { asset ->
                asset.length.takeIf { it > 0L }
                    ?: asset.parcelFileDescriptor.statSize.takeIf { it > 0L }
            }
        }.getOrNull()
        return descriptorLength ?: 0L
    }

    private fun findLanIpv4(context: Context): Inet4Address? {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = connectivity.activeNetwork
        val activeCapabilities = active?.let(connectivity::getNetworkCapabilities)
        if (active != null && (
                activeCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                    activeCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
                )
        ) {
            connectivity.getLinkProperties(active)?.linkAddresses
                ?.map { it.address }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.let { return it }
        }
        // Una VPN suele convertirse en la red activa. En ese caso elegimos directamente
        // wlan/eth para no anunciar al receptor una dirección del túnel que no puede alcanzar.
        return NetworkInterface.getNetworkInterfaces()?.toList()
            ?.sortedByDescending { networkInterface ->
                when {
                    networkInterface.name.startsWith("wlan", ignoreCase = true) -> 3
                    networkInterface.name.startsWith("eth", ignoreCase = true) -> 2
                    networkInterface.name.startsWith("tun", ignoreCase = true) -> 0
                    else -> 1
                }
            }
            ?.asSequence()
            ?.filter { networkInterface ->
                runCatching { networkInterface.isUp && !networkInterface.isLoopback }.getOrDefault(false)
            }
            ?.flatMap { it.inetAddresses.toList().asSequence() }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
    }

    private fun writeHeaders(
        output: BufferedOutputStream,
        status: Int,
        reason: String,
        mimeType: String,
        contentLength: Long,
        range: HttpByteRange?,
        totalLength: Long
    ) {
        val value = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $mimeType\r\n")
            append("Content-Length: $contentLength\r\n")
            append("Accept-Ranges: bytes\r\n")
            range?.let { append("Content-Range: bytes ${it.start}-${it.endInclusive}/$totalLength\r\n") }
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Range, Content-Type\r\n")
            append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(value.toByteArray(StandardCharsets.ISO_8859_1))
    }

    private fun writeError(output: BufferedOutputStream, code: Int, reason: String, total: Long) {
        val body = "$code $reason".toByteArray(StandardCharsets.UTF_8)
        val value = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            if (code == 416) append("Content-Range: bytes */$total\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(value.toByteArray(StandardCharsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    private fun copyExactly(input: InputStream, output: BufferedOutputStream, requested: Long) {
        var remaining = requested
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun readHttpLine(input: InputStream): String? {
        val bytes = ArrayList<Byte>(128)
        while (bytes.size < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().toString(StandardCharsets.ISO_8859_1)
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes += value.toByte()
        }
        return bytes.toByteArray().toString(StandardCharsets.ISO_8859_1)
    }

    private const val MAX_HEADER_LINES = 64
    private const val MAX_HEADER_BYTES = 8 * 1024
}
