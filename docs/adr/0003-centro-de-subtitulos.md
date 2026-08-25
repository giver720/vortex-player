# ADR-0003: Centro de subtítulos híbrido VLC + overlay Compose

**Status:** Accepted
**Date:** 2026-08-24
**Deciders:** Proyecto Vórtex

## Context

libVLC 3.6 puede seleccionar una pista, cargar un subtítulo externo y modificar su retardo,
pero sólo renderiza una pista de subtítulos a la vez. Vórtex necesita carga desde el selector de
Android, sincronización fina y dos idiomas simultáneos sin introducir un segundo motor de vídeo.
Los proveedores `content://` tampoco garantizan que VLC pueda reabrir directamente el documento.

## Decision

- Mantener la pista principal dentro de VLC para conservar compatibilidad con SRT, WebVTT, ASS,
  SSA y las pistas incrustadas del contenedor.
- Copiar cada documento elegido a una caché privada limitada a 8 MB y entregar a VLC una URI de
  archivo local. No se solicitan permisos de almacenamiento adicionales.
- Exponer el retardo principal de VLC entre −60 y +60 segundos, con pasos de 100 ms y 1 segundo.
- Interpretar SRT y WebVTT en Kotlin para un segundo subtítulo independiente, dibujado con
  Compose sobre el vídeo y sincronizado con el reloj del mismo `Player`.
- Permitir tamaño y fondo del segundo subtítulo. Mantener su estado ligado al medio actual para
  no aplicar accidentalmente un idioma o retardo a la siguiente película.
- Separar la búsqueda online. La integración posterior se define en el ADR 0004 para no acoplar
  red y credenciales al parser ni al renderizado local.

## Options Considered

### Option A: Pista VLC principal + overlay secundario

| Dimension | Assessment |
|-----------|------------|
| Complexity | Media |
| Compatibilidad | Alta para la pista principal; SRT/VTT para la secundaria |
| Sincronización | Un reloj compartido de Media3/VLC |
| Privacidad | Todo local |

**Pros:** dos idiomas reales, sin recodificar vídeo ni sustituir VLC.
**Cons:** el segundo subtítulo no conserva estilos avanzados ASS.

### Option B: Dos pistas dentro de VLC

| Dimension | Assessment |
|-----------|------------|
| Complexity | Baja |
| Compatibilidad | No disponible en la API Java de libVLC 3.6 |
| Sincronización | Nativa |
| Privacidad | Local |

**Pros:** renderizado nativo.
**Cons:** VLC expone una sola pista SPU seleccionada, así que no resuelve el requisito.

### Option C: Renderizar todos los subtítulos en Compose

| Dimension | Assessment |
|-----------|------------|
| Complexity | Alta |
| Compatibilidad | Habría que implementar ASS/SSA y fuentes |
| Sincronización | Control total |
| Privacidad | Local |

**Pros:** estilos uniformes y dos o más pistas.
**Cons:** perdería la madurez del renderizador de VLC y duplicaría gran parte de libass.

## Trade-off Analysis

El enfoque híbrido aprovecha VLC para el caso complejo y limita el parser propio a formatos de
texto sencillos. Copiar a caché evita fallos por permisos revocados y mantiene la carga fuera del
hilo principal. El límite de 8 MB protege memoria ante documentos incorrectos o maliciosos.

## Consequences

- Se pueden abrir subtítulos externos aun desde proveedores de nube compatibles con SAF.
- La pista principal y la secundaria se ajustan por separado.
- El segundo subtítulo funciona también durante streaming porque sólo depende del reloj.
- Los estilos ASS avanzados permanecen disponibles únicamente en la pista principal de VLC.
- La búsqueda y descarga online siguen siendo opcionales y requieren configurar OpenSubtitles;
  su diseño de credenciales, cuotas y límites queda en el ADR 0004.

## Action Items

1. [x] Implementar carga limitada y copia segura a caché.
2. [x] Implementar parser SRT/WebVTT y línea de tiempo.
3. [x] Exponer el retardo SPU de VLC.
4. [x] Implementar overlay secundario y controles visuales.
5. [x] Añadir pruebas JVM del parser y la selección temporal.
6. [ ] Probar carga desde almacenamiento local, Drive y Dropbox en dispositivos físicos.
7. [x] Diseñar e implementar autenticación, cuotas y descarga online (ADR 0004).
