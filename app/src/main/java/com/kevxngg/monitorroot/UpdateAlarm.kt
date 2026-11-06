package com.kevxngg.monitorroot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Alarma de unos 5 minutos que no despierta al teléfono: guarda el historial de
 * batería, actualiza el widget, revisa las alertas y, como máximo cada hora,
 * busca actualizaciones.
 */
class UpdateAlarm : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                schedule(app)
                Prefs.init(app)
                if (Prefs.gameMode) Thread { GameMode.start(app) }.start()
                return
            }
        }
        // Las actualizaciones se revisan como máximo una vez por hora en segundo plano
        UpdateWorker.enqueueIfDue(app)
        // Tareas rápidas en cada alarma: historial de batería y widget
        BatteryLog.record(app)
        WidgetUpdater.requestRefresh(app)
        Alerts.enqueue(app)
        schedule(app)
    }

    companion object {
        private const val INTERVAL = 5 * 60 * 1000L

        fun schedule(ctx: Context) {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(
                ctx, 0, Intent(ctx, UpdateAlarm::class.java).setAction("com.kevxngg.monitorroot.CHECK_UPDATE"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            // Sin despertar al teléfono: se ejecuta cuando ya está despierto (ahorra batería)
            am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + INTERVAL, pi)
        }
    }
}

/** Botón "Actualizar ahora" de la notificación: descarga e instala sin abrir la app. */
class InstallUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val url = intent.getStringExtra("apk") ?: return
        val pending = goAsync()
        UpdateWorker.showProgress(app)
        Thread {
            try {
                if (!RootShell.open()) throw IllegalStateException()
                val file = Updater.download(app, url) {}
                val sha = intent.getStringExtra("sha256")
                val size = intent.getLongExtra("size", 0L)
                if (!Updater.install(app, file, sha, size)) throw IllegalStateException()
            } catch (e: Exception) {
                UpdateWorker.showFailed(app, url)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
