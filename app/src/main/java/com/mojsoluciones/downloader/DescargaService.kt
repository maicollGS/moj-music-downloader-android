package com.mojsoluciones.downloader

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Mantiene viva la app mientras hay descargas y muestra el progreso en la barra de notificaciones. */
class DescargaService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ultimaNotificacion = 0L
    private var iniciado = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        crearCanales()
        val tipo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, ID_PROGRESO, construir("Preparando descargas…", "", -1f), tipo)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!iniciado) {
            iniciado = true
            scope.launch {
                combine(Motor.items, Motor.trabajando) { items, trabajando -> items to trabajando }
                    .collect { (items, trabajando) ->
                        if (!trabajando) {
                            terminar(items)
                        } else {
                            val ahora = System.currentTimeMillis()
                            if (ahora - ultimaNotificacion < 700) return@collect
                            ultimaNotificacion = ahora
                            val activo = items.firstOrNull { it.estado.activo && it.estado != Estado.EN_COLA }
                            val enCola = items.count { it.estado == Estado.EN_COLA }
                            val titulo = activo?.titulo ?: "Preparando descargas…"
                            val texto = buildString {
                                append(activo?.etiqueta ?: "")
                                if (enCola > 0) append("  ·  $enCola en cola")
                            }
                            notificar(ID_PROGRESO, construir(titulo, texto, activo?.progreso ?: -1f))
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    private fun terminar(items: List<Item>) {
        val listos = items.count { it.estado == Estado.LISTO || it.estado == Estado.AVISOS }
        val errores = items.count { it.estado == Estado.ERROR }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (listos + errores > 0) {
            val n = NotificationCompat.Builder(this, CANAL_FIN)
                .setSmallIcon(R.drawable.ic_notificacion)
                .setContentTitle("Descargas terminadas")
                .setContentText(buildString {
                    append("$listos lista(s)")
                    if (errores > 0) append(" · $errores con error")
                    append(" · Descargas/MOJ Downloader")
                })
                .setContentIntent(abrirApp())
                .setAutoCancel(true)
                .build()
            notificar(ID_FIN, n)
        }
        stopSelf()
    }

    private fun construir(titulo: String, texto: String, progreso: Float) =
        NotificationCompat.Builder(this, CANAL_PROGRESO)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(abrirApp())
            .setProgress(100, (progreso.coerceIn(0f, 1f) * 100).toInt(), progreso < 0f)
            .build()

    private fun abrirApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun notificar(id: Int, n: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(this).notify(id, n)
    }

    private fun crearCanales() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CANAL_PROGRESO, "Progreso de descargas", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CANAL_FIN, "Descargas terminadas", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CANAL_PROGRESO = "progreso"
        private const val CANAL_FIN = "fin"
        private const val ID_PROGRESO = 1
        private const val ID_FIN = 2
    }
}
