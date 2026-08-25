# Estrategia de pruebas: motor de reproducción inteligente

## Objetivos de cobertura

- 100 % de ramas del clasificador de fuentes y de la política de recuperación mediante JVM.
- Compilación instrumentada de todas las superficies Android afectadas.
- Cero errores nuevos de lint y APK release reducible/firmable.
- Prueba física de al menos un archivo por códec crítico y una fuente por protocolo antes de release.

## Pirámide

### Unitarias rápidas

- `content://` y archivos locales reciben `file-caching` corto.
- HTTP obtiene reconexión y caché media.
- HLS se detecta por extensión y MIME y obtiene caché de streaming.
- RTSP fuerza TCP.
- `lowRamDevice` reduce caché sin desactivar hardware.
- Primer fallo cambia HW → software, el segundo mantiene software y el tercero se expone.
- El formato de resolución no inventa dimensiones ausentes.

### Integración Android

- Construcción de `VlcPlayer` con la biblioteca nativa para las tres ABI.
- El panel observa el `StateFlow` sin bloquear el hilo principal.
- Cambiar medio reinicia contador y plan; cambiar pista no lo hace.
- Reintento seguro conserva posición aproximada, velocidad, boost, pista y salida de vídeo.
- Liberar el player elimina el watchdog y no deja callbacks posteriores.

### E2E manual en dispositivos

1. Reproducir H.264/AAC, HEVC, VP9, AV1, MKV multiaudio y audio sin vídeo.
2. Incluir 720p, 1080p, 4K, HDR10 y un archivo truncado o deliberadamente dañado.
3. Probar al menos un móvil Qualcomm, MediaTek y un dispositivo Android de memoria baja.
4. Confirmar que un fallo hardware cambia a `SOFTWARE`, conserva el instante y sólo suma una recuperación.
5. Dejar una fuente realmente congelada más de 16 s; comprobar recuperación sin bucle.
6. Probar HTTP progresivo, HLS VOD/live y RTSP con red estable, lenta y reconexión.
7. Abrir el panel y comparar códec, resolución y FPS con `MediaInfo` o datos conocidos.
8. Pausar más de 30 s: el watchdog no debe actuar. Repetir durante buffering.
9. Probar PiP, popup, pantalla apagada, auriculares y pérdida/recuperación de audio focus.

## Casos de fallo y seguridad

- Fuente inexistente: error visible sin reintentos ilimitados.
- Códec no compatible ni por software: exactamente dos recuperaciones automáticas.
- Estadísticas VLC nulas o parciales: mostrar `—`/cero sin cierre.
- Seek durante recuperación: el último comando del usuario debe seguir siendo recuperable.
- No se envía ni persiste telemetría; inspeccionar tráfico y almacenamiento de la app.

## Brechas actuales

- JVM valida decisiones, no MediaCodec ni bibliotecas nativas.
- `assembleDebugAndroidTest` comprueba integración binaria, pero no sustituye ejecutar en hardware.
- Cuadros perdidos, HDR y bloqueos varían según firmware y sólo se aceptan tras la matriz física.
