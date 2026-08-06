package com.vortex.player.ui.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vortex.player.update.AppRelease
import com.vortex.player.update.AppUpdate
import com.vortex.player.update.UpdateInstaller
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface UpdateStage {
    data object Idle : UpdateStage
    data object Checking : UpdateStage
    data class Available(val release: AppRelease) : UpdateStage
    data class Downloading(val release: AppRelease, val progress: Float) : UpdateStage
    data class ReadyToInstall(val release: AppRelease, val file: File) : UpdateStage
    data class UpToDate(val version: String) : UpdateStage
    data class Failed(val reason: String) : UpdateStage
}

class UpdateViewModel(app: Application) : AndroidViewModel(app) {

    private val _stage = MutableStateFlow<UpdateStage>(UpdateStage.Idle)
    val stage: StateFlow<UpdateStage> = _stage.asStateFlow()

    /** Se muestra el aviso sólo si el usuario no ha omitido justo esa versión. */
    private val _bannerVisible = MutableStateFlow(false)
    val bannerVisible: StateFlow<Boolean> = _bannerVisible.asStateFlow()

    val currentVersion: String get() = AppUpdate.currentVersion

    init {
        viewModelScope.launch {
            if (AppUpdate.shouldAutoCheck(getApplication())) check(automatic = true)
        }
    }

    fun check(automatic: Boolean = false) {
        viewModelScope.launch {
            if (!automatic) _stage.value = UpdateStage.Checking
            val release = AppUpdate.fetchLatest()
            AppUpdate.markChecked(getApplication())

            if (release == null) {
                // En la comprobación automática no se molesta al usuario si no hay red.
                if (!automatic) _stage.value = UpdateStage.Failed("No se pudo consultar GitHub")
                return@launch
            }

            if (!AppUpdate.isNewer(release.versionName, AppUpdate.currentVersion)) {
                if (!automatic) _stage.value = UpdateStage.UpToDate(AppUpdate.currentVersion)
                return@launch
            }

            if (release.assetForThisDevice() == null) {
                if (!automatic) {
                    _stage.value = UpdateStage.Failed(
                        "La versión ${release.versionName} no trae un APK para esta arquitectura"
                    )
                }
                return@launch
            }

            _stage.value = UpdateStage.Available(release)
            val skipped = AppUpdate.skippedVersion(getApplication())
            _bannerVisible.value = !automatic || skipped != release.versionName
        }
    }

    fun download() {
        val release = (_stage.value as? UpdateStage.Available)?.release ?: return
        val asset = release.assetForThisDevice() ?: return
        viewModelScope.launch {
            _stage.value = UpdateStage.Downloading(release, 0f)
            val file = UpdateInstaller.download(getApplication(), asset) { progress ->
                _stage.value = UpdateStage.Downloading(release, progress)
            }
            _stage.value = if (file == null) {
                UpdateStage.Failed("La descarga falló")
            } else {
                UpdateStage.ReadyToInstall(release, file)
            }
        }
    }

    fun install(): Boolean {
        val ready = _stage.value as? UpdateStage.ReadyToInstall ?: return false
        if (!UpdateInstaller.canInstall(getApplication())) return false
        UpdateInstaller.install(getApplication(), ready.file)
        return true
    }

    fun skipThisVersion() {
        val release = currentRelease() ?: return
        viewModelScope.launch {
            AppUpdate.skipVersion(getApplication(), release.versionName)
            _bannerVisible.value = false
            _stage.value = UpdateStage.Idle
        }
    }

    fun dismissBanner() { _bannerVisible.value = false }

    fun dismissDialog() {
        if (_stage.value is UpdateStage.Downloading) return
        _stage.value = UpdateStage.Idle
    }

    fun showDialogForAvailable() {
        currentRelease()?.let { _stage.value = UpdateStage.Available(it) }
    }

    private fun currentRelease(): AppRelease? = when (val s = _stage.value) {
        is UpdateStage.Available -> s.release
        is UpdateStage.Downloading -> s.release
        is UpdateStage.ReadyToInstall -> s.release
        else -> null
    }
}
