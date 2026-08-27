package com.vortex.player.ui.downloads

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vortex.player.download.YoutubeAuthStore
import com.vortex.player.ui.theme.VortexPalette
import com.vortex.player.ui.theme.VortexTheme

class YoutubeLoginActivity : ComponentActivity() {
    private var status by mutableStateOf("INICIA SESIÓN EN YOUTUBE")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VortexTheme {
                Column(Modifier.fillMaxSize().background(VortexPalette.Graphite)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("CUENTA DE YOUTUBE", color = VortexPalette.TextHigh)
                        Text(
                            status,
                            color = if (YoutubeAuthStore.hasSession(this@YoutubeLoginActivity)) {
                                VortexPalette.Neon
                            } else {
                                VortexPalette.TextLow
                            },
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Text(
                            "La contraseña se introduce únicamente en la página de Google. " +
                                "Vortex guarda la sesión dentro del almacenamiento privado de Android.",
                            color = VortexPalette.TextLow,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    AndroidView(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        factory = { context ->
                            WebView(context).apply webView@ {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                CookieManager.getInstance().apply {
                                    setAcceptCookie(true)
                                    setAcceptThirdPartyCookies(this@webView, true)
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: WebResourceRequest
                                    ): Boolean = request.url.scheme != "https"

                                    override fun onPageFinished(view: WebView, url: String) {
                                        if (YoutubeAuthStore.capture(this@YoutubeLoginActivity)) {
                                            status = "SESIÓN DETECTADA · YA PUEDES GUARDAR"
                                        }
                                    }
                                }
                                loadUrl(LOGIN_URL)
                            }
                        },
                        onRelease = { webView ->
                            webView.stopLoading()
                            webView.destroy()
                        }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                if (YoutubeAuthStore.capture(this@YoutubeLoginActivity)) {
                                    setResult(RESULT_OK)
                                    finish()
                                } else {
                                    Toast.makeText(
                                        this@YoutubeLoginActivity,
                                        "Aún no se detectó una sesión de YouTube",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = VortexPalette.Neon),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("GUARDAR SESIÓN", color = VortexPalette.Graphite)
                        }
                        Button(
                            onClick = { finish() },
                            colors = ButtonDefaults.buttonColors(containerColor = VortexPalette.GraphiteRaised)
                        ) {
                            Text("CANCELAR", color = VortexPalette.TextMid)
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val LOGIN_URL =
            "https://accounts.google.com/ServiceLogin?service=youtube&continue=" +
                "https%3A%2F%2Fwww.youtube.com%2F"
    }
}
