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
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.BackoffPolicy
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
        return if (runCheck(applicationContext)) Result.success() else Result.retry()
    }

    companion object {
        private const val CHANNEL = "app_updates"
        private const val NOTIF_ID = 1001
        private const val WORK = "update-check"

        /** Busca una versión nueva y, si la hay y no se avisó antes, muestra la notificación. */
        @Synchronized
        fun runCheck(ctx: Context): Boolean {
            Prefs.init(ctx)
            I18n.init(ctx)
            if (!Prefs.autoUpdate) return true
            val result = Updater.fetch(ctx)
            if (result is UpdateResult.Failure) return false
            val info = (result as UpdateResult.Success).info
            if (!Updater.isOffered(ctx, info)) return true
            val sp = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            if (sp.getInt("notified", 0) >= info.build) return true
            if (notify(ctx, info)) sp.edit().putInt("notified", info.build).apply()
            return true
        }

        fun enqueueNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("update-check-now", ExistingWorkPolicy.KEEP, req)
        }

        /** Revisión en segundo plano como máximo una vez por hora (ahorra batería y datos). */
        fun enqueueIfDue(ctx: Context) {
            val sp = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (now - sp.getLong("lastBgCheck", 0L) < BG_INTERVAL_MS) return
            sp.edit().putLong("lastBgCheck", now).apply()
            enqueueNow(ctx)
        }

        fun schedule(ctx: Context) {
            UpdateAlarm.schedule(ctx)
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // UPDATE reemplaza la revisión de 15 minutos de versiones anteriores
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        private const val BG_INTERVAL_MS = 60 * 60 * 1000L

        /**
         * Con root: concede el permiso de notificaciones y quita las restricciones de
         * batería para que las revisiones en segundo plano no se retrasen.
         */
        fun prepare(ctx: Context) {
            val p = ctx.packageName
            val cmds = mutableListOf(
                "cmd deviceidle whitelist +$p",
                "appops set $p RUN_IN_BACKGROUND allow",
                "appops set $p RUN_ANY_IN_BACKGROUND allow",
                "am set-standby-bucket $p active",
            )
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) cmds.add("pm grant $p android.permission.POST_NOTIFICATIONS")
            RootShell.run(cmds.joinToString("; "))
        }

        private fun channel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CHANNEL, tr("Actualizaciones"), NotificationManager.IMPORTANCE_HIGH)
                )
            }
        }

        fun showProgress(ctx: Context) {
            channel(ctx)
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_fg)
                .setContentTitle(tr("Descargando…"))
                .setProgress(0, 0, true)
                .setOngoing(true)
                .build()
            try { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n) } catch (_: SecurityException) {}
        }

        fun showFailed(ctx: Context, url: String) {
            channel(ctx)
            val browser = PendingIntent.getActivity(
                ctx, 2, Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_fg)
                .setContentTitle(tr("Hay una nueva versión"))
                .setContentText(tr("No se pudo instalar automáticamente. Puedes descargarla desde el navegador."))
                .setContentIntent(browser)
                .setAutoCancel(true)
                .build()
            try { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n) } catch (_: SecurityException) {}
        }

        fun cancelNotification(ctx: Context) {
            NotificationManagerCompat.from(ctx).cancel(NOTIF_ID)
        }

        private fun notify(ctx: Context, info: UpdateInfo): Boolean {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return false
            if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return false
            channel(ctx)
            val manager = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
            val install = PendingIntent.getBroadcast(
                ctx, 1, Intent(ctx, InstallUpdateReceiver::class.java).putExtra("apk", info.apkUrl)
                    .putExtra("sha256", info.sha256).putExtra("size", info.sizeBytes),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
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
                .setContentText(tr("Versión %s disponible", info.label))
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    tr("Versión %s disponible", info.label) + if (firstLines.isNotEmpty()) "\n" + firstLines else ""
                ))
                .setContentIntent(open)
                .addAction(0, tr("Actualizar ahora"), install)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
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
