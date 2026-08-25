# Plan de pruebas: subtítulos online

## Pruebas automatizadas

- Respuesta de búsqueda con idioma, release, contador, CC y traducción automática.
- Omisión de resultados sin `file_id` descargable.
- Respuestas vacías sin error.
- Respuesta de descarga con enlace temporal, nombre y cuota restante.
- Rechazo de una descarga sin enlace.
- Filtros de idioma esperados por el API.
- Suite JVM completa en `debug` y `release`.
- `lintRelease`, `assembleDebugAndroidTest` y APK de release firmado.

## Matriz manual con dispositivo

1. Abrir un vídeo, entrar en Subtítulos y configurar una API key válida.
2. Cerrar y abrir la app: comprobar que aparece configurada y que la key no se vuelve a mostrar.
3. Buscar por español, inglés y ambos; comprobar estados vacío, error y resultados.
4. Descargar un resultado como principal y confirmar imagen, pausa, búsqueda y cambio de pista.
5. Descargar otro como segundo y comprobar dos textos simultáneos, retardo, tamaño y fondo.
6. Probar API key incorrecta y cuota agotada: el panel debe seguir operativo y sin cierre.
7. Pulsar Cambiar key y verificar que la credencial anterior deja de estar disponible.
8. Repetir sin red, con Wi-Fi lento y después de cambiar de archivo en la cola.

## Seguridad y límites

- Confirmar con una inspección del APK y del logcat que la API key no aparece en texto plano.
- Simular `Content-Length` mayor de 8 MB y una respuesta progresiva mayor de 8 MB.
- Rechazar enlaces de descarga que no usen HTTPS.
- Verificar que no hay reintento implícito ante HTTP 429.
