package com.mojsoluciones.downloader

import android.content.Context

enum class Modo { AUDIO, VIDEO }

/** Formatos y calidades disponibles (mismos que la versión de Windows). */
object Catalogo {
    /** nombre visible -> (códec para ffmpeg o null = sin convertir, ¿usa bitrate?) */
    val formatosAudio: LinkedHashMap<String, Pair<String?, Boolean>> = linkedMapOf(
        "MP3" to ("mp3" to true),
        "M4A (AAC)" to ("m4a" to true),
        "OPUS" to ("opus" to true),
        "FLAC (sin pérdida)" to ("flac" to false),
        "WAV (sin pérdida)" to ("wav" to false),
        "Original (sin convertir)" to (null to false),
    )
    val calidades = listOf("320", "256", "192", "128")
    val portadaAudio = setOf("mp3", "m4a", "opus", "flac")

    val resoluciones: LinkedHashMap<String, Int?> = linkedMapOf(
        "Mejor disponible" to null,
        "4320p (8K)" to 4320,
        "2160p (4K)" to 2160,
        "1440p (2K)" to 1440,
        "1080p (Full HD)" to 1080,
        "720p (HD)" to 720,
        "480p" to 480,
        "360p" to 360,
        "240p" to 240,
        "144p" to 144,
    )

    /** nombre visible -> (contenedor de salida, preferencias de códec para -S) */
    val formatosVideo: LinkedHashMap<String, Pair<String, String>> = linkedMapOf(
        "MP4 (compatible con todo)" to ("mp4" to "vcodec:h264,acodec:aac"),
        "MP4 (máxima calidad: VP9/AV1)" to ("mp4" to ""),
        "MKV" to ("mkv" to ""),
        "WEBM" to ("webm/mkv" to "vcodec:vp9,acodec:opus"),
    )
    val miniaturaVideo = setOf("mp4", "mkv")

    /** "1080p (Full HD)" -> 1080 ; "1080p" -> 1080 ; "Mejor disponible" -> null */
    fun altura(resolucion: String): Int? =
        resoluciones[resolucion] ?: resolucion.substringBefore("p").trim().toIntOrNull()

    fun nombreResolucion(h: Int): String =
        resoluciones.entries.firstOrNull { it.value == h }?.key ?: "${h}p"
}

data class Opciones(
    val modo: Modo = Modo.AUDIO,
    val formatoAudio: String = "MP3",
    val calidad: String = "320",
    val resolucion: String = "1080p (Full HD)",
    val formatoVideo: String = "MP4 (compatible con todo)",
    val subtitulos: Boolean = false,
    val playlist: Boolean = true,
    val metadatos: Boolean = true,
    val portada: Boolean = true,
    val omitir: Boolean = true,
) {
    fun guardar(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("modo", modo.name)
            .putString("formatoAudio", formatoAudio)
            .putString("calidad", calidad)
            .putString("resolucion", resolucion)
            .putString("formatoVideo", formatoVideo)
            .putBoolean("subtitulos", subtitulos)
            .putBoolean("playlist", playlist)
            .putBoolean("metadatos", metadatos)
            .putBoolean("portada", portada)
            .putBoolean("omitir", omitir)
            .apply()
    }

    companion object {
        const val PREFS = "moj_opciones"

        fun cargar(ctx: Context): Opciones {
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = Opciones()
            fun valido(v: String?, opciones: Collection<String>, def: String) =
                if (v != null && v in opciones) v else def
            return Opciones(
                modo = runCatching { Modo.valueOf(sp.getString("modo", d.modo.name)!!) }.getOrDefault(d.modo),
                formatoAudio = valido(sp.getString("formatoAudio", null), Catalogo.formatosAudio.keys, d.formatoAudio),
                calidad = valido(sp.getString("calidad", null), Catalogo.calidades, d.calidad),
                resolucion = valido(sp.getString("resolucion", null), Catalogo.resoluciones.keys, d.resolucion),
                formatoVideo = valido(sp.getString("formatoVideo", null), Catalogo.formatosVideo.keys, d.formatoVideo),
                subtitulos = sp.getBoolean("subtitulos", d.subtitulos),
                playlist = sp.getBoolean("playlist", d.playlist),
                metadatos = sp.getBoolean("metadatos", d.metadatos),
                portada = sp.getBoolean("portada", d.portada),
                omitir = sp.getBoolean("omitir", d.omitir),
            )
        }
    }
}
