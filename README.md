# Clash of Music

Clash of Music es una aplicación Android en Kotlin para organizar y escuchar una biblioteca de audio. Incluye reproducción en segundo plano, cola, modo aleatorio, playlists, favoritos y controles desde la notificación o la pantalla de bloqueo.

Puedes buscar música y preparar descargas a partir de enlaces compatibles. Los enlaces de Spotify se usan para identificar pistas y buscar coincidencias en YouTube. La app permite conservar el formato original o convertir a MP3, M4A u Opus. La biblioteca y los respaldos se guardan en el dispositivo.

## Compilar desde el código

Necesitas JDK 17 y Android SDK con la plataforma 35. Android Studio puede configurar el SDK mediante `local.properties`. Si compilas desde una terminal, configura `JAVA_HOME` y `ANDROID_HOME` según tu instalación. La app requiere Android 8.0 o posterior (API 26).

En Windows, desde la raíz del repositorio:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

En macOS o Linux, ejecuta las mismas tareas con `./gradlew`.

Los APK de prueba se generan por arquitectura en `app/build/outputs/apk/debug/`. Por ejemplo, un teléfono ARM de 64 bits utiliza `app-arm64-v8a-debug.apk`.

La compilación opcional Android ARM64 con FFmpeg, Python y QuickJS de procedencia documentada se prepara siguiendo [Componentes de terceros](THIRD_PARTY.md#candidato-android-arm64).

## Tecnologías

La interfaz usa Jetpack Compose y Material 3. La reproducción usa Media3 y los datos locales se guardan con Room. NewPipeExtractor, yt-dlp, FFmpeg y aria2 participan en la extracción y el procesamiento de audio. Consulta [Componentes de terceros](THIRD_PARTY.md) para ver sus referencias y las consideraciones de distribución.

## Licencia

El código propio de Clash of Music se distribuye bajo la [GNU General Public License, versión 3](LICENSE) (`GPL-3.0-only`). Los componentes de terceros se rigen por sus licencias respectivas. Consulta sus referencias en [Componentes de terceros](THIRD_PARTY.md).
