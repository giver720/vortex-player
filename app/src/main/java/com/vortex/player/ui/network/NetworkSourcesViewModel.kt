package com.vortex.player.ui.network

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vortex.player.data.MediaEntry
import com.vortex.player.network.NetworkMediaKind
import com.vortex.player.network.NetworkSource
import com.vortex.player.network.NetworkSourceDraft
import com.vortex.player.network.NetworkSourceParseResult
import com.vortex.player.network.NetworkSourceParser
import com.vortex.player.network.NetworkSourceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NetworkConnectionUi(
    val connected: Boolean = false,
    val transport: String = "SIN CONEXIÓN",
    val metered: Boolean = false
)

class NetworkSourcesViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = NetworkSourceRepository.get(app)
    private val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val sources: StateFlow<List<NetworkSource>> = repository.sources

    private val _url = MutableStateFlow("")
    val url: StateFlow<String> = _url.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _analysis = MutableStateFlow<NetworkSourceParseResult?>(null)
    val analysis: StateFlow<NetworkSourceParseResult?> = _analysis.asStateFlow()
    private var mediaKindOverride: NetworkMediaKind? = null

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _connection = MutableStateFlow(readConnection())
    val connection: StateFlow<NetworkConnectionUi> = _connection.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshConnection()
        override fun onLost(network: Network) = refreshConnection()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            refreshConnection()
        }
    }

    init {
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
    }

    fun setUrl(value: String) {
        _url.value = value
        mediaKindOverride = null
        analyze(showBlankError = false)
    }

    fun setTitle(value: String) {
        _title.value = value.take(100)
        analyze(showBlankError = false)
    }

    fun clearMessage() {
        _message.value = null
    }

    fun setMediaKind(kind: NetworkMediaKind) {
        mediaKindOverride = kind
        analyze(showBlankError = false)
    }

    fun playInput(): MediaEntry? {
        refreshConnection()
        val draft = validDraft(showError = true) ?: return null
        rememberOpened(draft)
        return draft.toMediaEntry()
    }

    fun play(source: NetworkSource): MediaEntry? {
        refreshConnection()
        val parsed = NetworkSourceParser.parse(source.url, source.title)
            as? NetworkSourceParseResult.Valid ?: run {
                _message.value = "La fuente guardada ya no es válida"
                return null
            }
        val draft = parsed.draft.copy(mediaKind = source.mediaKind)
        rememberOpened(draft)
        return draft.toMediaEntry()
    }

    fun saveInputAsFavorite() {
        val draft = validDraft(showError = true) ?: return
        if (!draft.canPersist) {
            _message.value = "Enlace privado: puede reproducirse, pero no se guardará en el teléfono"
            return
        }
        viewModelScope.launch {
            repository.saveFavorite(draft)
            _message.value = "Fuente añadida a favoritos"
        }
    }

    fun toggleFavorite(source: NetworkSource) {
        viewModelScope.launch { repository.toggleFavorite(source.url) }
    }

    fun remove(source: NetworkSource) {
        viewModelScope.launch {
            repository.remove(source.url)
            _message.value = "Fuente eliminada"
        }
    }

    fun clearRecent() {
        viewModelScope.launch {
            repository.clearRecent()
            _message.value = "Historial de red limpiado"
        }
    }

    private fun rememberOpened(draft: NetworkSourceDraft) {
        if (!draft.canPersist) {
            _message.value = "Enlace privado: reproducción temporal, no se guardará en el historial"
            return
        }
        viewModelScope.launch { repository.recordOpened(draft) }
    }

    private fun analyze(showBlankError: Boolean) {
        val parsed = if (_url.value.isBlank() && !showBlankError) {
            null
        } else {
            NetworkSourceParser.parse(_url.value, _title.value)
        }
        _analysis.value = if (parsed is NetworkSourceParseResult.Valid && mediaKindOverride != null) {
            parsed.copy(draft = parsed.draft.copy(mediaKind = requireNotNull(mediaKindOverride)))
        } else {
            parsed
        }
    }

    private fun validDraft(showError: Boolean): NetworkSourceDraft? {
        analyze(showBlankError = showError)
        return when (val result = _analysis.value) {
            is NetworkSourceParseResult.Valid -> result.draft
            is NetworkSourceParseResult.Invalid -> {
                if (showError) _message.value = result.message
                null
            }
            null -> null
        }
    }

    private fun refreshConnection() {
        _connection.value = readConnection()
    }

    private fun readConnection(): NetworkConnectionUi {
        val active = connectivity.activeNetwork ?: return NetworkConnectionUi()
        val capabilities = connectivity.getNetworkCapabilities(active) ?: return NetworkConnectionUi()
        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WI-FI"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "DATOS MÓVILES"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "RED"
        }
        return NetworkConnectionUi(
            connected = true,
            transport = if (hasInternet) transport else "$transport · RED LOCAL",
            metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        )
    }

    private fun NetworkSourceDraft.toMediaEntry(): MediaEntry {
        val now = System.currentTimeMillis() / 1000
        return MediaEntry(
            id = url.hashCode().toLong(),
            uri = Uri.parse(url),
            title = title,
            displayName = title,
            durationMs = 0L,
            sizeBytes = 0L,
            mimeType = if (protocol.mimeType == "video/*") {
                mediaKind.fallbackMimeType
            } else {
                protocol.mimeType
            },
            width = 0,
            height = 0,
            folderPath = "",
            folderName = "Red · ${protocol.label}",
            dateAddedSec = now,
            isVideo = mediaKind == NetworkMediaKind.VIDEO,
            persistable = canPersist
        )
    }

    override fun onCleared() {
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        super.onCleared()
    }
}
