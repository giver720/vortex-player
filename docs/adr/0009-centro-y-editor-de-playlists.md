# ADR 0009: centro y editor directo de playlists

**Estado:** Aceptado
**Fecha:** 2026-08-24
**Decisores:** proyecto Vórtex

## Contexto

La base de Vórtex ya podía crear listas y tenía operaciones internas para renombrar, quitar y
reordenar, pero la interfaz sólo permitía abrir, reproducir y borrar. El borrado estaba expuesto
sin confirmación; añadir contenido exigía volver a la biblioteca y mantener pulsado; un archivo
ausente desaparecía de la vista aunque su nombre siguiera en Room. El reordenamiento también
borraba y reinsertaba todos los elementos, cambiando sus IDs.

## Decisión

- Reemplazar el índice lineal por tarjetas con mosaico, métricas y acciones rápidas.
- Crear un editor por playlist con búsqueda, selección, swipe, arrastre y deshacer.
- Permitir añadir medios desde el propio editor y priorizar las listas recientes al añadir desde
  la biblioteca.
- Guardar nombre, descripción, portada, origen y regla inteligente en `playlists`.
- Guardar artista, álbum, duración y tipo en `playlist_items` para representar medios ausentes.
- Mantener un índice único `(playlistId, uri)` para evitar duplicados incluso con escrituras
  simultáneas.
- Reordenar mediante actualizaciones de posición, conservando IDs; sólo el deshacer reconstruye
  la secuencia dentro de una transacción.
- Añadir reproducción inmediata, aleatoria, siguiente y al final de la cola.
- Guardar la cola actual como playlist editable.
- Compartir una cola visual entre biblioteca y reproductor, con selector de audio/vídeo,
  búsqueda, inserción siguiente/final, salto, reordenamiento y eliminación múltiple.
- Importar y exportar M3U/M3U8 con un límite de lectura de 8 MB y 100 000 líneas.
- Resolver fuentes HTTP(S) importadas como medios remotos; una URL de Spotify nunca se trata como
  audio reproducible.
- Crear reglas dinámicas para recientes, nunca reproducidos, en progreso, audio, vídeo y contenido
  largo.
- Importar una playlist autorizada de Spotify conservando las coincidencias locales y mostrando
  las pistas faltantes.
- Cargar toda la cola compatible en Google Cast. Un único servidor temporal puede servir varios
  archivos locales para que el receptor precargue el siguiente.

## Consecuencias

- La migración 11→12 conserva listas existentes y elimina duplicados históricos antes de crear el
  índice único.
- Los archivos ausentes permanecen visibles y se pueden limpiar o recuperar mediante deshacer.
- Las playlists inteligentes no admiten reordenar ni quitar elementos: su regla es la fuente del
  orden y se recalculan al cambiar la biblioteca.
- Una entrada M3U remota se reproduce en memoria y no entra al snapshot de reproducción.
- La importación desde Spotify no descarga ni reproduce audio protegido; sólo crea referencias y
  asocia archivos locales encontrados.
- Cast omite futuros elementos con protocolos incompatibles, pero rechaza el envío si el elemento
  actual no puede reproducirse en el receptor.

## Acciones

1. [x] Migrar el esquema Room y exportar schema 12.
2. [x] Construir tarjetas visuales y acciones rápidas.
3. [x] Construir editor, búsqueda, selección, swipe, arrastre y deshacer.
4. [x] Añadir cola guardable e importación/exportación M3U.
5. [x] Añadir playlists inteligentes e importación Spotify.
6. [x] Enviar la cola completa a Google Cast.
7. [x] Añadir pruebas JVM y plan manual.
8. [ ] Ejecutar la matriz física con listas grandes, Spotify y Chromecast.
