# ADR-0006: Biblioteca inteligente sobre MediaStore

**Status:** Accepted
**Date:** 2026-08-24
**Deciders:** Proyecto Vórtex

## Context

La biblioteca ya obtenía audio y vídeo de MediaStore, pero cada actualización sustituía toda la
lista y la interfaz sólo permitía navegar manualmente. Una biblioteca grande necesita conservar
los elementos sin cambios, comunicar qué cambió y ayudar a localizar series, formatos concretos
y archivos posiblemente repetidos. La solución no debe pedir acceso amplio al almacenamiento ni
borrar medios a partir de una heurística.

## Decision

- Mantener MediaStore como única fuente de verdad y hacer una consulta completa para detectar
  también eliminaciones.
- Reconciliar la nueva instantánea por URI, reutilizando la misma instancia de cada elemento que
  no cambió. Se informa total, altas, bajas, modificaciones, elementos intactos y tiempo.
- Agrupar episodios nombrados como `S01E02`, `1x02` o `Temporada 1 Episodio 2`.
- Marcar como posibles duplicados únicamente medios del mismo tipo con igual tamaño y duración
  redondeada al segundo. Se conserva por defecto el más reciente.
- No borrar automáticamente: la interfaz sólo selecciona las copias candidatas y deja la
  confirmación final al usuario y al diálogo del sistema.
- Ofrecer filtros combinables por resolución, duración, tamaño y contenedor usando sólo datos
  verificables de MediaStore.

## Flujo

```text
MediaStore -> MediaScanner -> MediaScanReconciler -> LibraryState
                                                    |
                                                    v
                                      LibraryIntelligenceEngine
                                         |                 |
                                      series        posibles duplicados
                                         \                 /
                                          pestaña SMART + filtros
```

## Options Considered

### Option A: MediaStore + índice derivado en memoria

| Dimension | Assessment |
|-----------|------------|
| Complejidad | Baja-media |
| Privacidad | Todo local |
| Escalabilidad | Adecuada para miles de medios |
| Riesgo de pérdida | Bajo; no hay borrado automático |

**Pros:** no duplica metadatos persistentes, detecta cambios externos y conserva los permisos
modernos de Android.
**Cons:** para detectar bajas todavía debe enumerar MediaStore completo.

### Option B: Base de datos propia como índice principal

| Dimension | Assessment |
|-----------|------------|
| Complejidad | Alta |
| Privacidad | Todo local |
| Escalabilidad | Alta |
| Riesgo de desincronización | Medio-alto |

**Pros:** consultas sofisticadas y actualizaciones parciales.
**Cons:** obliga a sincronizar dos fuentes y a migrar un esquema para datos que Android ya indexa.

### Option C: Hash completo de cada archivo

| Dimension | Assessment |
|-----------|------------|
| Precisión | Alta |
| Coste de CPU/E/S | Muy alto en bibliotecas grandes |
| Batería | Riesgo alto |
| Permisos | Acceso de lectura adicional según proveedor |

**Pros:** confirma duplicados byte a byte.
**Cons:** leer gigabytes en segundo plano no es aceptable como comportamiento predeterminado.

## Consequences

- Los refrescos conservan objetos y cachés de elementos intactos.
- La pestaña SMART descubre series y posibles copias sin crear carpetas artificiales.
- Los filtros afectan tanto las vistas normales como el análisis SMART.
- “Duplicado” se presenta siempre como posibilidad, no como hecho confirmado.
- Códec, HDR y bitrate no se ofrecen todavía porque MediaStore no garantiza esos campos; sólo se
  añadirán con extracción técnica bajo demanda y caché explícita.

## Action Items

1. [x] Reconciliar instantáneas y publicar el informe del escaneo.
2. [x] Añadir filtros técnicos combinables.
3. [x] Agrupar series y posibles duplicados.
4. [x] Añadir revisión y selección segura de copias.
5. [x] Cubrir patrones, firmas y límites con pruebas JVM.
6. [ ] Validar rendimiento con bibliotecas físicas de 1.000, 5.000 y 10.000 elementos.
