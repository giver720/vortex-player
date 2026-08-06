# libVLC usa JNI y reflexión sobre estas clases
-keep class org.videolan.libvlc.** { *; }
-keep class org.videolan.medialibrary.** { *; }
-dontwarn org.videolan.**

# Media3
-dontwarn androidx.media3.**

# yt-dlp: la librería lanza un intérprete de Python y resuelve clases por nombre,
# así que R8 no puede renombrar ni eliminar nada de aquí.
-keep class com.yausername.** { *; }
-keep class com.yausername.youtubedl_android.mapper.** { *; }
-dontwarn com.yausername.**

# Commons Compress descomprime el paquete de Python al primer arranque. Su
# inicializador estático se apoya en clases que R8 daba por muertas, y sin estas
# reglas la app se cae con ExceptionInInitializerError nada más abrir Descargas.
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**
-keep class org.tukaani.xz.** { *; }
-dontwarn org.tukaani.xz.**
-dontwarn org.brotli.dec.**

# Jackson (usado por youtubedl-android para leer el JSON de yt-dlp)
-keep class com.fasterxml.jackson.** { *; }
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-dontwarn com.fasterxml.jackson.**
