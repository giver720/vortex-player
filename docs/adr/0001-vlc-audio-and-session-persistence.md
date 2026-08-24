# ADR-0001: Audio nativo VLC y sesión persistente

**Status:** Accepted  
**Date:** 2026-08-24  
**Deciders:** Proyecto Vórtex

## Context

Vórtex usa libVLC como único motor. libVLC no publica un identificador de sesión de audio
Android, por lo que los efectos basados en `DynamicsProcessing` no pueden procesar su salida.
Además, `PlaybackHub` conservaba la cola sólo mientras vivía el proceso; tras cerrar la app o
reiniciar el teléfono no había datos con los que responder a un botón multimedia.

La solución debe aceptar playlists grandes, sobrevivir escrituras interrumpidas y mantener una
sola ruta de reproducción.

## Decision

- Aplicar ecualización, preamplificación, graves y claridad mediante
  `MediaPlayer.Equalizer` de libVLC.
- Implementar protección de picos como margen estático de preamplificación, ya que la API de
  libVLC no ofrece un limitador dinámico.
- Guardar la cola y el estado de sesión en un JSON versionado mediante `AtomicFile`.
- Declarar `MediaButtonReceiver` y responder a `MediaSession.Callback.onPlaybackResumption`.
- Mantener Room para el historial por archivo y DataStore para preferencias pequeñas; la cola
  completa vive en su archivo específico.

## Options Considered

### Option A: JSON atómico + ecualizador libVLC

| Dimension | Assessment |
|-----------|------------|
| Complexity | Media |
| Cost | Sin dependencias nuevas |
| Scalability | Adecuada para miles de elementos |
| Team familiarity | Alta: Kotlin, JSON y libVLC existentes |

**Pros:** conserva VLC como único motor, no requiere migración de base de datos y tolera un
cierre durante la escritura.  
**Cons:** reescribe el documento completo al actualizar la posición y no ofrece compresión
dinámica real.

### Option B: Tablas Room + efectos de sesión Android

| Dimension | Assessment |
|-----------|------------|
| Complexity | Alta |
| Cost | Migración y mantenimiento de esquema |
| Scalability | Alta |
| Team familiarity | Alta |

**Pros:** actualizaciones parciales y consultas relacionales.  
**Cons:** la sesión es un único agregado, obliga a varias tablas/migraciones y los efectos
Android siguen sin poder engancharse al audio de VLC.

### Option C: Volver a ExoPlayer para el audio

| Dimension | Assessment |
|-----------|------------|
| Complexity | Alta |
| Cost | Dos motores y dos rutas de fallos |
| Scalability | No cambia la cola |
| Team familiarity | Media |

**Pros:** sesión de audio Android disponible.  
**Cons:** contradice la decisión VLC-only y vuelve a introducir diferencias de formato,
estado y comportamiento entre audio y vídeo.

## Trade-off Analysis

La escritura completa del JSON es aceptable porque ocurre cada cuatro segundos, es serial y el
archivo sólo contiene metadatos. El beneficio principal es evitar una migración de Room para un
estado que siempre se lee y escribe como una unidad. La protección de picos estática es menos
sofisticada que un limitador, pero evita clipping sin fingir una capacidad que libVLC no expone.

## Consequences

- La última cola vuelve a aparecer tras recrear el proceso.
- Auriculares y Bluetooth pueden reanudar aun cuando el servicio ya no estaba vivo.
- Los perfiles de ecualización se aplican dentro del motor y cambian con la salida activa.
- Ambiente, espacialidad y compresión quedan ocultos al no estar soportados por esta API.
- Si en el futuro libVLC expone un compresor/limitador, el plan puro puede ampliarse sin cambiar
  la persistencia ni la interfaz de sesión.

## Action Items

1. [x] Implementar códec y almacenamiento atómico.
2. [x] Implementar reanudación de Media3 y restauración del dock.
3. [x] Integrar el ecualizador nativo y perfiles por salida.
4. [x] Añadir pruebas JVM del códec y el cálculo DSP.
5. [x] Añadir smoke test instrumentado de libVLC.
6. [ ] Ejecutar el smoke test en un teléfono o emulador durante la validación de release.
