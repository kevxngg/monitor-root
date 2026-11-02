package com.kevxngg.monitorroot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Revisa GitHub en segundo plano (cada 15 minutos, el mínimo que permite Android)
 * y muestra una notificación cuando hay una versión nueva, aunque la app esté cerrada.
 */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        Prefs.init(ctx)
        I18n.init(ctx)
        if (!Prefs.autoUpdate) return Result.success()
        val info = Updater.check(ctx) ?: return Result.success()
        val sp = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        if (sp.getInt("notified", 0) >= info.build) return Result.success()
        if (notify(ctx, info)) sp.edit().putInt("notified", info.build).apply()
        return Result.success()
    }

    companion object {
        private const val CHANNEL = "updates"
        private const val NOTIF_ID = 1001
        private const val WORK = "update-check"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** Con root se concede el permiso de notificaciones sin preguntar (Android 13+). */
        fun prepare(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                RootShell.run("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS")
            }
        }

        fun cancelNotification(ctx: Context) {
            NotificationManagerCompat.from(ctx).cancel(NOTIF_ID)
        }

        private fun notify(ctx: Context, info: UpdateInfo): Boolean {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return false
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, tr("Actualizaciones"), NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val open = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val firstLines = info.notes.lines()
                .map { it.trim() }
                .filter { it.startsWith("- ") }
                .take(3)
                .joinToString("\n") { "• " + it.drop(2) }
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_fg)
                .setContentTitle(tr("Hay una nueva versión"))
                .setContentText(tr("Build %d disponible", info.build))
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    tr("Build %d disponible", info.build) + if (firstLines.isNotEmpty()) "\n" + firstLines else ""
                ))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            return try {
                NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n)
                true
            } catch (_: SecurityException) {
                false
            }
        }
    }
}
