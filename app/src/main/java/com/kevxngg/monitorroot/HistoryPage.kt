package com.kevxngg.monitorroot

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class HistRow(
    val pkg: String,
    val label: String,
    val icon: ImageBitmap?,
    val fgMs: Long,
    val wifiBytes: Long,
    val mobileBytes: Long,
)

private val PERIODS = listOf("Últimas 24 horas" to 1L, "Últimos 7 días" to 7L, "Últimos 30 días" to 30L)

private fun netByUid(ctx: Context, type: Int, start: Long, end: Long): Map<Int, Long> {
    val out = HashMap<Int, Long>()
    try {
        val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val stats = nsm.querySummary(type, null, start, end)
        val b = NetworkStats.Bucket()
        while (stats.hasNextBucket()) {
            stats.getNextBucket(b)
            out[b.uid] = (out[b.uid] ?: 0L) + b.rxBytes + b.txBytes
        }
        stats.close()
    } catch (_: Exception) {
    }
    return out
}

private fun loadHistory(ctx: Context, days: Long): List<HistRow> {
    // Con root nos damos el permiso de estadísticas de uso
    RootShell.run("appops set ${ctx.packageName} GET_USAGE_STATS allow")
    val end = System.currentTimeMillis()
    // Desde el período elegido, o desde que se eliminó el historial si fue después
    val start = maxOf(end - days * 24 * 3600 * 1000, Prefs.historyResetAt)
    val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val usage = try {
        usm.queryAndAggregateUsageStats(start, end)
    } catch (e: Exception) {
        emptyMap<String, android.app.usage.UsageStats>()
    }
    @Suppress("DEPRECATION")
    val wifi = netByUid(ctx, ConnectivityManager.TYPE_WIFI, start, end)
    @Suppress("DEPRECATION")
    val mobile = netByUid(ctx, ConnectivityManager.TYPE_MOBILE, start, end)
    return Apps.launchablePackages().mapNotNull { pkg ->
        val m = Apps.meta(pkg) ?: return@mapNotNull null
        HistRow(
            pkg = pkg,
            label = m.label,
            icon = m.icon,
            fgMs = usage[pkg]?.totalTimeInForeground ?: 0L,
            wifiBytes = wifi[m.uid] ?: 0L,
            mobileBytes = mobile[m.uid] ?: 0L,
        )
    }
}

