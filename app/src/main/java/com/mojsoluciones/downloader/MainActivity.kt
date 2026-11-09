package com.mojsoluciones.downloader

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Texto recibido con "Compartir" desde otra app. */
    private val compartido = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Motor.iniciar(this)
        leerIntent(intent)
        setContent {
            TemaMoj {
                Pantalla(compartido)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        leerIntent(intent)
    }

    private fun leerIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { compartido.value = Motor.extraerEnlace(it) }
        }
    }
}

// ------------------------------------------------------------------------ tema
private val Azul = Color(0xFF1F6AA5)

@Composable
fun TemaMoj(content: @Composable () -> Unit) {
    val oscuro = isSystemInDarkTheme()
    val colores = if (oscuro) darkColorScheme(
        primary = Color(0xFF6FB1E8), onPrimary = Color(0xFF00243F),
        primaryContainer = Azul, onPrimaryContainer = Color.White,
        secondaryContainer = Color(0xFF243447), onSecondaryContainer = Color(0xFFD3E4F5),
        background = Color(0xFF101418), surface = Color(0xFF101418),
        surfaceContainer = Color(0xFF1A2027), surfaceContainerHigh = Color(0xFF222A33),
    ) else lightColorScheme(
        primary = Azul, onPrimary = Color.White,
        primaryContainer = Color(0xFFD1E4FF), onPrimaryContainer = Color(0xFF001D36),
        secondaryContainer = Color(0xFFD6E3F1), onSecondaryContainer = Color(0xFF0F1D2A),
    )
    MaterialTheme(colorScheme = colores, content = content)
}

private val ColorListo = Color(0xFF3FB950)
private val ColorProceso = Color(0xFFC9A227)
private val ColorError = Color(0xFFE05555)
private val ColorAviso = Color(0xFFE39B2D)

