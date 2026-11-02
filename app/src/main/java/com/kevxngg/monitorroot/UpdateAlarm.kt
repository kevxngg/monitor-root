package com.kevxngg.monitorroot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Revisión de actualizaciones en segundo plano cada 5 minutos con AlarmManager.
 * Es más rápida que WorkManager (mínimo 15 min). Con root, la app se saca de las
 * restricciones de batería para que Android no retrase las alarmas.
 */
class UpdateAlarm : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                schedule(app)
                return
            }
        }
        val pending = goAsync()
        Thread {
            try {
                UpdateWorker.runCheck(app)
            } finally {
                schedule(app)
                pending.finish()
            }
        }.start()
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
