# MOJ Downloader (Android)

Descarga música y video de YouTube, YouTube Music, SoundCloud, Bandcamp, Vimeo, TikTok y +1000 sitios, directo en el celular.

## Descargar
Ve a **[Releases](../../releases/latest)** y descarga `MOJ-Downloader-vX.apk` (el archivo *32bits* es solo para celulares muy antiguos). Al instalar, Android pedirá permitir "instalar apps de origen desconocido".

## Funciones
- 🎵 **Audio**: MP3, M4A, OPUS, FLAC, WAV u original; 128–320 kbps; portada y metadatos incluidos
- 🎬 **Video**: 144p a 4K/8K; MP4 (compatible con todo o máxima calidad), MKV, WEBM; subtítulos opcionales
- 🔍 Ver las calidades disponibles de un video antes de descargar
- Búsqueda por nombre ("Bad Bunny Monaco") o enlaces; varios a la vez
- Playlists completas en su propia carpeta
- **Compartir → MOJ Downloader** desde YouTube, TikTok o el navegador
- Descargas en segundo plano con notificación de progreso
- Omite lo ya descargado; actualiza el motor (yt-dlp) automáticamente

Archivos en: `Descargas/MOJ Downloader/Música` y `Descargas/MOJ Downloader/Videos`.

## Técnico
Kotlin + Jetpack Compose, motor [youtubedl-android](https://github.com/yausername/youtubedl-android) (yt-dlp + Python + FFmpeg + QuickJS).
Cada push a `main` compila el APK con GitHub Actions y publica una versión nueva.

Descarga solo contenido propio, libre o con permiso del titular.

© MOJ Soluciones
