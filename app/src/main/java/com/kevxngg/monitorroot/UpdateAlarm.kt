package com.kevxngg.monitorroot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Solicita una revisión mediante una alarma con intervalo nominal de 5 minutos.
 * Android puede retrasar la alarma y el trabajo, incluso con exención de batería.
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
        // The receiver only schedules work: network requests may exceed its time limit.
        UpdateWorker.enqueueNow(app)
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
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + INTERVAL, pi)
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
                if (!Updater.install(app, file)) throw IllegalStateException()
            } catch (e: Exception) {
                UpdateWorker.showFailed(app, url)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
