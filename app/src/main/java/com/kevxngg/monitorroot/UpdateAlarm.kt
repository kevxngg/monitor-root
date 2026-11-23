package com.kevxngg.monitorroot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Roughly 5-minute alarm that does not wake the phone: saves the battery history,
 * updates the widget, checks alerts and, at most once an hour,
 * looks for updates.
 */
class UpdateAlarm : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                schedule(app)
                // After updating, the "Downloading…" notification of the previous version is removed
                if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) UpdateWorker.cancelNotification(app)
                Prefs.init(app)
                if (Prefs.gameMode) Thread { GameMode.start(app) }.start()
                return
            }
        }
        // The next alarm first: if something below fails, the chain is not broken
        schedule(app)
        // Updates are checked in the background at most once per hour
        UpdateWorker.enqueueIfDue(app)
        // Quick tasks on every alarm: widget, alerts, and the battery history (file work, off the main thread)
        WidgetUpdater.requestRefresh(app)
        Alerts.enqueue(app)
        val pending = goAsync()
        Thread {
            try {
                BatteryLog.record(app)
            } finally {
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
            // Without waking the phone: runs when it is already awake (saves battery)
            am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + INTERVAL, pi)
        }
    }
}

/** The notification's "Actualizar ahora" (Update now) button: downloads and installs without opening the app. */
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
