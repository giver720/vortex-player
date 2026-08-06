package com.vortex.player

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.vortex.player.popup.PopupService
import com.vortex.player.ui.downloads.DownloadsScreen
import com.vortex.player.ui.downloads.DownloadsViewModel
import com.vortex.player.ui.library.LibraryScreen
import com.vortex.player.ui.library.LibraryViewModel
import com.vortex.player.ui.player.PlayerActivity
import com.vortex.player.ui.theme.VortexTheme

private enum class Screen { LIBRARY, DOWNLOADS }

class MainActivity : ComponentActivity() {

    private val libraryViewModel: LibraryViewModel by viewModels()
    private val downloadsViewModel: DownloadsViewModel by viewModels()

    /**
     * El permiso de superposición no se concede desde un diálogo normal: hay que mandar
     * al usuario a Ajustes. Al volver, si lo dio, abrimos el popup que había pedido.
     */
    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (PopupService.canDrawOverlays(this)) PopupService.show(this)
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* La reproducción funciona igual sin notificación; no bloqueamos por esto. */ }

    /** Selector de carpeta del sistema para el destino de las descargas. */
    private val folderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(downloadsViewModel::setDestination) }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        setContent {
            VortexTheme {
                var screen by remember { mutableStateOf(Screen.LIBRARY) }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (screen) {
                        Screen.LIBRARY -> LibraryScreen(
                            viewModel = libraryViewModel,
                            onOpenPlayer = {
                                startActivity(Intent(this, PlayerActivity::class.java))
                            },
                            onRequestPopup = ::requestPopup,
                            onOpenDownloads = { screen = Screen.DOWNLOADS }
                        )

                        Screen.DOWNLOADS -> {
                            BackHandler { screen = Screen.LIBRARY }
                            DownloadsScreen(
                                viewModel = downloadsViewModel,
                                onBack = { screen = Screen.LIBRARY },
                                onPickFolder = { folderLauncher.launch(null) }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun requestPopup() {
        if (PopupService.canDrawOverlays(this)) {
            PopupService.show(this)
            // Salir al escritorio es parte del gesto: el popup existe para seguir
            // viendo mientras usas otra app.
            moveTaskToBack(true)
        } else {
            overlayLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
