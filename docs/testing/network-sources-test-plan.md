# Estrategia de pruebas: fuentes de red

## Unitarias JVM

- Un host sin esquema se normaliza a HTTPS.
- `.m3u8` se clasifica como HLS y una extensión de audio como AUDIO.
- `file://`, esquemas desconocidos y direcciones sin servidor se rechazan.
- Usuario/contraseña, tokens y firmas permiten reproducir pero nunca persistir.
- Abrir dos veces la misma URL actualiza una sola entrada y conserva el favorito.
- El códec JSON conserva protocolo, tipo, favorito y última apertura.
- Limpiar recientes mantiene los favoritos.
- Los límites de 25 recientes y 100 favoritos se aplican de forma determinista.

## Integración Android

- Abrir una fuente crea un `MediaEntry` y usa el mismo `PlaybackService`/VLC de la biblioteca.
- La pantalla actualiza Wi-Fi, datos, Ethernet o VPN sin filtrar una red local válida.
- Cambiar VÍDEO/AUDIO se conserva en favoritos y desactiva la salida visual para radios.
- Una URL privada produce un `MediaEntry.persistable=false`; no llega al snapshot ni a las
  preferencias de posición o audio-only.
- El archivo `network-sources.json` sobrevive a cierre abrupto sin quedar truncado.

## Matriz manual

1. HTTP progresivo: MP4 y MP3, pausa, seek y reconexión.
2. HLS: VOD y directo, cambio de calidad del manifiesto y pérdida temporal de red.
3. RTSP: cámara en la misma Wi-Fi con y sin salida a Internet.
4. RTMP/MMS/UDP/TCP: una fuente válida y una inexistente por protocolo.
5. Repetir cada vídeo en HW y tras fallback SW; comprobar imagen, audio y posición.
6. Guardar, desfavoritar, borrar y limpiar recientes; reiniciar la aplicación.
7. Pegar una URL con credenciales y otra con `access_token`; reproducir, cerrar el proceso e
   inspeccionar `network-sources.json` y `playback-session.json` para confirmar que no aparecen.

## Criterios de aceptación

- Ningún cierre por URL vacía, inválida o servidor inaccesible.
- La red local no se bloquea por carecer de Internet.
- No hay secretos persistidos.
- Los errores permanentes respetan el máximo de recuperaciones del motor inteligente.
