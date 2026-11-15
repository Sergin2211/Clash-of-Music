# Componentes de terceros

Las versiones declaradas por la app están en `app/build.gradle.kts`. Cada componente conserva los avisos y términos de su propio proyecto.

| Componente | Uso y referencia |
| --- | --- |
| Kotlin y kotlinx.coroutines | Lenguaje y tareas asíncronas. [Kotlin](https://kotlinlang.org/) · [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| AndroidX | Jetpack Compose y Material 3 para la interfaz, Media3 para el reproductor y Room para la biblioteca. [Código de AndroidX](https://github.com/androidx/androidx) · [Media3](https://github.com/androidx/media) |
| Coil y OkHttp | Carátulas y solicitudes HTTP. [Coil](https://github.com/coil-kt/coil) · [OkHttp](https://github.com/square/okhttp) |
| NewPipeExtractor `v0.26.5` | Extracción de información. [Código de la versión](https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.26.5) · [GPL v3](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.26.5/LICENSE) |
| `youtubedl-android` `0.18.1` | Integración de yt-dlp, FFmpeg y aria2 para procesar y descargar audio. Los artefactos Maven de esta versión declaran GPL v3 para sus módulos. [Código de la versión](https://github.com/yausername/youtubedl-android/tree/d725d5c9a18c3a99a13ee0308bf78275dc310760) · [GPL v3](https://github.com/yausername/youtubedl-android/blob/0.18.1/LICENSE) |

El propietario del proyecto generó con IA el logotipo que se incluye como recurso de Android.

## Distribución del APK estándar

En el APK local `arm64-v8a`, los ocho archivos nativos de los módulos `library`, `ffmpeg` y `aria2c` coinciden byte por byte con los AAR de Maven. También coinciden con los identificadores de contenido de Git de la etiqueta `0.18.1` (`d725d5c9a18c3a99a13ee0308bf78275dc310760`). [Versión publicada](https://github.com/yausername/youtubedl-android/releases/tag/0.18.1)

El archivo de yt-dlp del AAR coincide con el recurso empaquetado en el APK. Su metadato `RELEASE_GIT_HEAD` identifica la versión `2025.11.12` y el commit `335653be82d5ef999cfc2879d005397402eebec1`. [Código de yt-dlp de ese commit](https://github.com/yt-dlp/yt-dlp/tree/335653be82d5ef999cfc2879d005397402eebec1)

Las bibliotecas del APK identifican FFmpeg 7.1.1 con `--enable-gpl` y `--enable-version3`. El manual de la etiqueta describe el proceso con Termux, pero omite la revisión de los paquetes y los parches exactos de esa compilación. Para distribuir el APK queda pendiente localizar las fuentes y los pasos de compilación de esos binarios y sus bibliotecas empaquetadas, incluido el entorno de Python y QuickJS y los componentes de aria2. Si no se recuperan, el APK de publicación deberá compilarse con binarios de procedencia documentada. [Guía de FFmpeg](https://ffmpeg.org/legal.html) · [Receta de FFmpeg en 0.18.1](https://github.com/yausername/youtubedl-android/blob/0.18.1/BUILD_FFMPEG.md) · [Consulta de procedencia de 0.18.1](https://github.com/yausername/youtubedl-android/issues/363)

La publicación debe acompañar el APK con los avisos y textos de licencia aplicables y ofrecer a los destinatarios el código fuente correspondiente a esa versión, cuando la licencia lo requiera. Para una descarga por Internet bajo GPL v3, la sección 6(d) describe cómo ofrecer acceso equivalente al código. [GPL v3, secciones 5 y 6](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.26.5/LICENSE)

## Prueba de compilación del conversor

Se compiló FFmpeg 7.1.5 y ffprobe para Android ARM64 (API 26) con el NDK 27.0.12077973. Se verificó la firma del archivo fuente oficial. En un dispositivo Android se convirtió un WAV sintético a MP3 con LAME 3.100, M4A con AAC y Opus con libopus 1.5.2, usando los parámetros de conversión de la app. ffprobe confirmó el códec, la duración, la tasa de bits y la etiqueta de título de los tres archivos. [Fuentes y firma de FFmpeg](https://ffmpeg.org/download.html)

La receta para Git Bash en Windows recibe, en este orden, el directorio de trabajo con los archivos fuente, la ruta al NDK 27.0.12077973 y la ruta a `cmake/3.22.1/bin` dentro del SDK. Verifica los tres SHA-256 y genera los ejecutables en `build-arm64-codecs/`. [Receta de compilación](scripts/probe-ffmpeg-android-arm64.sh) · [FFmpeg 7.1.5](https://ffmpeg.org/releases/ffmpeg-7.1.5.tar.xz) · [Opus 1.5.2](https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz) · [LAME 3.100](https://deb.debian.org/debian/pool/main/l/lame/lame_3.100.orig.tar.gz)

La receta usa rutas relativas en la configuración visible y remapea las rutas locales que añade el compilador. Comprueba que los ejecutables de salida estén libres de la ruta del directorio de compilación.

## Candidato Android ARM64

La compilación opcional con `-PverifiedAndroidRuntime=<directorio>` empaqueta los siguientes componentes. Usa las clases publicadas del wrapper `0.18.1` y su archivo de yt-dlp, que incluye EJS 0.3.1. El descargador de Kotlin y yt-dlp se encargan de las transferencias. FFmpeg y ffprobe se compilan con acceso a archivos locales. Esta compilación omite el módulo opcional aria2. [Fuentes de EJS 0.3.1](https://pypi.org/project/yt-dlp-ejs/0.3.1/#files)

| Componente | Fuente y preparación |
| --- | --- |
| CPython 3.14.10 | Paquete oficial Android aarch64 verificado contra el SHA-256 de python.org. El empaquetador conserva la biblioteca estándar y los módulos nativos de la publicación, y compila el [lanzador](scripts/android-python-launcher.c) con el NDK 27.0.12077973. [Publicación, código fuente y SHA-256](https://www.python.org/downloads/release/python-31410/) · [Receta Android de la versión](https://github.com/python/cpython/tree/v3.14.10/Android) · [Licencia de Python y avisos incorporados](https://github.com/python/cpython/blob/v3.14.10/LICENSE) |
| Dependencias de CPython | La receta oficial fija `bzip2-1.0.8-3`, `libffi-3.4.4-3`, `openssl-3.5.9-0`, `sqlite-3.53.4-0`, `xz-5.4.6-1` y `zstd-1.5.7-2`. Cada etiqueta identifica el código y la preparación de la dependencia correspondiente. [Versiones en android.py](https://github.com/python/cpython/blob/v3.14.10/Android/android.py) · [Recetas etiquetadas de BeeWare](https://github.com/beeware/cpython-android-source-deps/tags) |
| QuickJS-NG 0.12.1 | Compilado para Android desde la etiqueta `v0.12.1`. El archivo obtenido desde GitHub tiene SHA-256 `c2c54b76ca2f52ffea49658a61c5111449cfe0f94e62510bd3bd7a12e2e18884`. [Fuente de la etiqueta](https://github.com/quickjs-ng/quickjs/archive/refs/tags/v0.12.1.tar.gz) · [Licencia MIT](https://github.com/quickjs-ng/quickjs/blob/v0.12.1/LICENSE) |
| Certifi 2026.10.23 | El archivo de raíces TLS y el aviso MPL 2.0 proceden del wheel oficial, verificado por SHA-256. [Wheel y código fuente](https://pypi.org/project/certifi/2026.10.23/#files) |
| FFmpeg 7.1.5, Opus 1.5.2 y LAME 3.100 | Compilados con la receta anterior, con soporte para audio, imágenes JPEG y PNG y metadatos de carátulas. [Avisos de FFmpeg](https://github.com/FFmpeg/FFmpeg/blob/n7.1.5/LICENSE.md) · [Licencia de Opus](https://github.com/xiph/opus/blob/v1.5.2/COPYING) |

Para compilar QuickJS-NG con CMake 3.22.1, usa el generador Ninja y el archivo `build/cmake/android.toolchain.cmake` del NDK 27.0.12077973. Configura `ANDROID_ABI=arm64-v8a`, `ANDROID_PLATFORM=android-26`, `CMAKE_BUILD_TYPE=Release` y `CMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384`. Compila el objetivo `qjs_exe` y ejecuta `llvm-strip` sobre `qjs`.

Ejecuta el [empaquetador](scripts/package-android-arm64-runtime.py) con Python 3.9 o posterior. Toma el paquete de Python para Android, el wheel de Certifi, `clang.exe` del NDK, el ejecutable `qjs`, el directorio de salida de FFmpeg y los AAR originales `library` y `ffmpeg` 0.18.1. Consulta sus argumentos con `--help`. Pasa a Gradle el directorio de salida con ambos AAR y ejecuta `gradlew.bat "-PverifiedAndroidRuntime=<directorio>" assembleOptimized` en Windows.

Para distribuir este candidato, el artefacto y su código correspondiente deben quedar accesibles desde la misma publicación. Incluye las versiones, licencias y fuentes/scripts de compilación indicados en este documento junto con el código de la app.