@Composable
fun HistoryPage(s: Snapshot, query: String, onRun: () -> Unit) {
    val win = LocalWin.current
    val ctx = LocalContext.current
    var period by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<List<HistRow>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var sortId by remember { mutableStateOf("fg") }
    var asc by remember { mutableStateOf(false) }
    var selPkg by remember { mutableStateOf<String?>(null) }
    var menuPkg by remember { mutableStateOf<String?>(null) }
    val h = rememberScrollState()

    LaunchedEffect(period, reload) {
        data = null
        data = withContext(Dispatchers.IO) { loadHistory(ctx.applicationContext, PERIODS[period].second) }
    }

    // Tiempo de CPU de los procesos que siguen vivos, por app
    val cpuSec = remember(s) {
        s.processes.filter { it.pkg != null }.groupBy { it.pkg!! }.mapValues { (_, l) -> l.sumOf { it.cpuTicks } / 100 }
    }

    val rows = remember(data, cpuSec, query, sortId, asc) {
        val l = (data ?: emptyList()).filter { matches(query, it.label, it.pkg) }
        val sorted = when (sortId) {
            "cpu" -> l.sortedBy { cpuSec[it.pkg] ?: 0L }
            "wifi" -> l.sortedBy { it.wifiBytes }
            "mobile" -> l.sortedBy { it.mobileBytes }
            "fg" -> l.sortedBy { it.fgMs }
            else -> l.sortedBy { it.label.lowercase() }
        }
        if (asc) sorted else sorted.reversed()
    }
    val maxFg = rows.maxOfOrNull { it.fgMs }?.coerceAtLeast(1L) ?: 1L
    val maxCpu = cpuSec.values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val maxWifi = rows.maxOfOrNull { it.wifiBytes }?.coerceAtLeast(1L) ?: 1L
    val maxMobile = rows.maxOfOrNull { it.mobileBytes }?.coerceAtLeast(1L) ?: 1L

    val cols = listOf(
        Col("fg", tr("Tiempo de uso"), 104.dp),
        Col("cpu", tr("Tiempo de CPU"), 104.dp),
        Col("wifi", "Wi‑Fi", 90.dp),
        Col("mobile", tr("Datos móviles"), 108.dp),
    )

    Column(Modifier.fillMaxSize()) {
        PageHeader(tr("Historial de aplicaciones")) {
            CmdButton(Icons.Outlined.AddBox, tr("Ejecutar nueva tarea"), onClick = onRun)
            CmdButton(Icons.Outlined.Refresh, tr("Actualizar")) { reload++ }
            CmdMore(PERIODS.mapIndexed { i, (label, _) -> MenuEntry(tr(label), checked = period == i) { period = i } })
        }
        val sinceMs = maxOf(System.currentTimeMillis() - PERIODS[period].second * 24 * 3600 * 1000, Prefs.historyResetAt)
        val since = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, I18n.locale()).format(Date(sinceMs))
        Text(
            tr("Uso de recursos desde el %s para el usuario actual.", since),
            fontSize = 12.sp, color = win.sub, modifier = Modifier.padding(start = 16.dp, end = 16.dp),
        )
        Text(
            tr("Eliminar historial de uso"),
            fontSize = 12.sp, color = win.accent,
            modifier = Modifier
                .padding(start = 10.dp, bottom = 4.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                .clickable {
                    Prefs.historyResetAt = System.currentTimeMillis()
                    Prefs.save()
                    reload++
                }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val nameW = nameWidth(maxWidth, cols)
            Column(Modifier.fillMaxSize()) {
                TableHeader(tr("Nombre"), nameW, cols, h, sortId, asc) { id ->
                    if (sortId == id) asc = !asc else {
                        sortId = id; asc = id == "name"
                    }
                }
                when {
                    data == null -> EmptyText(tr("Leyendo estadísticas de uso…"))
                    rows.isEmpty() -> EmptyText(tr("No hay datos. Si es la primera vez, toca Actualizar."))
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(rows, key = { it.pkg }) { r ->
                            val cpu = cpuSec[r.pkg] ?: 0L
                            TableRow(
                                nameW, cols,
                                listOf(
                                    Cell(formatDuration(r.fgMs / 1000), heat = r.fgMs.toFloat() / maxFg),
                                    Cell(formatDuration(cpu), heat = cpu.toFloat() / maxCpu),
                                    Cell(formatBytes(r.wifiBytes), heat = r.wifiBytes.toFloat() / maxWifi),
                                    Cell(formatBytes(r.mobileBytes), heat = r.mobileBytes.toFloat() / maxMobile),
                                ),
                                h,
                                selected = selPkg == r.pkg,
                                onClick = { selPkg = r.pkg },
                                onLongClick = { selPkg = r.pkg; menuPkg = r.pkg },
                                menu = {
                                    WinMenu(
                                        menuPkg == r.pkg, { menuPkg = null },
                                        listOf(
                                            MenuEntry(tr("Abrir"), Icons.Outlined.OpenInNew) {
                                                Apps.pm()?.getLaunchIntentForPackage(r.pkg)?.let { ctx.startActivity(it) }
                                            },
                                            MenuEntry(tr("Información de la app"), Icons.Outlined.Info) { openAppInfo(ctx, r.pkg) },
                                            MenuEntry(tr("Buscar en línea"), Icons.Outlined.Search) { searchOnline(ctx, r.label) },
                                        ),
                                    )
                                },
                            ) {
                                AppIcon(r.icon, r.label)
                                Spacer(Modifier.width(8.dp))
                                Text(r.label, fontSize = 13.sp, color = win.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
