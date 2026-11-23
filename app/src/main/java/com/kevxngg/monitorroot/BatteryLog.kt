package com.kevxngg.monitorroot

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * Battery history: stores level, temperature and charging state every few
 * minutes (also while the app is closed, from the background alarm).
 */
object BatteryLog {
    class Entry(val time: Long, val level: Int, val tempC: Float, val charging: Boolean)

    private const val FILE = "battery_log.csv"
    private const val KEEP_MS = 3L * 24 * 3600 * 1000
    private const val MIN_GAP_MS = 60_000L
    private const val HOUR = 3600_000f

    @Synchronized
    fun record(ctx: Context) {
        try {
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0 || scale <= 0) return
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val entry = Entry(
                time = System.currentTimeMillis(),
                level = level * 100 / scale,
                tempC = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f,
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            )
            val all = read(ctx)
            val last = all.lastOrNull()
            // Entries dated in the future (the clock was changed) are dropped so logging does not stall
            if (last != null && entry.time - last.time in 0 until MIN_GAP_MS) return
            val kept = (all + entry).filter { it.time <= entry.time && entry.time - it.time <= KEEP_MS }
            // Written to a temporary file and then renamed: a kill in the middle never leaves it empty
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.writeText(kept.joinToString("\n") { "${it.time},${it.level},${it.tempC},${if (it.charging) 1 else 0}" })
            tmp.renameTo(File(ctx.filesDir, FILE))
        } catch (_: Exception) {
        }
    }

    fun read(ctx: Context): List<Entry> = try {
        File(ctx.filesDir, FILE).takeIf { it.exists() }?.readLines().orEmpty().mapNotNull(::parseLine)
    } catch (_: Exception) {
        emptyList()
    }

    /** One saved line, or null if it is damaged (a bad line no longer wipes the whole history). */
    fun parseLine(line: String): Entry? {
        val p = line.split(',')
        if (p.size < 4) return null
        val time = p[0].trim().toLongOrNull() ?: return null
        val level = p[1].trim().toIntOrNull() ?: return null
        val temp = p[2].trim().toFloatOrNull() ?: return null
        return Entry(time, level, temp, p[3].trim() == "1")
    }

    /** Percent per hour over the current stretch (discharging or charging), using up to 2 hours. */
    private fun rate(entries: List<Entry>, charging: Boolean): Float? {
        val now = entries.lastOrNull() ?: return null
        if (now.charging != charging) return null
        val seg = entries.takeLastWhile { it.charging == charging && now.time - it.time <= 2 * HOUR }
        if (seg.size < 2) return null
        val hours = (now.time - seg.first().time) / HOUR
        if (hours < 0.25f) return null
        val diff = if (charging) now.level - seg.first().level else seg.first().level - now.level
        return if (diff > 0) diff / hours else null
    }

    fun dischargeRate(entries: List<Entry>) = rate(entries, charging = false)
    fun chargeRate(entries: List<Entry>) = rate(entries, charging = true)

    /** Estimate from the current being drawn right now. */
    class Instant(val charging: Boolean, val pctPerHour: Float, val minutes: Int?)

    /**
     * Rate and remaining time from the instant current and the real capacity
     * (null if the phone does not report them or the current is negligible).
     */
    fun instant(b: BattInfo): Instant? {
        val ma = b.currentMa ?: return null
        val full = b.fullMah ?: b.designMah ?: return null
        if (full <= 0 || kotlin.math.abs(ma) < 20) return null
        val charging = ma > 0
        val level = b.level ?: return null
        // Remaining charge: the counter if it is consistent with the level, otherwise derived from the level
        val left = b.chargeMah?.takeIf { it in 1..(full * 11 / 10) } ?: (full * level / 100)
        val mah = if (charging) (full - left).coerceAtLeast(0) else left
        val minutes = (mah * 60L / kotlin.math.abs(ma)).toInt().takeIf { it in 1..(72 * 60) }
        return Instant(charging, kotlin.math.abs(ma) * 100f / full, minutes)
    }

    /** Estimated minutes until empty (discharging) or full (charging). */
    fun estimateMinutes(ctx: Context, entries: List<Entry>): Pair<Boolean, Int>? {
        val now = entries.lastOrNull() ?: return null
        if (now.charging) {
            if (Build.VERSION.SDK_INT >= 28) {
                val ms = ctx.getSystemService(BatteryManager::class.java)?.computeChargeTimeRemaining() ?: -1
                if (ms > 0) return true to (ms / 60_000).toInt()
            }
            val r = chargeRate(entries) ?: return null
            return true to ((100 - now.level) / r * 60).toInt()
        }
        val r = dischargeRate(entries) ?: return null
        return false to (now.level / r * 60).toInt()
    }
}

fun formatHoursMinutes(minutes: Int): String =
    if (minutes >= 60) tr("%d h %d min", minutes / 60, minutes % 60) else tr("%d min", minutes)

