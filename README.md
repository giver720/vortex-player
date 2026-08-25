# Vórtex

Reproductor de medios para Android con motor VLC, ventana flotante y descargas
integradas. Pensado para plantarle cara a VLC y MX Player sin heredar sus interfaces.

<p align="center">
  <img src="docs/captura-biblioteca.png" width="30%" alt="Biblioteca" />
  <img src="docs/captura-reproductor.png" width="30%" alt="Reproductor" />
  <img src="docs/captura-descargas.png" width="30%" alt="Descargas" />
</p>

## Qué hace distinto

**VLC en todo momento.** libVLC es el único motor que abre, decodifica y reproduce audio
y vídeo. El contenedor `SimpleBasePlayer` mantiene la integración con `MediaSession`, por
lo que notificación, pantalla de bloqueo, mandos Bluetooth, ventana flotante y controles
de Android continúan funcionando sin introducir un segundo motor multimedia.

**Audio Pro dentro de VLC.** Ecualizador de diez bandas, presets, graves, claridad y volumen
extra ya no dependen de una sesión de efectos Android. Los modos Seguro, Potente, Noche y Voz
configuran toda la cadena con un toque; Tono original/Procesado permite comparar A/B la curva y
el boost sin perder los ajustes. El boost usa primero el volumen software nativo de VLC hasta 200 % y, a partir de ahí,
su preamplificador. Un diagnóstico de ganancia avisa del riesgo teórico de clipping, y ReplayGain
normaliza automáticamente las pistas que incluyen esas etiquetas. Los perfiles por altavoz,
cable y Bluetooth se aplican directamente al motor.

**Un MP4 suena como un MP3.** El modo solo-audio apaga la decodificación de vídeo sin
tocar el audio ni la posición: VLC desactiva la pista con `setVideoTrackEnabled(false)`.
Se puede activar desde la biblioteca, desde el reproductor **y desde la propia ventana
flotante**, sin cortar el sonido.

**Ventana flotante de verdad.** No es el PiP del sistema (que también está): es una
ventana propia por encima de cualquier app, que se arrastra con un dedo, se redimensiona
con dos y lleva dentro el interruptor de solo-audio.

**Descargas con yt-dlp.** YouTube, Vimeo, Twitch, TikTok, Twitter y todo lo que soporte
yt-dlp. Busca por texto o pega un enlace, previsualiza listas con miniaturas y elige qué
elementos bajar. Vídeo hasta 4K o extracción de audio a MP3/M4A/OPUS/FLAC/WAV. Las listas
de reproducción **crean automáticamente su propia carpeta** con las pistas numeradas; cada
elemento entra como trabajo independiente, así que un fallo no obliga a repetir la lista.
En MP4 se prioriza AVC/H.264 para evitar decodificadores Android que anuncian AV1/VP9 pero
entregan cuadros verdes o negros; MKV y WebM conservan la ruta de máxima calidad moderna.
El destino lo elige el usuario con el selector de carpetas del sistema. La cola puede
ejecutar entre **1 y 10 descargas simultáneas**, elegidas con un deslizador o tocando el
número; cada trabajo activo conserva progreso y cancelación propios. El motor inteligente
puede reducir ese límite según batería, memoria y temperatura, aplicar cupos distintos a
YouTube y otras fuentes, esperar Wi-Fi/cargador/horario y limitar el ancho de banda. Los
fallos temporales se reintentan con espera progresiva conservando el archivo `.part`, y los
trabajos pendientes se pueden mover al inicio o al final de la cola.

YouTube usa automáticamente el QuickJS incluido en el APK y los componentes EJS de
`yt-dlp`. Si aparece la comprobación anti-bot, Vórtex prueba una ruta pública alternativa
sin pedir cuentas ni importar cookies. Los fallos de formato, JavaScript, FFmpeg y
postprocesado permanecen visibles en la cola en vez de convertirse en un error genérico.

**Enlaces de Spotify.** Pega una canción, un álbum o una lista y Vórtex lee **sólo los
metadatos** del catálogo —título, artista, duración y portada—, busca cada tema en
YouTube filtrando por duración y etiqueta el resultado con esos datos. El audio de
Spotify va cifrado y no se toca: es el mismo enfoque de spotDL. Cada canción entra en la
cola por separado, así que una lista de ochenta temas no se pierde porque falle uno. El
motor de catálogo se actualiza por separado mediante un manifiesto declarativo validado:
puede adaptarse a cambios de estructura de Spotify sin descargar ni ejecutar código.
Además, el lector reconoce estados hidratados alternativos y entidades serializadas para
resistir despliegues A/B que muevan los datos sin previo aviso.
Los enlaces abreviados de `open.spotify.com/s/` se mantienen dentro del flujo de Spotify:
nunca se entregan al descargador como si fueran audio ni provocan reintentos DRM.

