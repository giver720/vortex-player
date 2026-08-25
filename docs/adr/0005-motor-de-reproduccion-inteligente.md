# ADR 0005: motor de reproducción inteligente sobre libVLC

**Estado:** Aceptado
**Fecha:** 2026-08-24
**Decisores:** proyecto Vórtex

## Contexto

Vórtex usaba decodificación hardware y cachés globales fijas para cualquier medio. Un MP4 local,
un HLS y una cámara RTSP recibían la misma estrategia. Ante `EncounteredError`, el adaptador
Media3 pasaba directamente a error y perdía la intención de reproducir, aunque VLC pudiera
recuperarse al reabrir el medio o desactivar el decodificador del fabricante.

El reproductor debe privilegiar batería y rendimiento, pero también recuperarse sin convertir
un archivo incompatible en un bucle infinito. Los diagnósticos deben ser visibles y baratos.

## Decisión

Mantener libVLC como único motor y añadir una capa determinista de inteligencia alrededor:

- clasificar cada URI como local, HTTP, HLS, RTSP u otra fuente de red;
- aplicar caché por medio: corta para local, media para HTTP/RTSP y mayor para HLS;
- reducir esas cachés en dispositivos que Android marca como `lowRamDevice`;
- comenzar con hardware y, ante el primer fallo o bloqueo confirmado, reabrir en software;
- permitir un segundo reintento seguro y detenerse después para evitar bucles;
- conservar índice, posición, velocidad, audio y voluntad de reproducción al recuperar;
- vigilar avance cada cuatro segundos y considerar bloqueo sólo tras 16 segundos en estado listo;
- vigilar también los cuadros realmente mostrados: si el audio avanza al menos 3 segundos pero
  hardware no muestra ninguno durante 8 segundos, reabrir automáticamente en software;
- aplicar esa vigilancia sólo con una superficie conectada y recordar el fallback para ese medio
  durante la sesión;
- publicar estadísticas nativas como máximo una vez por segundo;
- ofrecer un reintento manual en software desde un panel de diagnóstico.

## Opciones consideradas

### Opción A: política adaptativa alrededor de libVLC

| Dimensión | Evaluación |
|---|---|
| Complejidad | Media |
| Batería | Hardware en la ruta normal |
| Compatibilidad | Fallback software automático |
| Mantenimiento | Un solo motor |

**Pros:** conserva MediaSession, ecualizador, pistas y superficies actuales; el plan es comprobable
sin Android.
**Contras:** no puede conocer por adelantado todos los fallos de códec de cada fabricante.

### Opción B: elegir hardware o software manualmente

| Dimensión | Evaluación |
|---|---|
| Complejidad | Baja |
| Batería | Depende de la configuración |
| Compatibilidad | Requiere intervención |
| Mantenimiento | Bajo |

**Pros:** comportamiento explícito.
**Contras:** obliga a entender decodificadores y deja la reproducción rota hasta cambiar el ajuste.

### Opción C: recuperar cambiando entre VLC y Media3/ExoPlayer

| Dimensión | Evaluación |
|---|---|
| Complejidad | Muy alta |
| Batería | Variable |
| Compatibilidad | Amplia, pero inconsistente |
| Mantenimiento | Dos motores y dos estados |

**Pros:** una segunda implementación completa.
**Contras:** duplica cola, pistas, subtítulos, DSP, superficies, errores y sesión multimedia; contradice
la decisión de VLC como motor único.

## Análisis de compromisos

Dos recuperaciones cubren el fallo habitual de MediaCodec y una reapertura limpia sin esconder
errores permanentes. El watchdog de tiempo sólo actúa en `STATE_READY` con intención de
reproducir, por lo que no confunde pausa ni buffering deliberado con congelamiento. Un segundo
watchdog usa `displayedPictures`: requiere pista, superficie, estadísticas, tiempo de gracia y
avance del reloj. Así detecta audio con pantalla negra sin degradar el modo solo-audio ni la
reproducción en segundo plano. La ventana general de 16 segundos favorece evitar falsos positivos
sobre una recuperación agresiva.

La telemetría es local, no persiste historial y no sale del dispositivo. Su frecuencia de un
segundo permite una UI útil sin consultar estadísticas nativas en cada evento de tiempo.

## Consecuencias

- Archivos incompatibles con MediaCodec pueden continuar automáticamente en software.
- Las fuentes remotas reciben un búfer apropiado sin penalizar los archivos locales.
- El panel permite explicar si VLC usa HW/SW y si pierde cuadros o paquetes.
- Un fallo persistente se hace visible después de dos intentos, no queda oculto.
- La prueba real de congelamiento, HDR y códecs de fabricante continúa necesitando dispositivos.

## Acciones

1. [x] Implementar plan puro por fuente y memoria del dispositivo.
2. [x] Aplicar opciones por `Media` y fallback HW → software.
3. [x] Añadir watchdog y límite de recuperaciones.
4. [x] Exponer estadísticas VLC y panel visual.
5. [x] Añadir pruebas JVM de clasificación y política.
6. [x] Detectar audio con cero cuadros mostrados y degradar HW → software.
7. [ ] Ejecutar la matriz física de códecs y fabricantes.