/** Card with the last-24-hours chart and the estimates. */
@Composable
fun BatteryHistoryCard(refreshKey: Long, color: Color, batt: BattInfo? = null) {
    val win = LocalWin.current
    val ctx = LocalContext.current
    val entries by produceState(initialValue = emptyList<BatteryLog.Entry>(), refreshKey / 60) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { BatteryLog.read(ctx) }
    }
    val now = System.currentTimeMillis()
    val day = entries.filter { now - it.time <= 24 * 3600_000L }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Historial de batería"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = win.text)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(tr("Nivel de carga"), fontSize = 11.sp, color = win.sub)
            Text("100%", fontSize = 11.sp, color = win.sub)
        }
        if (day.size < 2) {
            Box(
                Modifier.fillMaxWidth().height(130.dp).clip(corner(4)).border(1.dp, color.copy(alpha = 0.6f)),
            ) {
                Text(
                    tr("Recopilando datos. La gráfica aparecerá en unos minutos."),
                    fontSize = 12.sp, color = win.sub, modifier = Modifier.padding(12.dp),
                )
            }
        } else {
            val fill = if (win.dark) 0.3f else 0.16f
            val chargeColor = win.eco
            Canvas(Modifier.fillMaxWidth().height(130.dp)) {
                val w = size.width
                val h = size.height
                val start = now - 24 * 3600_000L
                fun x(t: Long) = ((t - start).toFloat() / (24 * 3600_000f)) * w
                fun y(l: Int) = h * (1f - l / 100f)
                val g = color.copy(alpha = if (win.dark) 0.22f else 0.14f)
                for (i in 1 until 4) drawLine(g, Offset(0f, h * i / 4f), Offset(w, h * i / 4f), 1f)
                for (i in 1 until 24) drawLine(g, Offset(w * i / 24f, 0f), Offset(w * i / 24f, h), 1f)
                // Fill under the curve, in the normal color
                val area = Path()
                day.forEachIndexed { i, e ->
                    if (i == 0) {
                        area.moveTo(x(e.time), h); area.lineTo(x(e.time), y(e.level))
                    } else {
                        area.lineTo(x(e.time), y(e.level))
                    }
                }
                area.lineTo(x(day.last().time), h)
                area.close()
                drawPath(area, color.copy(alpha = fill))
                // The line: green where the phone was charging
                val stroke = 2.dp.toPx()
                day.zipWithNext().forEach { (a, b) ->
                    drawLine(
                        color = if (a.charging) chargeColor else color,
                        start = Offset(x(a.time), y(a.level)),
                        end = Offset(x(b.time), y(b.level)),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
                drawRect(color.copy(alpha = 0.9f), style = Stroke(width = 1.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(tr("24 horas"), fontSize = 11.sp, color = win.sub)
            Text(tr("Ahora"), fontSize = 11.sp, color = win.sub)
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Box(Modifier.width(16.dp).height(3.dp).clip(corner(2)).background(win.eco))
            Spacer(Modifier.width(6.dp))
            Text(tr("Cargando"), fontSize = 11.sp, color = win.sub)
            Spacer(Modifier.width(16.dp))
            Box(Modifier.width(16.dp).height(3.dp).clip(corner(2)).background(color))
            Spacer(Modifier.width(6.dp))
            Text(tr("Descargando"), fontSize = 11.sp, color = win.sub)
        }

        // Measured over the last minutes; until there is enough history, the instant current is used (≈)
        val instant = batt?.let { BatteryLog.instant(it) }
        val est = BatteryLog.estimateMinutes(ctx, entries)
        val discharge = BatteryLog.dischargeRate(entries)
        val charge = BatteryLog.chargeRate(entries)
        val rows = buildList {
            if (est != null) {
                add((if (est.first) tr("Tiempo hasta carga completa") else tr("Tiempo restante estimado")) to formatHoursMinutes(est.second))
            } else if (instant?.minutes != null) {
                add((if (instant.charging) tr("Tiempo hasta carga completa") else tr("Tiempo restante estimado")) to "≈ " + formatHoursMinutes(instant.minutes))
            }
            discharge?.let { add(tr("Velocidad de descarga") to tr("%.1f %% por hora", it)) }
            charge?.let { add(tr("Velocidad de carga") to tr("%.1f %% por hora", it)) }
            if (discharge == null && charge == null && instant != null) {
                add((if (instant.charging) tr("Velocidad de carga") else tr("Velocidad de descarga")) to "≈ " + tr("%.1f %% por hora", instant.pctPerHour))
            }
            day.maxByOrNull { it.tempC }?.let { add(tr("Temperatura máxima (24 h)") to "%.1f °C".format(it.tempC)) }
        }
        if (rows.isEmpty()) {
            Text(tr("Las estimaciones aparecen tras unos 15 minutos de uso."), fontSize = 12.sp, color = win.sub)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                rows.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text("$k:", fontSize = 12.sp, color = win.sub, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(v, fontSize = 12.sp, color = win.text)
                    }
                }
            }
        }
    }
}