**Tu Spotify (beta).** La conexión oficial usa OAuth 2.0 con PKCE: Vórtex nunca incluye
ni solicita un `client secret`. El Hub sincroniza las playlists autorizadas de la cuenta,
mantiene una caché local para poder consultarlas sin conexión y busca coincidencias con
la música que ya existe en el teléfono. Sólo reproduce esos archivos locales o abre la
canción en Spotify; la API oficial no forma parte del motor de descargas. Al desconectar
la cuenta se eliminan sus datos de la caché.

**Aspecto al estilo VLC.** Ajustar, llenar, estirar y relaciones forzadas (16:9, 4:3,
18:9, 21:9, 1:1) para cuando un fichero trae mal los metadatos, más zoom por pellizco
encima de cualquier preset.

**Motor de reproducción inteligente.** Vórtex ajusta el búfer según sea un archivo local,
HTTP, HLS o RTSP, comienza con decodificación hardware y cambia automáticamente a software
si el decodificador del dispositivo falla o la reproducción deja de avanzar. El panel del
reproductor muestra fuente, HW/SW, códec, resolución, FPS, bitrate, cuadros perdidos y las
recuperaciones realizadas.

**Centro de fuentes de red.** Desde la biblioteca se pueden abrir y guardar transmisiones
HTTP(S), HLS/M3U8, RTSP, RTMP, MMS, UDP y TCP. La pantalla detecta el protocolo y si parece
audio o vídeo, permite corregir el tipo manualmente, separa favoritos de recientes y evita
duplicados. Cámaras y servidores de la red local funcionan aunque esa Wi-Fi no tenga salida
a Internet. Las URLs con usuario, contraseña, token o firma se reproducen sólo en memoria:
no entran al historial, a favoritos ni a la sesión persistente.

**Enviar a la TV con Google Cast.** El botón Cast del reproductor descubre Chromecast,
Google TV y receptores compatibles. HTTP(S) y HLS se entregan directamente; para un archivo
local Vórtex levanta un puente HTTP temporal en la red local, protegido por un token aleatorio,
con CORS y rangos para poder avanzar. VLC sólo se pausa cuando el receptor confirma la carga.
La reproducción remota se controla desde el reproductor, el mini reproductor, la notificación
y la pantalla expandida oficial de Cast. RTSP, RTMP, MMS, UDP y TCP siguen reproduciéndose en
el teléfono porque el receptor Cast predeterminado no admite esos protocolos.

**Playlists sin rodeos.** El centro de playlists usa tarjetas con mosaico, duración, origen y
acciones directas para reproducir, mezclar, poner después o añadir a la cola. Cada lista tiene
editor propio: búsqueda, selección múltiple, swipe para quitar, arrastre para reordenar, deshacer,
portada, descripción, limpieza de archivos ausentes y orden por título, artista, álbum o duración.
La cola actual se guarda con un toque y las listas entran/salen como M3U/M3U8. Las playlists
inteligentes se actualizan con la biblioteca, y una playlist de Spotify puede convertirse en una
lista local que distingue lo disponible de lo que todavía falta. Al usar Cast se envía la cola
completa, incluidos archivos locales mediante el puente temporal.

**Cola bajo control.** El dock y el reproductor abren una cola visual común. Desde ella se
pueden buscar y seleccionar varios MP3, audios o vídeos, insertarlos como siguientes o al final,
saltar a cualquier elemento, cambiar el orden y quitar uno o varios sin reiniciar el medio actual.
La selección múltiple de la biblioteca también pregunta si debe reproducir ahora, después o al
final, evitando reemplazar por accidente lo que ya estaba sonando.

**Se actualiza sola.** Vórtex consulta las publicaciones de este repositorio, elige el APK
que corresponde a la arquitectura del móvil, lo descarga con progreso y se lo entrega al
instalador del sistema. Al no estar en ninguna tienda, esta es la vía para no quedarse
atrás sin depender de que alguien recuerde volver aquí.

## Otras funciones

- Biblioteca por MediaStore con miniaturas extraídas del propio vídeo y caché en disco.
- Biblioteca inteligente con escaneo incremental, filtros por resolución, duración, tamaño y
  formato, agrupación automática de series y revisión segura de posibles duplicados.
- Árbol de carpetas navegable, con ramas plegables y migas de pan.
- Búsqueda unificada por nombre de fichero, carpeta y ruta, con resultados agrupados.
- Orden por fecha, nombre, duración, tamaño o resolución, y vista rejilla o lista.
- Selección múltiple, cola visual editable, playlists, M3U/M3U8, listas inteligentes y favoritos.
- "Continuar viendo" conserva cada 4 segundos la cola completa, la pista, posición, modo
  solo-audio, velocidad, repetición y aleatorio en un archivo atómico. El dock y los botones
  Bluetooth pueden restaurarla incluso después de que Android destruya el proceso.
