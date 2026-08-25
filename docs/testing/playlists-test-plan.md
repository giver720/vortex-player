# Estrategia de pruebas: centro de playlists

## Unitarias JVM

- M3U/M3U8 reconoce BOM, `#PLAYLIST`, `#EXTINF`, duración, título y orden.
- Una URI duplicada aparece una sola vez al importar.
- La exportación reemplaza saltos de línea y conserva URI, duración, artista y título.
- Mover un elemento conserva todos los IDs y rechaza índices fuera de rango.
- Ordenar por título, artista, álbum, duración o tipo produce IDs estables.
- Las estadísticas cuentan disponibles, ausentes, audio, vídeo y duración guardada.
- Una regla inteligente desconocida se ignora sin romper la playlist.
- Mover o quitar posiciones de la cola mantiene la identidad del medio actual y elige el
  siguiente superviviente cuando se elimina el que estaba sonando.

## Migración Room 11→12

- Conservar nombre, IDs, orden y fechas de todas las listas existentes.
- Añadir descripción vacía, portada nula, origen `LOCAL` y regla nula.
- Añadir snapshots vacíos/seguros a elementos históricos.
- Eliminar filas duplicadas por URI antes de crear el índice único.
- Confirmar que no se usa migración destructiva y que el schema 12 coincide con la entidad.

## Integración Android

- Crear una lista vacía abre directamente su editor.
- Añadir desde el editor excluye lo que ya está en la lista.
- Renombrar, describir y elegir/quitar una portada persiste tras reiniciar.
- Arrastrar y ordenar una lista grande conserva el medio actual y no duplica elementos.
- Swipe y selección múltiple quitan elementos; Deshacer repone posición y metadatos.
- Un archivo eliminado sigue visible como ausente y puede limpiarse.
- Guardar cola mantiene su orden y no interrumpe la reproducción.
- Añadir al final o reproducir después conserva medio, posición y estado pausa/reproducción.
- El selector de cola filtra Todo/Audio/Vídeo, busca título/artista y sólo añade lo marcado.
- Reordenar y eliminar varios elementos desde la cola actualiza también índice, Cast y sesión.
- Importar un M3U remoto reproduce HTTP/HLS; una pista Spotify faltante no se abre como audio.
- Las reglas inteligentes cambian al reescanear MediaStore y no muestran acciones manuales.
- Importar desde Spotify conserva el orden, coincidencias y faltantes.
- Cast recibe la cola completa, avanza al siguiente y recupera índice/posición al desconectar.

## Matriz manual

1. Listas de 0, 1, 100, 1 000 y 10 000 elementos.
2. Audio, vídeo, mezcla, URIs `content://`, archivo borrado y HTTP/HLS.
3. Nombre largo, Unicode, portada horizontal/vertical y descripción multilínea.
4. M3U UTF-8 con rutas locales, URLs, duplicados y extensión desconocida.
5. Cola en reproducción, pausa, aleatorio y Cast conectado.
6. Playlist Spotify totalmente local, parcialmente local y sin coincidencias.
7. Interrumpir el proceso durante add, remove, reorder y migración.

## Criterios de aceptación

- Ningún borrado de archivo al eliminar una playlist.
- Ningún duplicado por `(playlistId, uri)`.
- Ninguna pérdida de listas al migrar desde v11.
- Ningún bloqueo perceptible al desplazar o buscar en 1 000 elementos.
- Cero errores nuevos de lint y builds debug/release reproducibles.
