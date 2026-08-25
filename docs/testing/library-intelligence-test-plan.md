# Estrategia de pruebas: biblioteca inteligente

## Objetivos de cobertura

- Validar todas las convenciones de episodios aceptadas y rechazar nombres ordinarios.
- Fijar los límites de resolución, duración y tamaño para evitar cambios silenciosos.
- Comprobar que la firma de posibles duplicados separa audio y vídeo.
- Confirmar que ningún flujo borra archivos automáticamente.
- Medir el refresco y desplazamiento con bibliotecas grandes en hardware real.

## Unitarias JVM

- `S01E02`, `1x02` y `Temporada N Episodio N` producen temporada y episodio correctos.
- Un nombre sin patrón no crea una serie.
- Duraciones con pequeñas diferencias dentro del mismo segundo comparten firma.
- El mismo tamaño y duración no mezcla audio con vídeo.
- 720p, 1080p, 1440p y 4K se clasifican por el lado corto.
- Los límites exactos de 10/60 minutos y 100 MB/1 GB pertenecen a un solo rango.
- M4V y M4A se reconocen dentro de la familia MP4; formatos no listados van a “Otros”.

## Integración Android

- Un cambio en MediaStore dispara un único refresco después del antirrebote.
- Los medios intactos conservan identidad; alta, baja y modificación actualizan el contador.
- Quitar permisos no provoca un borrado en disco ni una solicitud de acceso amplio.
- Los filtros combinados actualizan las vistas normales y el contenido SMART.
- Marcar copias abre la selección múltiple; borrar requiere confirmación del sistema.

## E2E manual

1. Cargar 1.000, 5.000 y 10.000 medios y registrar tiempo de escaneo, memoria y fluidez.
2. Copiar, renombrar y eliminar archivos desde otra app; verificar los contadores.
3. Probar nombres de series en español e inglés, con puntos, guiones y espacios.
4. Combinar resolución, duración, tamaño y formato y comparar el resultado con los archivos.
5. Revisar un grupo de duplicados: el archivo más nuevo no debe quedar preseleccionado.
6. Cancelar el diálogo de borrado y comprobar que todo permanece intacto.

## Criterios de salida

- Suite JVM y lint sin errores nuevos.
- APK debug y release compilables para las tres ABI.
- Ningún cierre al escanear 10.000 elementos.
- Tiempo objetivo de reconciliación menor a 250 ms, excluyendo la consulta del proveedor, en un
  dispositivo Android de gama media.

## Brechas actuales

- La firma por tamaño y duración produce candidatos, no una confirmación criptográfica.
- El rendimiento real de MediaStore depende del fabricante y requiere matriz física.
- Códec, HDR y bitrate quedan fuera hasta implementar extracción técnica bajo demanda.
