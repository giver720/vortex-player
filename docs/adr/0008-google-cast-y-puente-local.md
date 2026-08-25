# ADR 0008: Google Cast y puente local temporal

**Estado:** Aceptado
**Fecha:** 2026-08-24
**Decisores:** proyecto Vórtex

## Contexto

La siguiente fase requiere reproducir en Chromecast, Google TV y receptores Cast sin reemplazar
libVLC como motor local. Un receptor puede descargar una URL HTTP(S), pero no puede abrir el
`content://` privado de Android ni los protocolos RTSP, RTMP, MMS, UDP o TCP del centro de red.
El traspaso tampoco debe cortar la reproducción local antes de saber si la TV aceptó el medio.

## Decisión

- Integrar Google Cast Framework 22.2.0 y el receptor multimedia predeterminado.
- Mantener libVLC como único motor en el teléfono; Cast es una salida remota, no un segundo
  reproductor local.
- Enviar HTTP(S) y HLS directamente al receptor.
- Exponer archivos `content://` y `file://` mediante un servidor HTTP efímero ligado a la sesión.
- Generar una ruta impredecible con token aleatorio para cada medio y aceptar sólo esa ruta.
- Implementar `GET`, `HEAD`, `OPTIONS`, CORS y un rango HTTP por solicitud para carga y seek.
- Mantener el puente vivo con un servicio en primer plano visible sólo mientras la TV lo usa.
- Pausar VLC únicamente tras una respuesta satisfactoria a `RemoteMediaClient.load`.
- Conservar la posición remota en VLC al terminar la sesión, sin reproducir automáticamente.
- Usar el controlador expandido y la notificación oficiales, además de controles Compose.
- Rechazar en Cast los protocolos no soportados con un mensaje explícito; permanecen disponibles
  para reproducción local.

## Consecuencias

- El teléfono y el receptor deben estar en una red local donde puedan comunicarse entre sí.
- El token evita enumerar archivos, pero no cifra el tráfico del puente dentro de la LAN.
- El receptor predeterminado decide finalmente si admite el contenedor y los códecs del archivo.
- Una URL remota que exige cabeceras o cookies privadas puede fallar porque la TV realiza su propia
  petición; Vórtex no transfiere credenciales del navegador.
- Si la carga remota falla, VLC continúa en el teléfono y el puente se destruye.
- La biblioteca Cast se fija en 22.2.0 porque 22.3.1 fue compilada con metadatos Kotlin 2.2,
  incompatibles con Kotlin 2.0.21 del proyecto.

## Acciones

1. [x] Registrar el proveedor Cast y el receptor predeterminado.
2. [x] Añadir botón de ruta, estado remoto y controlador expandido.
3. [x] Implementar handoff confirmado entre VLC y Cast.
4. [x] Implementar puente local con token, CORS, `HEAD` y rangos.
5. [x] Conectar el mini reproductor y cambios de elemento de la cola.
6. [x] Añadir política pura, pruebas JVM y matriz manual.
7. [ ] Ejecutar la matriz física en Chromecast y Google TV.
