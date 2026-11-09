package com.mojsoluciones.downloader

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Environment
import androidx.core.content.ContextCompat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

enum class Estado { EN_COLA, ANALIZANDO, DESCARGANDO, PROCESANDO, LISTO, AVISOS, ERROR, CANCELADO }

val Estado.activo get() = this == Estado.EN_COLA || this == Estado.ANALIZANDO ||
        this == Estado.DESCARGANDO || this == Estado.PROCESANDO

data class Item(
    val id: String,
    val entrada: String,
    val opciones: Opciones,
    val titulo: String,
    val estado: Estado = Estado.EN_COLA,
    val progreso: Float = 0f,        // 0..1 ; < 0 = indeterminado
    val etiqueta: String = "En cola",
    val detalle: String = "",
)

data class FilaFormato(val columnas: List<String>)

data class InfoCalidades(
    val titulo: String,
    val duracion: String,
    val videos: List<FilaFormato>,
    val audios: List<FilaFormato>,
    val alturas: List<Int>,
)

/** Motor de descargas: cola en segundo plano sobre yt-dlp + FFmpeg + QuickJS (youtubedl-android). */
object Motor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: Context

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    private val _listo = MutableStateFlow(false)
    val listo: StateFlow<Boolean> = _listo.asStateFlow()

    private val _estadoMotor = MutableStateFlow("Preparando motor…")
    val estadoMotor: StateFlow<String> = _estadoMotor.asStateFlow()

    private val _trabajando = MutableStateFlow(false)
    val trabajando: StateFlow<Boolean> = _trabajando.asStateFlow()

    private val cola = Channel<String>(Channel.UNLIMITED)
    private val pendientes = AtomicInteger(0)
    private val cancelados: MutableSet<String> = Collections.synchronizedSet(HashSet())

    val carpetaRaiz: File
        get() = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "MOJ Downloader"
        )

    fun carpeta(modo: Modo) = File(carpetaRaiz, if (modo == Modo.VIDEO) "Videos" else "Música")

    // ------------------------------------------------------------------ inicio
    fun iniciar(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        scope.launch {
            try {
                YoutubeDL.init(app)
                FFmpeg.init(app)
                _listo.value = true
                refrescarVersion()
                // YouTube cambia seguido: actualiza yt-dlp una vez al día en silencio
                val sp = app.getSharedPreferences(Opciones.PREFS, Context.MODE_PRIVATE)
                val ultima = sp.getLong("ultima_actualizacion", 0L)
                if (System.currentTimeMillis() - ultima > 24L * 3600 * 1000) {
                    actualizarMotor(silencioso = true)
                }
            } catch (e: Throwable) {
                _estadoMotor.value = "Error al preparar el motor: ${e.message}"
            }
        }
        scope.launch { procesarCola() }
    }

    private fun refrescarVersion() {
        val v = runCatching { YoutubeDL.versionName(app) }.getOrNull()
        _estadoMotor.value = "Motor listo · yt-dlp ${v ?: "incluido"}"
    }

    suspend fun actualizarMotor(silencioso: Boolean = false): String = withContext(Dispatchers.IO) {
        if (!_listo.value) return@withContext "El motor aún se está preparando"
        if (!silencioso) _estadoMotor.value = "Actualizando yt-dlp…"
        val msg = try {
            val r = YoutubeDL.updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
            app.getSharedPreferences(Opciones.PREFS, Context.MODE_PRIVATE).edit()
                .putLong("ultima_actualizacion", System.currentTimeMillis()).apply()
            if (r == YoutubeDL.UpdateStatus.DONE) "Motor actualizado" else "El motor ya está al día"
        } catch (e: Throwable) {
            "No se pudo actualizar: ${e.message?.take(150)}"
        }
        refrescarVersion()
        msg
    }

    // ------------------------------------------------------------------- cola
    fun agregar(entradas: List<String>, op: Opciones) {
        val nuevos = entradas.map { Item(id = UUID.randomUUID().toString(), entrada = it, opciones = op, titulo = it) }
        if (nuevos.isEmpty()) return
        _items.update { it + nuevos }
        pendientes.addAndGet(nuevos.size)
        _trabajando.value = true
        ContextCompat.startForegroundService(app, Intent(app, DescargaService::class.java))
        nuevos.forEach { cola.trySend(it.id) }
    }

    fun cancelar(id: String) {
        cancelados.add(id)
        YoutubeDL.destroyProcessById(id)
        YoutubeDL.destroyProcessById("info-$id")
        actualizarItem(id) { if (it.estado.activo) it.copy(estado = Estado.CANCELADO, etiqueta = "Cancelado", progreso = 0f) else it }
    }

    fun cancelarTodo() = _items.value.filter { it.estado.activo }.forEach { cancelar(it.id) }

    fun limpiarTerminados() = _items.update { lista -> lista.filter { it.estado.activo } }

    private suspend fun procesarCola() {
        for (id in cola) {
            while (!_listo.value) delay(300)
            try {
                if (id !in cancelados) descargar(id)
            } catch (e: YoutubeDL.CanceledException) {
                actualizarItem(id) { it.copy(estado = Estado.CANCELADO, etiqueta = "Cancelado") }
            } catch (e: Throwable) {
                if (id in cancelados) {
                    actualizarItem(id) { it.copy(estado = Estado.CANCELADO, etiqueta = "Cancelado") }
                } else {
                    actualizarItem(id) {
                        it.copy(estado = Estado.ERROR, etiqueta = "Error", progreso = 0f,
                            detalle = limpiarError(e.message))
                    }
                }
            }
            if (pendientes.decrementAndGet() <= 0) {
                pendientes.set(0)
                _trabajando.value = false
            }
        }
    }

    private fun actualizarItem(id: String, f: (Item) -> Item) =
        _items.update { lista -> lista.map { if (it.id == id) f(it) else it } }

    private fun item(id: String) = _items.value.first { it.id == id }

    // --------------------------------------------------------------- descarga
    private fun descargar(id: String) {
        val it0 = item(id)
        val op = it0.opciones
        val texto = it0.entrada
        val busqueda = !esUrl(texto)
        val objetivo = if (busqueda) "ytsearch1:$texto" else texto
        val video = op.modo == Modo.VIDEO

        // 1) Analizar (rápido, sin descargar)
        actualizarItem(id) { it.copy(estado = Estado.ANALIZANDO, etiqueta = "Analizando…", progreso = -1f) }
        val reqInfo = YoutubeDLRequest(objetivo)
            .addOption("--flat-playlist")
            .addOption("--dump-single-json")
        if (!op.playlist) reqInfo.addOption("--no-playlist")
        val info = parsearJson(YoutubeDL.execute(reqInfo, "info-$id", null).out)
        if (id in cancelados) throw YoutubeDL.CanceledException()

        val entradas = info.optJSONArray("entries")
        val esPlaylist = info.optString("_type") == "playlist" && !busqueda
        val unidad = if (video) "videos" else "canciones"
        var titulo = when {
            busqueda -> {
                val primero = entradas?.optJSONObject(0)
                    ?: throw IllegalStateException("No se encontraron resultados para la búsqueda")
                "🔎 " + primero.optString("title", texto)
            }
            esPlaylist -> "📃 ${info.optString("title", "Playlist")} (${entradas?.length() ?: 0} $unidad)"
            else -> info.optString("title", texto)
        }
        titulo = (if (video) "🎬 " else "🎵 ") + titulo
        val total = if (esPlaylist) (entradas?.length() ?: 1).coerceAtLeast(1) else 1

        // 2) Configurar
        var destino = carpeta(op.modo)
        if (esPlaylist) destino = File(destino, nombreSeguro(info.optString("title", "Playlist")))
        destino.mkdirs()
        val sufijo = if (video) "%(height& - {}p|)s" else ""
        val plantilla = if (esPlaylist) "%(playlist_index)02d - %(title)s$sufijo.%(ext)s" else "%(title)s$sufijo.%(ext)s"
        val temp = File(app.cacheDir, "tmp/$id").apply { mkdirs() }
        val lista = File(app.cacheDir, "lista-$id.txt").apply { delete() }

        val req = YoutubeDLRequest(objetivo)
            .addOption("-P", "home:${destino.absolutePath}")
            .addOption("-P", "temp:${temp.absolutePath}")
            .addOption("-o", plantilla)
            .addOption("--newline")
            .addOption("--progress")
            .addOption("--windows-filenames")
            .addOption("--no-mtime")
            .addOption("--retries", 5)
            .addOption("--fragment-retries", 5)
        req.addCommands(listOf("--print-to-file", "after_move:filepath", lista.absolutePath))
        if (!op.playlist) req.addOption("--no-playlist")
        if (esPlaylist) req.addOption("--ignore-errors")

        val descripcion: String
        var codec: String? = null
        val incrustar: Boolean
        if (video) {
            val h = Catalogo.altura(op.resolucion)
            val (contenedor, prefs) = Catalogo.formatosVideo[op.formatoVideo] ?: ("mp4" to "")
            val orden = listOfNotNull(if (h != null) "res:$h" else "res", "fps", prefs.ifEmpty { null })
            req.addOption("-f", "bv*+ba/b")
                .addOption("-S", orden.joinToString(","))
                .addOption("--merge-output-format", contenedor)
            val ext = contenedor.substringBefore("/")
            if (ext == "mp4" || ext == "mkv") req.addOption("--remux-video", ext)
            if (op.subtitulos) {
                req.addOption("--write-subs")
                    .addOption("--sub-langs", "es.*,en.*,-live_chat")
                    .addOption("--embed-subs")
            }
            incrustar = op.portada && ext in Catalogo.miniaturaVideo
            descripcion = "${op.resolucion} · ${op.formatoVideo}"
        } else {
            val (c, usaBitrate) = Catalogo.formatosAudio[op.formatoAudio] ?: ("mp3" to true)
            codec = c
            req.addOption("-f", "bestaudio/best")
            if (c != null) {
                req.addOption("-x")
                    .addOption("--audio-format", c)
                    .addOption("--audio-quality", if (usaBitrate) "${op.calidad}K" else "0")
            }
            incrustar = op.portada && c in Catalogo.portadaAudio
            descripcion = op.formatoAudio + if (c != null && usaBitrate) " · ${op.calidad} kbps" else ""
        }
        if (op.metadatos) req.addOption("--embed-metadata")
        if (incrustar) req.addOption("--embed-thumbnail").addOption("--convert-thumbnails", "jpg")
        if (op.omitir) {
            val archivo = File(app.filesDir, if (video) "descargados_video.txt" else "descargados_audio.txt")
            req.addOption("--download-archive", archivo.absolutePath)
        }

        actualizarItem(id) {
            it.copy(titulo = titulo, estado = Estado.DESCARGANDO, etiqueta = "Descargando…",
                progreso = -1f, detalle = descripcion)
        }

        // 3) Descargar
        val reItem = Regex("""Downloading item (\d+) of (\d+)""")
        var indice = 1
        var totalPl = total
        var ultimo = 0L
        val etiquetasPP = mapOf(
            "[ExtractAudio]" to "Convirtiendo a ${codec?.uppercase(Locale.ROOT) ?: "audio"}…",
            "[Merger]" to "Uniendo video y audio…",
            "[VideoRemuxer]" to "Ajustando formato…",
            "[EmbedSubtitle]" to "Agregando subtítulos…",
            "[EmbedThumbnail]" to "Agregando portada…",
            "[Metadata]" to "Guardando metadatos…",
        )

        var errorEjecucion: String? = null
        val salidaErr = try {
            YoutubeDL.execute(req, id) { progreso: Float, eta: Long, linea: String ->
            reItem.find(linea)?.let { m ->
                indice = m.groupValues[1].toInt()
                totalPl = m.groupValues[2].toInt().coerceAtLeast(1)
            }
            val pref = if (esPlaylist) "($indice/$totalPl) " else ""
            val pp = etiquetasPP.entries.firstOrNull { linea.startsWith(it.key) }
            val ahora = System.currentTimeMillis()
            when {
                pp != null -> actualizarItem(id) {
                    it.copy(estado = Estado.PROCESANDO, etiqueta = pref + pp.value)
                }
                linea.startsWith("[download]") && progreso > 0f && ahora - ultimo > 250 -> {
                    ultimo = ahora
                    val frac = (progreso / 100f).coerceIn(0f, 1f)
                    val general = if (esPlaylist) ((indice - 1) + frac) / totalPl else frac
                    val etaTxt = if (eta > 0) " · faltan ${formatoTiempo(eta)}" else ""
                    actualizarItem(id) {
                        it.copy(estado = Estado.DESCARGANDO, progreso = general,
                            etiqueta = "$pref${progreso.toInt()}%", detalle = "$descripcion$etaTxt")
                    }
                }
                else -> Unit
            }
            }.err
        } catch (e: YoutubeDLException) {
            if (id in cancelados) throw YoutubeDL.CanceledException()
            errorEjecucion = e.message ?: "Error desconocido"
            errorEjecucion
        }

        // 4) Resultado
        val archivos = lista.takeIf { it.exists() }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()
        lista.delete()
        temp.deleteRecursively()
        if (archivos.isEmpty() && errorEjecucion != null) throw IllegalStateException(errorEjecucion)
        if (archivos.isNotEmpty()) {
            MediaScannerConnection.scanFile(app, archivos.toTypedArray(), null, null)
        }
        val ubicacion = "Descargas/MOJ Downloader/" + destino.relativeTo(carpetaRaiz).path
        val conErrores = errorEjecucion != null || salidaErr.contains("ERROR:")
        actualizarItem(id) {
            when {
                archivos.isEmpty() && !conErrores -> it.copy(estado = Estado.LISTO, progreso = 1f,
                    etiqueta = "✔ Ya descargado", detalle = "Se omitió porque ya estaba en $ubicacion")
                conErrores -> it.copy(estado = Estado.AVISOS, progreso = 1f, etiqueta = "Completado con avisos",
                    detalle = "${archivos.size} archivo(s) en $ubicacion · algunos elementos fallaron")
                else -> it.copy(estado = Estado.LISTO, progreso = 1f, etiqueta = "✔ Listo",
                    detalle = if (archivos.size == 1) "Guardado en $ubicacion"
                    else "${archivos.size} archivos en $ubicacion")
            }
        }
    }

    // --------------------------------------------------- ver calidades
    suspend fun calidades(texto: String): InfoCalidades = withContext(Dispatchers.IO) {
        check(_listo.value) { "El motor aún se está preparando, intenta en unos segundos" }
        val objetivo = if (esUrl(texto)) texto else "ytsearch1:$texto"
        val req = YoutubeDLRequest(objetivo).addOption("--dump-single-json").addOption("--no-playlist")
        var info = parsearJson(YoutubeDL.execute(req, "calidades-${UUID.randomUUID()}", null).out)
        if (info.optString("_type") == "playlist") {
            info = info.optJSONArray("entries")?.optJSONObject(0)
                ?: throw IllegalStateException("No se encontraron resultados")
        }
        val formatos = info.optJSONArray("formats") ?: JSONArray()
        val videos = mutableListOf<JSONObject>()
        val audios = mutableListOf<JSONObject>()
        for (i in 0 until formatos.length()) {
            val f = formatos.optJSONObject(i) ?: continue
            val vcodec = f.optString("vcodec", "none")
            val acodec = f.optString("acodec", "none")
            val alto = f.optInt("height", 0)
            if (vcodec != "none" && vcodec != "null" && alto > 0) videos += f
            else if ((vcodec == "none") && acodec != "none" && acodec != "null") audios += f
        }
        fun codec(c: String) = c.substringBefore(".")
        fun tam(f: JSONObject): String {
            val b = f.optLong("filesize", 0).takeIf { it > 0 } ?: f.optLong("filesize_approx", 0)
            return formatoBytes(b)
        }
        val vistos = HashSet<String>()
        val filasVideo = videos
            .sortedWith(compareByDescending<JSONObject> { it.optInt("height") }
                .thenByDescending { it.optDouble("fps", 0.0).takeIf { d -> !d.isNaN() } ?: 0.0 }
                .thenByDescending { it.optDouble("tbr", 0.0).takeIf { d -> !d.isNaN() } ?: 0.0 })
            .mapNotNull { f ->
                val fps = (f.optDouble("fps", 0.0).takeIf { !it.isNaN() } ?: 0.0).toInt()
                val clave = "${f.optInt("height")}-$fps-${codec(f.optString("vcodec"))}-${f.optString("ext")}"
                if (!vistos.add(clave)) null
                else FilaFormato(listOf("${f.optInt("height")}p", "$fps fps", codec(f.optString("vcodec")),
                    f.optString("ext"), tam(f)))
            }
        val filasAudio = audios
            .sortedByDescending { it.optDouble("abr", 0.0).takeIf { d -> !d.isNaN() } ?: 0.0 }
            .map { f ->
                val abr = f.optDouble("abr", 0.0).takeIf { !it.isNaN() && it > 0 }
                FilaFormato(listOf(abr?.let { "${it.toInt()} kbps" } ?: "?", codec(f.optString("acodec")),
                    f.optString("ext"), tam(f)))
            }
        val dur = aLong(info.optDouble("duration", 0.0))
        InfoCalidades(
            titulo = info.optString("title", texto),
            duracion = if (dur > 0) formatoTiempo(dur) else "—",
            videos = filasVideo,
            audios = filasAudio,
            alturas = videos.map { it.optInt("height") }.distinct().sortedDescending(),
        )
    }

    private fun aLong(d: Double): Long = if (d.isNaN()) 0 else d.toLong()

    // ------------------------------------------------------------ utilidades
    fun esUrl(t: String): Boolean {
        val s = t.trim().lowercase(Locale.ROOT)
        return s.startsWith("http://") || s.startsWith("https://") || s.startsWith("www.") || "://" in s
    }

    /** Extrae el enlace de un texto compartido ("Mira este video: https://youtu.be/xyz"). */
    fun extraerEnlace(texto: String): String =
        Regex("""https?://\S+""").find(texto)?.value ?: texto.trim()

    private fun parsearJson(salida: String): JSONObject {
        val linea = salida.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("{") }
            ?: throw IllegalStateException("No se pudo leer la información del enlace")
        return JSONObject(linea)
    }

    private fun nombreSeguro(s: String): String =
        s.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().trimEnd('.').take(100).ifEmpty { "Playlist" }

    fun formatoTiempo(seg: Long): String {
        val h = seg / 3600
        val m = (seg % 3600) / 60
        val s = seg % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    fun formatoBytes(b: Long): String {
        if (b <= 0) return "—"
        var v = b.toDouble()
        for (u in listOf("B", "KB", "MB", "GB")) {
            if (v < 1024) return String.format(Locale.ROOT, "%.1f %s", v, u)
            v /= 1024
        }
        return String.format(Locale.ROOT, "%.1f TB", v)
    }

    private fun limpiarError(msg: String?): String {
        if (msg.isNullOrBlank()) return "Error desconocido"
        val lineaError = msg.lines().map { it.trim() }.lastOrNull { it.startsWith("ERROR:") } ?: msg.lines().last { it.isNotBlank() }
        return lineaError.removePrefix("ERROR:").trim().take(300)
    }
}
