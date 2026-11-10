package com.kevxngg.monitorroot

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.text.DateFormat
import java.util.Date

/** Widget de la pantalla de inicio: CPU, RAM, temperatura de la CPU y batería. */
class MonitorWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        WidgetUpdater.requestRefresh(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) WidgetUpdater.requestRefresh(ctx)
    }

    companion object {
        const val ACTION_REFRESH = "com.kevxngg.monitorroot.WIDGET_REFRESH"
    }
}

/** Lee los datos con root fuera del hilo principal y actualiza los widgets. */
class WidgetWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        Prefs.init(ctx)
        I18n.init(ctx)
        val d = if (RootShell.open()) {
            QuickStats.read(fps = false, ctx = ctx)
            Thread.sleep(600) // la CPU necesita dos lecturas para calcular el uso
            QuickStats.read(fps = false, ctx = ctx)
        } else null
        WidgetUpdater.push(ctx, d?.cpu, d?.ram, d?.cpuTemp, d?.battLevel)
        return Result.success()
    }
}

object WidgetUpdater {
    fun hasWidgets(ctx: Context): Boolean =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, MonitorWidget::class.java)).isNotEmpty()

    fun requestRefresh(ctx: Context) {
        if (!hasWidgets(ctx)) return
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "widget-refresh", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<WidgetWorker>().build(),
        )
    }

    /** Pinta los valores en todos los widgets (también se llama desde la app abierta). */
    fun push(ctx: Context, cpu: Int?, ram: Int?, cpuTemp: Float?, battery: Int?) {
        val manager = AppWidgetManager.getInstance(ctx)
        val ids = manager.getAppWidgetIds(ComponentName(ctx, MonitorWidget::class.java))
        if (ids.isEmpty()) return
        val views = RemoteViews(ctx.packageName, R.layout.widget_monitor)
        fun set(valueId: Int, barId: Int, value: Int?, text: String) {
            views.setTextViewText(valueId, text)
            views.setProgressBar(barId, 100, (value ?: 0).coerceIn(0, 100), false)
        }
        set(R.id.w_cpu_value, R.id.w_cpu_bar, cpu, cpu?.let { "$it%" } ?: "—")
        set(R.id.w_ram_value, R.id.w_ram_bar, ram, ram?.let { "$it%" } ?: "—")
        set(R.id.w_temp_value, R.id.w_temp_bar, cpuTemp?.toInt(), cpuTemp?.let { "%.0f°".format(it) } ?: "—")
        set(R.id.w_batt_value, R.id.w_batt_bar, battery, battery?.let { "$it%" } ?: "—")
        views.setTextViewText(R.id.w_temp_label, tr("CPU °C"))
        views.setTextViewText(R.id.w_batt_label, tr("Batería"))
        views.setTextViewText(R.id.w_time, DateFormat.getTimeInstance(DateFormat.SHORT, I18n.locale()).format(Date()))
        views.setOnClickPendingIntent(
            R.id.w_root,
            PendingIntent.getActivity(ctx, 10, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
        )
        views.setOnClickPendingIntent(
            R.id.w_refresh,
            PendingIntent.getBroadcast(
                ctx, 11, Intent(ctx, MonitorWidget::class.java).setAction(MonitorWidget.ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        manager.updateAppWidget(ids, views)
    }
}
