# ADR 0004: búsqueda online de subtítulos con OpenSubtitles

- Estado: aceptado
- Fecha: 2026-08-24

## Contexto

El Centro de subtítulos ya carga documentos locales y muestra dos idiomas a la vez, pero
obliga a salir de Vórtex para encontrar el archivo. OpenSubtitles ofrece un API oficial con
búsqueda por título e idioma y enlaces temporales de descarga sujetos a cuota.

## Decisión

Vórtex integra directamente los endpoints oficiales `GET /subtitles` y `POST /download`.
Cada persona configura la API key de una aplicación creada en OpenSubtitles. La key nunca se
incluye en el repositorio ni como constante de compilación: se cifra en el dispositivo con
AES-GCM y una clave no exportable del Android Keystore.

La primera versión online:

- busca por título en español, inglés o ambos y muestra como máximo 20 resultados;
- prioriza los resultados con más descargas;
- identifica subtítulos para personas con discapacidad auditiva y traducciones automáticas;
- descarga sólo mediante HTTPS y limita la respuesta a 8 MB;
- copia el resultado a la caché privada existente;
- permite cargarlo directamente como pista principal o segundo subtítulo;
- muestra la cuota restante cuando el API la comunica.

No se solicita el usuario, contraseña ni token de una cuenta de OpenSubtitles. La key puede
eliminarse desde el panel y no se escribe en logs.

## Consecuencias

La búsqueda depende de red, disponibilidad y cuota de un tercero. La app presenta los errores
HTTP sin reintentos automáticos para no consumir más cuota. Cambios futuros del esquema quedan
aislados en `OpenSubtitlesParser` y cubiertos por pruebas JVM.

El archivo descargado continúa sujeto a las mismas reglas del Centro de subtítulos: VLC acepta
la pista principal; la segunda capa requiere SRT o WebVTT que el parser local pueda interpretar.
