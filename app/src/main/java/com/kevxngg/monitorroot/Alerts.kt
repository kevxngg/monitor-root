package com.kevxngg.monitorroot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONObject

/**
 * Alertas en segundo plano (se revisan con la alarma de cada ~5 minutos):
 *  - temperatura alta
 *  - una app usando mucha CPU en segundo plano
 *  - batería cargada hasta el límite elegido
 */
object Alerts {
    private const val CHANNEL = "alerts"
    private const val COOLDOWN = 30 * 60_000L
    private val WS = Regex("\\s+")

    fun anyEnabled() = Prefs.alertTemp || Prefs.alertCpu || Prefs.alertCharge

    fun enqueue(ctx: Context) {
        Prefs.init(ctx)
        if (!anyEnabled()) return
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "alerts-check", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<AlertWorker>().build(),
        )
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("alerts", Context.MODE_PRIVATE)

    private fun cooledDown(ctx: Context, key: String): Boolean {
        val last = sp(ctx).getLong(key, 0)
        return System.currentTimeMillis() - last > COOLDOWN
    }

    private fun mark(ctx: Context, key: String) = sp(ctx).edit().putLong(key, System.currentTimeMillis()).apply()

    fun check(ctx: Context) {
        Prefs.init(ctx)
        I18n.init(ctx)
        if (!anyEnabled() || !RootShell.open()) return
        if (Prefs.alertTemp) checkTemp(ctx)
        if (Prefs.alertCharge) checkCharge(ctx)
        if (Prefs.alertCpu) checkCpu(ctx)
    }

    private fun checkTemp(ctx: Context) {
        val d = QuickStats.read(fps = false, ctx = ctx)
        val t = listOfNotNull(d.cpuTemp, d.battTemp).maxOrNull() ?: return
        val limit = Prefs.TEMP_LIMITS.getOrElse(Prefs.alertTempLimit) { 50 }
        if (t >= limit && cooledDown(ctx, "temp")) {
            notify(ctx, 4001, tr("El teléfono está caliente"), tr("Llegó a %d °C. Deja que se enfríe o cierra las apps pesadas.", t.toInt()))
            mark(ctx, "temp")
        }
    }

    private fun checkCharge(ctx: Context) {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val limit = Prefs.CHARGE_LIMITS.getOrElse(Prefs.alertChargeLimit) { 80 }
        val s = sp(ctx)
        if (!charging) {
            s.edit().putBoolean("chargeNotified", false).apply()
            return
        }
        if (level >= limit && !s.getBoolean("chargeNotified", false)) {
            notify(ctx, 4002, tr("Batería al %d %%", level), tr("Puedes desconectar el cargador para cuidar la batería."))
            s.edit().putBoolean("chargeNotified", true).apply()
        }
    }

    /** Compara el tiempo de CPU de cada app con la revisión anterior. */
    private fun checkCpu(ctx: Context) {
        val focus = RootShell.run("dumpsys window | grep -m 1 mCurrentFocus").firstOrNull()
        val fgPkg = focus?.let { Regex("u\\d+ ([\\w.]+)/").find(it)?.groupValues?.get(1) }
        val names = HashMap<Int, String>()
        RootShell.run("ps -A -o PID,NAME").drop(1).forEach { l ->
            val p = l.trim().split(WS, limit = 2)
            if (p.size == 2) p[0].toIntOrNull()?.let { names[it] = p[1].substringBefore(':') }
        }
        val ticks = HashMap<String, Long>()
        RootShell.run("cat /proc/[0-9]*/stat").forEach { l ->
            val open = l.indexOf('(')
            val close = l.lastIndexOf(')')
            if (open < 0 || close < 0 || close + 2 > l.length) return@forEach
            val pid = l.substring(0, open).trim().toIntOrNull() ?: return@forEach
            val pkg = names[pid] ?: return@forEach
            if (!pkg.contains('.')) return@forEach
            val f = l.substring(close + 2).split(' ')
            if (f.size < 13) return@forEach
            ticks[pkg] = (ticks[pkg] ?: 0L) + (f[11].toLongOrNull() ?: 0L) + (f[12].toLongOrNull() ?: 0L)
        }
        val now = System.currentTimeMillis()
        val s = sp(ctx)
        val prevJson = s.getString("cpuTicks", null)
        val snapshot = JSONObject()
        ticks.forEach { (k, v) -> snapshot.put(k, v) }
        snapshot.put("_t", now)
        s.edit().putString("cpuTicks", snapshot.toString()).apply()
        if (prevJson == null) return
        val prev = JSONObject(prevJson)
        val seconds = (now - prev.optLong("_t", now)) / 1000f
        if (seconds < 60f || seconds > 3600f) return
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val limit = Prefs.CPU_LIMITS.getOrElse(Prefs.alertCpuLimit) { 50 }
        ticks.forEach { (pkg, t) ->
            if (pkg == fgPkg || pkg == ctx.packageName || !prev.has(pkg)) return@forEach
            val meta = Apps.meta(pkg) ?: return@forEach
            if (meta.system) return@forEach
            val used = (t - prev.getLong(pkg)).coerceAtLeast(0)
            // Porcentaje del total de la CPU (100 ticks por segundo y núcleo)
            val pct = used * 100f / (seconds * 100f * cores)
            if (pct >= limit && cooledDown(ctx, "cpu:$pkg")) {
                val kill = PendingIntent.getBroadcast(
                    ctx, pkg.hashCode(), Intent(ctx, KillAppReceiver::class.java).putExtra("pkg", pkg),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                notify(
                    ctx, 4100 + (pkg.hashCode() and 0xFF),
                    tr("%s usa mucha CPU", meta.label),
                    tr("Usó un %d %% de la CPU en segundo plano durante los últimos minutos.", pct.toInt()),
                    NotificationCompat.Action(0, tr("Finalizar tarea"), kill),
                )
                mark(ctx, "cpu:$pkg")
            }
        }
    }

    private fun notify(ctx: Context, id: Int, title: String, text: String, action: NotificationCompat.Action? = null) {
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, tr("Alertas"), NotificationManager.IMPORTANCE_HIGH))
        }
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_fg)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(PendingIntent.getActivity(ctx, id, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
        action?.let { b.addAction(it) }
        try {
            NotificationManagerCompat.from(ctx).notify(id, b.build())
        } catch (_: SecurityException) {
        }
    }
}

class AlertWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        try {
            Alerts.check(applicationContext)
        } catch (_: Exception) {
        }
        return Result.success()
    }
}

/** Botón "Finalizar tarea" de la alerta de CPU. */
class KillAppReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pkg = intent.getStringExtra("pkg") ?: return
        val pending = goAsync()
        Thread {
            try {
                if (RootShell.open()) Actions.forceStop(pkg)
                NotificationManagerCompat.from(ctx).cancel(4100 + (pkg.hashCode() and 0xFF))
            } finally {
                pending.finish()
            }
        }.start()
    }
}
