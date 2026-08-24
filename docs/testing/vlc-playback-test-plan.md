# Plan de pruebas: motor VLC

## Objetivo

Proteger los caminos críticos del reproductor único: restauración de cola, cálculo del audio y
ciclo nativo de preparación/reproducción/liberación.

## Cobertura

| Área | Tipo | Casos principales | Objetivo |
|------|------|-------------------|----------|
| Códec de sesión | Unitario JVM | round trip de 1.000 items, corrupción, versión, nulos, límites | Todas las ramas de validación |
| Ecualizador | Unitario JVM | apagado, plano, interpolación, graves/claridad, headroom | Todas las reglas DSP puras |
| libVLC | Instrumentado | WAV local, EQ, prepare, play y release | Smoke test por ABI antes de release |
| MediaSession | Integración manual | cerrar proceso, botón Bluetooth, reanudar posición | Android 8, 12 y 15 |
| Cola grande | Integración manual | 1.000+ pistas, reinicio y restauración | Sin pérdida ni bloqueo visible |

## Casos de release

1. Reproducir audio y vídeo locales y alternarlos dentro de la misma cola.
2. Pausar, forzar cierre del proceso, abrir Vórtex y reanudar desde el dock.
3. Terminar el servicio y pulsar Play en auriculares Bluetooth.
4. Activar cada preset y cambiar entre altavoz, cable y Bluetooth sin cortar la pista.
5. Activar volumen extra con y sin protección de picos y confirmar que el ajuste llega a VLC.
6. Ejecutar `testDebugUnitTest`, `testReleaseUnitTest`, `assembleDebugAndroidTest`, `lintRelease`
   y `assembleRelease`.

## Brechas conocidas

- La reproducción nativa necesita dispositivo/emulador; el build sólo confirma que el APK de
  pruebas compila.
- Los controles Bluetooth y la recuperación tras reinicio requieren hardware o una prueba de
  sistema, por lo que permanecen en la lista manual de release.