- Gestos: brillo a la izquierda, volumen a la derecha, arrastre horizontal para buscar,
  doble toque lateral para ±10 s, bloqueo de controles.
- Selección de pistas de audio y subtítulos, velocidad de 0,5× a 3× con corrección de tono.
- Centro de subtítulos: abre SRT, WebVTT, ASS y SSA desde el selector de Android, ajusta la
  sincronización principal entre −60 y +60 segundos y permite superponer un segundo SRT/VTT
  con retardo, tamaño y fondo propios. Los documentos se copian a caché privada sin pedir
  permisos adicionales de almacenamiento.
- Búsqueda online opcional en OpenSubtitles por título e idioma. Cada resultado puede cargarse
  directamente como pista principal o secundaria; la API key configurada por la persona se
  cifra con Android Keystore y nunca se incluye en el repositorio ni se escribe en logs.
- Temporizador de apagado.
- Reproducción en segundo plano con la pantalla apagada.
- Fuentes de red con pegado rápido, detección de protocolo, favoritos e historial.
- Google Cast para HTTP(S), HLS y archivos locales, con controles remotos persistentes.
- Se abre desde otras apps para cualquier `video/*`, `audio/*`, HLS, RTSP, RTMP y MMS.

## Instalación

Descarga el APK de tu arquitectura desde [Releases](../../releases):

| Archivo | Para |
|---|---|
| `app-arm64-v8a-release.apk` | Prácticamente todos los móviles actuales |
| `app-armeabi-v7a-release.apk` | Móviles antiguos de 32 bits |
| `app-x86_64-release.apk` | Emuladores y algunos Chromebooks/dispositivos Intel |

Se publica un APK por arquitectura porque libVLC y el intérprete de Python de yt-dlp pesan
unas decenas de megas **por ABI**; un único APK universal rondaría los 250 MB.

Requiere Android 7.0 (API 24) o superior.

### Catálogos

Los metadatos de tienda están en `fastlane/metadata/android/` (formato fastlane, que es el
que consumen F-Droid e IzzyOnDroid), en español e inglés.

**IzzyOnDroid** es el catálogo objetivo: indexa los APK firmados de las Releases de este
repositorio y da actualizaciones automáticas a través del cliente de F-Droid.

**f-droid.org** propiamente dicho compila desde el código fuente en su propio servidor y
rechaza los binarios precompilados. Vórtex incluye tres (libVLC, el intérprete de Python de
yt-dlp y ffmpeg), así que entrar ahí exigiría compilarlos también desde fuente. No es
imposible —VLC lo hace— pero es un proyecto en sí mismo.

## Permisos y por qué

| Permiso | Para qué |
|---|---|
| `READ_MEDIA_VIDEO` / `READ_MEDIA_AUDIO` | Construir la biblioteca. Nada sale del dispositivo. |
| `SYSTEM_ALERT_WINDOW` | La ventana flotante sobre otras apps. |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Seguir sonando con la app cerrada. |
| `FOREGROUND_SERVICE_DATA_SYNC` | Que las descargas no se corten al salir de la app. |
| `POST_NOTIFICATIONS` | Controles en la notificación y progreso de descarga. |
| `INTERNET` | Streaming, descargas y puente local para Google Cast. |
| `ACCESS_NETWORK_STATE` | Mostrar el transporte activo, respetar sólo Wi-Fi y elegir la dirección local para Cast. |

## Compilar

```bash
git clone https://github.com/giver720/vortex-player.git
cd vortex-player
./gradlew assembleDebug
```

Para una build de release firmada, crea `keystore.properties` en la raíz:

```properties
storeFile=../keystore/mi-clave.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Sin ese fichero el proyecto compila igual; las builds de release simplemente salen sin firmar.

## Arquitectura

```
app/src/main/java/com/vortex/player/
├── data/            Biblioteca (MediaStore) y persistencia (Room)
├── playback/        Motor VLC, abstracción de pistas y MediaSessionService
│   ├── VlcPlayer.kt         libVLC expuesto como Player de Media3
│   └── PlaybackService.kt   Sesión multimedia con VLC como motor único
├── popup/           Ventana flotante (overlay + Compose)
├── download/        yt-dlp, cola, destino y publicación en la mediateca
├── network/         Validación, privacidad, favoritos e historial de fuentes remotas
├── cast/            Google Cast, handoff remoto y puente HTTP local temporal
├── spotify/         Catálogo, reglas actualizables, paginación y etiquetas
├── subtitle/        Parser local, caché segura y cliente oficial de OpenSubtitles
├── update/          Comprobación, descarga e instalación desde las Releases
└── ui/              Compose: biblioteca, reproductor, descargas, tema HUD
```

## Aviso

yt-dlp se incluye para descargar contenido propio o de libre distribución. Respeta los
términos de servicio de cada plataforma y los derechos de autor del material que descargues.

## Licencia

GPL-3.0, por compatibilidad con libVLC y yt-dlp.
