# Estrategia de pruebas: Google Cast

## Unitarias JVM

- HTTP y HTTPS producen entrega directa; `content://` y `file://`, puente local.
- HLS sin duración se marca como directo y HLS con duración como contenido bajo demanda.
- RTSP y cualquier esquema desconocido se rechazan con un motivo visible.
- MIME comodín o ausente se concreta a partir de la extensión.
- El parser de rangos acepta rango cerrado, abierto y sufijo.
- Rangos múltiples, invertidos, mal formados o fuera del archivo devuelven 416.

## Integración Android

- El proveedor usa el receptor multimedia predeterminado y restaura sesiones Cast.
- El botón sólo se muestra cuando Google Play Services y Cast están disponibles.
- Al conectar, el medio actual conserva título, posición y estado reproducir/pausa.
- VLC sigue reproduciendo si la TV rechaza la carga y se pausa sólo tras confirmación.
- Cambiar de pista mientras Cast está conectado carga la nueva pista en la TV.
- Al terminar la sesión, el puente se cierra y VLC conserva la posición remota en pausa.
- El servicio del puente muestra una notificación de baja prioridad mientras está activo.

## Matriz manual

1. Chromecast y Google TV, tanto desde el reproductor como desde el mini reproductor.
2. MP4 H.264/AAC, WebM VP9/Opus, MP3, M4A y un contenedor/códec no compatible.
3. HTTP progresivo y HLS VOD/live, incluyendo pausa, seek y cambio de elemento.
4. Archivo local pequeño y uno mayor de 4 GB; iniciar, avanzar y retroceder varias veces.
5. Dispositivo con una sola Wi-Fi, Wi-Fi más VPN y red con aislamiento entre clientes.
6. Apagar la pantalla del teléfono, volver a la app y usar notificación/control expandido.
7. Apagar el receptor durante reproducción y recuperarlo; después desconectar voluntariamente.
8. Intentar RTSP/RTMP y comprobar el rechazo sin interrumpir VLC.
9. URL HLS firmada: confirmar que no queda en snapshot, favoritos ni historial.

## Criterios de aceptación

- Ninguna doble reproducción local/remota después de una carga confirmada.
- Ningún corte local si falla la conexión o el receptor rechaza el medio.
- Seek funcional sobre archivos locales mediante respuestas HTTP 206.
- El servidor no responde fuera de la ruta aleatoria del medio actual.
- Cero errores nuevos de lint y compilaciones debug/release reproducibles.