// -------------------------------------------------------------------- pantalla
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Pantalla(compartido: MutableStateFlow<String?>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var op by remember { mutableStateOf(Opciones.cargar(ctx)) }
    fun cambiar(nueva: Opciones) {
        op = nueva
        nueva.guardar(ctx)
    }

    var texto by remember { mutableStateOf("") }
    val cola by Motor.items.collectAsState()
    val estadoMotor by Motor.estadoMotor.collectAsState()
    val listo by Motor.listo.collectAsState()
    val textoCompartido by compartido.collectAsState()

    var resoluciones by remember { mutableStateOf(Catalogo.resoluciones.keys.toList()) }
    var analizando by remember { mutableStateOf(false) }
    var calidades by remember { mutableStateOf<InfoCalidades?>(null) }
    var actualizando by remember { mutableStateOf(false) }

    // texto compartido desde YouTube, TikTok, navegador…
    LaunchedEffect(textoCompartido) {
        textoCompartido?.let {
            texto = if (texto.isBlank()) it else texto.trimEnd() + "\n" + it
            compartido.value = null
        }
    }

    // permisos: notificaciones (Android 13+) y almacenamiento (Android 10 o menor)
    val permisos = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        val pedir = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT <= 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (pedir.isNotEmpty()) permisos.launch(pedir.toTypedArray())
    }

    fun lineas() = texto.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MOJ Downloader", fontWeight = FontWeight.Bold)
                        Text("Música y video de YouTube y +1000 sitios",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    if (actualizando) {
                        CircularProgressIndicator(Modifier.size(22.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                    } else {
                        IconButton(onClick = {
                            actualizando = true
                            scope.launch {
                                val msg = Motor.actualizarMotor()
                                actualizando = false
                                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                            }
                        }) { Icon(Icons.Default.Refresh, contentDescription = "Actualizar motor") }
                    }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = pad.calculateTopPadding() + 4.dp,
                bottom = pad.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------------------------------------------------- enlaces
            item {
                Tarjeta {
                    OutlinedTextField(
                        value = texto,
                        onValueChange = { texto = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                        label = { Text("Enlaces o búsquedas (uno por línea)") },
                        placeholder = { Text("https://youtu.be/…\no el nombre de una canción") },
                        minLines = 3,
                        maxLines = 8,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val pegado = cm.primaryClip?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()
                            if (!pegado.isNullOrEmpty()) {
                                texto = if (texto.isBlank()) pegado else texto.trimEnd() + "\n" + pegado
                            } else Toast.makeText(ctx, "El portapapeles está vacío", Toast.LENGTH_SHORT).show()
                        }) { Text("📋 Pegar") }
                        OutlinedButton(onClick = { texto = "" }) { Text("🗑 Limpiar") }
                    }
                    Text("Tip: en YouTube toca Compartir → MOJ Downloader",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ---------------------------------------------------- opciones
            item {
                Tarjeta {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = op.modo == Modo.AUDIO,
                            onClick = { cambiar(op.copy(modo = Modo.AUDIO)) },
                            shape = SegmentedButtonDefaults.itemShape(0, 2)
                        ) { Text("🎵 Audio") }
                        SegmentedButton(
                            selected = op.modo == Modo.VIDEO,
                            onClick = { cambiar(op.copy(modo = Modo.VIDEO)) },
                            shape = SegmentedButtonDefaults.itemShape(1, 2)
                        ) { Text("🎬 Video") }
                    }

                    if (op.modo == Modo.AUDIO) {
                        val usaBitrate = Catalogo.formatosAudio[op.formatoAudio]?.second ?: true
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Selector("Formato de audio", op.formatoAudio, Catalogo.formatosAudio.keys.toList(),
                                Modifier.weight(1.4f)) { cambiar(op.copy(formatoAudio = it)) }
                            Selector("Calidad (kbps)", op.calidad, Catalogo.calidades, Modifier.weight(1f),
                                habilitado = usaBitrate) { cambiar(op.copy(calidad = it)) }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Selector("Resolución máxima", op.resolucion, resoluciones, Modifier.weight(1f)) {
                                cambiar(op.copy(resolucion = it))
                            }
                            Selector("Formato de video", op.formatoVideo, Catalogo.formatosVideo.keys.toList(),
                                Modifier.weight(1.2f)) { cambiar(op.copy(formatoVideo = it)) }
                        }
                        Interruptor("Incluir subtítulos (español / inglés)", op.subtitulos) {
                            cambiar(op.copy(subtitulos = it))
                        }
                    }
                    Interruptor("Descargar playlist completa", op.playlist) { cambiar(op.copy(playlist = it)) }
                    Interruptor("Guardar metadatos (título, artista…)", op.metadatos) { cambiar(op.copy(metadatos = it)) }
                    Interruptor("Incrustar portada / miniatura", op.portada) { cambiar(op.copy(portada = it)) }
                    Interruptor("Omitir lo que ya descargué", op.omitir) { cambiar(op.copy(omitir = it)) }

                    Text(
                        "Se guarda en: Descargas/MOJ Downloader/" + if (op.modo == Modo.VIDEO) "Videos" else "Música",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---------------------------------------------------- acciones
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        enabled = !analizando,
                        modifier = Modifier.height(52.dp),
                        onClick = {
                            val primera = lineas().firstOrNull()
                            if (primera == null) {
                                Toast.makeText(ctx, "Pega primero un enlace", Toast.LENGTH_SHORT).show()
                            } else scope.launch {
                                analizando = true
                                try {
                                    val info = Motor.calidades(primera)
                                    if (info.alturas.isNotEmpty()) {
                                        resoluciones = listOf("Mejor disponible") +
                                                info.alturas.map { Catalogo.nombreResolucion(it) }
                                        if (op.resolucion !in resoluciones) {
                                            cambiar(op.copy(resolucion = "Mejor disponible"))
                                        }
                                    }
                                    calidades = info
                                } catch (e: Throwable) {
                                    Toast.makeText(ctx, "No se pudieron leer las calidades: " +
                                            (e.message?.lines()?.lastOrNull { l -> l.isNotBlank() } ?: ""),
                                        Toast.LENGTH_LONG).show()
                                } finally {
                                    analizando = false
                                }
                            }
                        }
                    ) {
                        if (analizando) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("🔍 Calidades")
                    }
                    Button(
                        modifier = Modifier.weight(1f).height(52.dp),
                        onClick = {
                            val l = lineas()
                            if (l.isEmpty()) {
                                Toast.makeText(ctx, "Pega un enlace o escribe el nombre de una canción o video",
                                    Toast.LENGTH_SHORT).show()
                            } else {
                                Motor.agregar(l, op)
                                texto = ""
                            }
                        }
                    ) { Text("⬇  Descargar", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                }
            }

            // ---------------------------------------------------- cola
            if (cola.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Cola de descargas", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f))
                        if (cola.any { it.estado.activo }) {
                            TextButton(onClick = { Motor.cancelarTodo() }) { Text("Cancelar todo", color = ColorError) }
                        }
                        if (cola.any { !it.estado.activo }) {
                            TextButton(onClick = { Motor.limpiarTerminados() }) { Text("Limpiar") }
                        }
                    }
                }
                items(cola.reversed(), key = { it.id }) { FilaItem(it) }
            }

            // ---------------------------------------------------- pie
            item {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!listo) {
                            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(estadoMotor, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("© MOJ Soluciones · Descarga solo contenido propio, libre o con permiso.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    calidades?.let { DialogoCalidades(it) { calidades = null } }
}

// ------------------------------------------------------------------ componentes
@Composable
fun Tarjeta(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
fun Selector(
    etiqueta: String,
    valor: String,
    opciones: List<String>,
    modifier: Modifier = Modifier,
    habilitado: Boolean = true,
    alElegir: (String) -> Unit,
) {
    var abierto by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(etiqueta, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Box {
            OutlinedButton(
                onClick = { abierto = true },
                enabled = habilitado,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 12.dp, end = 4.dp)
            ) {
                Text(valor, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = abierto, onDismissRequest = { abierto = false }) {
                opciones.forEach { o ->
                    DropdownMenuItem(
                        text = { Text(o, fontWeight = if (o == valor) FontWeight.Bold else FontWeight.Normal) },
                        onClick = { abierto = false; alElegir(o) }
                    )
                }
            }
        }
    }
}

@Composable
fun Interruptor(texto: String, valor: Boolean, alCambiar: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(texto, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = valor, onCheckedChange = alCambiar)
    }
}

@Composable
fun FilaItem(item: Item) {
    val color = when (item.estado) {
        Estado.LISTO -> ColorListo
        Estado.AVISOS, Estado.CANCELADO -> ColorAviso
        Estado.ERROR -> ColorError
        Estado.PROCESANDO -> ColorProceso
        Estado.DESCARGANDO, Estado.ANALIZANDO -> MaterialTheme.colorScheme.primary
        Estado.EN_COLA -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.titulo, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (item.estado.activo) {
                    IconButton(onClick = { Motor.cancelar(item.id) }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancelar")
                    }
                } else Spacer(Modifier.height(40.dp))
            }
            Column(Modifier.padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.etiqueta, color = color, style = MaterialTheme.typography.labelLarge)
                when {
                    item.estado == Estado.EN_COLA -> {}
                    item.progreso < 0f && item.estado.activo -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> LinearProgressIndicator(
                        progress = { item.progreso.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = color,
                    )
                }
                if (item.detalle.isNotBlank()) {
                    Text(item.detalle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun DialogoCalidades(info: InfoCalidades, alCerrar: () -> Unit) {
    AlertDialog(
        onDismissRequest = alCerrar,
        confirmButton = { TextButton(onClick = alCerrar) { Text("Cerrar") } },
        title = { Text(info.titulo, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Duración: ${info.duracion}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                if (info.videos.isNotEmpty()) {
                    Text("VIDEO", fontWeight = FontWeight.Bold)
                    Tabla(listOf("Resol.", "FPS", "Códec", "Ext", "Tamaño"), info.videos)
                    Spacer(Modifier.height(10.dp))
                }
                if (info.audios.isNotEmpty()) {
                    Text("AUDIO", fontWeight = FontWeight.Bold)
                    Tabla(listOf("Bitrate", "Códec", "Ext", "Tamaño"), info.audios)
                }
                if (info.videos.isEmpty() && info.audios.isEmpty()) {
                    Text("Esta fuente no informa sus formatos; se descargará la mejor calidad disponible.")
                }
                if (info.alturas.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Las resoluciones de este video ya quedaron en el selector de \"Resolución máxima\".",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    )
}

@Composable
fun Tabla(encabezado: List<String>, filas: List<FilaFormato>) {
    val anchos = listOf(64.dp, 56.dp, 56.dp, 46.dp, 72.dp)
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        (listOf(FilaFormato(encabezado)) + filas).forEachIndexed { i, fila ->
            Row {
                fila.columnas.forEachIndexed { c, v ->
                    Text(
                        v,
                        modifier = Modifier.width(anchos.getOrElse(c) { 64.dp }),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
