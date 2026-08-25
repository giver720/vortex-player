# Plan de pruebas: Centro de subtítulos

## Objetivo

Verificar que la pista principal de VLC, el segundo overlay y sus relojes permanecen coordinados
sin bloquear el reproductor ni ampliar permisos de almacenamiento.

## Cobertura automatizada

| Área | Tipo | Casos |
|------|------|-------|
| Parser SRT | Unitario JVM | BOM, coma decimal, multilínea, etiquetas y entidades |
| Parser WebVTT | Unitario JVM | identificador, hora opcional y ajustes tras el timestamp |
| Validación | Unitario JVM | cues invertidos, timestamps inválidos y documento vacío |
| Línea temporal | Unitario JVM | inicio, fin exclusivo y cues superpuestos |
| Integración VLC | Compilación instrumentada | API de retardo y carga externa disponibles por ABI |

## Matriz manual de release

1. Abrir SRT, VTT, ASS y SSA como pista principal desde almacenamiento local.
2. Repetir la carga desde un proveedor `content://` como Drive o Dropbox.
3. Ajustar la pista principal a −1 s, −100 ms, +100 ms y +1 s mientras reproduce.
4. Cargar un SRT y un WebVTT como segunda pista; comprobar Unicode y saltos de línea.
5. Mostrar dos idiomas simultáneos durante diez minutos y tras búsquedas en la línea de tiempo.
6. Cambiar tamaño y fondo del segundo subtítulo en móvil, tablet, horizontal y PiP.
7. Cambiar de vídeo y confirmar que la segunda pista y su retardo no contaminan el siguiente.
8. Intentar cargar un documento de más de 8 MB y comprobar el error visible, sin cierre.

## Brechas conocidas

- El picker de documentos y `MediaPlayer.addSlave` requieren dispositivo o emulador.
- ASS/SSA conserva sus estilos en VLC, pero no está admitido como segundo overlay.
- La búsqueda online tiene su propia matriz de red, credenciales y cuota en
  `docs/testing/online-subtitles-test-plan.md`.
