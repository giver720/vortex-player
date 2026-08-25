# ADR 0007: centro de fuentes de red

**Estado:** Aceptado
**Fecha:** 2026-08-24
**Decisores:** proyecto Vórtex

## Contexto

libVLC ya podía abrir HTTP, HLS y RTSP cuando otra aplicación enviaba un enlace, pero Vórtex no
tenía un punto propio para introducirlo, repetirlo o conservar una cámara o radio habitual. Un
historial ingenuo también podía escribir en texto plano URLs firmadas, tokens o credenciales.

## Decisión

- Crear un centro Compose accesible desde el menú principal.
- Admitir HTTP(S), HLS/M3U8, RTSP, RTMP, MMS, UDP y TCP.
- Añadir HTTPS automáticamente cuando se pega un host web sin esquema.
- Detectar HLS y extensiones de audio, permitiendo corregir manualmente audio/vídeo.
- Reutilizar `PlaybackService` y libVLC: no se introduce un segundo motor.
- Mantener hasta 25 recientes y 100 favoritos, deduplicados por URL, en un `AtomicFile`.
- Permitir cámaras de red local aunque el transporte no anuncie salida a Internet.
- Considerar privada una URL con `userinfo`, token, autorización, firma, clave o contraseña.
  Estas fuentes sólo viven en memoria y quedan excluidas del historial, favoritos, preferencias
  por medio y snapshot de reproducción.

## Consecuencias

- Una fuente guardada conserva nombre, protocolo, tipo y última apertura.
- Un archivo JSON truncado conserva la versión anterior gracias a `AtomicFile`.
- La validación, el límite del historial y el códec se prueban en JVM.
- La disponibilidad real de un servidor sólo la confirma VLC al abrirlo; la UI no hace una
  petición previa que pudiera duplicar consumo, fallar con autenticación o alterar una cámara.
- Los enlaces privados no pueden ofrecer «continuar viendo» después de cerrar el proceso.

## Acciones

1. [x] Implementar parser y política pura de biblioteca de red.
2. [x] Añadir persistencia atómica y exclusión de secretos.
3. [x] Crear pantalla visual con favoritos, recientes y selector audio/vídeo.
4. [x] Conectar las fuentes con la cola, MediaSession y libVLC existentes.
5. [x] Añadir pruebas JVM y plan manual por protocolo.
6. [ ] Ejecutar la matriz física con cámara RTSP, radio, HLS y Wi-Fi local sin Internet.
