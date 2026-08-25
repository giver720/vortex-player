# ADR-0002: Audio Pro sobre capacidades verificables de libVLC

**Status:** Accepted
**Date:** 2026-08-24
**Deciders:** Proyecto Vórtex

## Context

Vórtex necesita controles de sonido más rápidos, visuales y confiables sin abandonar la
decisión de usar VLC como motor único. La API Java de libVLC 3.6 permite ecualizador,
preamplificación y volumen software, y el núcleo VLC admite ReplayGain. No expone, sin
embargo, muestras PCM, picos en tiempo real ni un compresor/limitador dinámico controlable.

## Decision

- Crear perfiles Audio Pro —Seguro, Potente, Noche y Voz— como configuraciones reproducibles
  de etapas que VLC sí aplica.
- Añadir bypass A/B Tono original/Procesado sin borrar la curva ni el boost guardados. La
  normalización ReplayGain pertenece al motor y permanece activa en ambos lados de la prueba.
- Activar ReplayGain por pista con preamplificación predeterminada de 0 dB y protección de
  pico. Los archivos sin etiquetas ReplayGain no reciben ganancia adicional.
- Calcular un diagnóstico preventivo de clipping a partir de la suma teórica de curva,
  preamplificación y boost. La interfaz lo identifica explícitamente como estimación.
- Mantener la protección existente como margen estático; no presentarla como limitador
  dinámico.

## Options Considered

### Option A: Audio Pro declarativo sobre libVLC

| Dimension | Assessment |
|-----------|------------|
| Complexity | Baja-media |
| Cost | Sin dependencias nuevas |
| Compatibilidad | Conserva todos los formatos y una sola sesión |
| Riesgo | El diagnóstico no mide el PCM real |

**Pros:** audible, comprobable, persistente y coherente con VLC-only.
**Cons:** no puede comprimir dinámicamente una película con diálogos bajos.

### Option B: Efectos Android por sesión

| Dimension | Assessment |
|-----------|------------|
| Complexity | Media |
| Cost | Dependencia del fabricante |
| Compatibilidad | libVLC no publica una sesión utilizable |
| Riesgo | Controles visibles que no procesan audio |

**Pros:** APIs conocidas de Android.
**Cons:** ya se comprobó que no pueden engancharse de forma confiable a la salida de VLC.

### Option C: Capturar PCM y ejecutar DSP nativo propio

| Dimension | Assessment |
|-----------|------------|
| Complexity | Muy alta |
| Cost | C/C++, latencia, sincronización A/V y mantenimiento por ABI |
| Compatibilidad | Requiere sustituir la salida de audio de VLC |
| Riesgo | Regresiones de estabilidad y batería |

**Pros:** permitiría limitador, compresor y vúmetro reales.
**Cons:** convierte la ruta de audio en un segundo producto y amenaza la estabilidad del motor.

## Trade-off Analysis

Se prioriza una mejora útil ahora, sin afirmar capacidades inexistentes. El modo Potente puede
superar el margen de una mezcla ya masterizada; por eso su riesgo se muestra en magenta. Seguro,
Noche y Voz reservan margen estático y son apropiados como punto de partida. Un DSP PCM propio
queda como proyecto independiente y sólo debería aceptarse con pruebas de latencia y consumo.

## Consequences

- Cambiar de carácter sonoro requiere un toque, no ajustar varios deslizadores.
- Original/Procesado permite comprobar al instante si el cambio realmente se oye.
- ReplayGain mejora la consistencia entre pistas que incluyen metadatos compatibles.
- El usuario ve el riesgo teórico antes de combinar una curva agresiva con boost.
- Aún no existe compresión dinámica; el control permanece oculto en VLC.

## Action Items

1. [x] Implementar perfiles y persistencia por salida.
2. [x] Implementar bypass A/B.
3. [x] Activar ReplayGain y protección de pico nativas.
4. [x] Añadir diagnóstico puro y pruebas JVM.
5. [ ] Medir en dispositivos físicos la diferencia de volumen y distorsión por salida.
6. [ ] Evaluar un prototipo DSP PCM separado antes de prometer limitador dinámico.
