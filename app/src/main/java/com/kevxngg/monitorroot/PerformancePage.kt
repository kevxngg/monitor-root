package com.kevxngg.monitorroot

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private class SideData(val values: List<Float>, val max: Float, val line1: String, val line2: String, val second: List<Float>? = null)

private fun netScale(a: List<Float>, b: List<Float>) = niceScale((a + b).maxOrNull() ?: 0f)

private fun sideData(r: Res, s: Snapshot, h: History): SideData = when (r) {
    Res.CPU -> SideData(h["cpu"], 100f, "%.0f%%  %s".format(s.cpuTotal, avgGhz(s)), "")
    Res.MEM -> SideData(h["mem"], 100f, "${formatGb(s.mem.usedKb)}/${formatGb(s.mem.totalKb)} (%.0f%%)".format(s.mem.usedPct), "")
    Res.DISK -> SideData(h["dact"], 100f, s.disk?.type ?: "—", "%.0f%%".format(s.disk?.activePct ?: 0f))
    Res.WIFI -> SideData(
        h["wrx"], netScale(h["wrx"], h["wtx"]), s.wifi.ssid ?: "Wi‑Fi",
        "E: ${formatBits(s.wifi.net.txBps * 8f)} R: ${formatBits(s.wifi.net.rxBps * 8f)}", h["wtx"],
    )
    Res.MOBILE -> SideData(
        h["mrx"], netScale(h["mrx"], h["mtx"]), if (s.mobile == null) "Desconectado" else "Datos móviles",
        "E: ${formatBits((s.mobile?.txBps ?: 0L) * 8f)} R: ${formatBits((s.mobile?.rxBps ?: 0L) * 8f)}", h["mtx"],
    )
    Res.GPU -> SideData(
        h["gpu"], 100f, s.gpu.model ?: s.gpu.source,
        (s.gpu.load?.let { "$it%" } ?: "—") + (s.gpu.tempC?.let { " (%.0f °C)".format(it) } ?: ""),
    )
    Res.TEMP -> SideData(
        h["temp"], 100f, s.batt.level?.let { "Batería $it%" } ?: "Batería",
        s.thermals.firstOrNull()?.let { "Máx. %.0f °C".format(it.celsius) } ?: "—",
    )
}

@Composable
fun PerformancePage(s: Snapshot, h: History, onRun: () -> Unit) {
    val win = LocalWin.current
    val clipboard = LocalClipboardManager.current
    var res by remember { mutableStateOf(Res.CPU) }
    var logical by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        PageHeader("Rendimiento") {
            CmdButton(Icons.Outlined.AddBox, "Ejecutar nueva tarea", onClick = onRun)
            CmdMore(
                listOf(
                    MenuEntry("Gráfico: uso general", checked = !logical, enabled = res == Res.CPU) { logical = false },
                    MenuEntry("Gráfico: procesadores lógicos", checked = logical, enabled = res == Res.CPU) { logical = true },
                    MenuEntry("Copiar resumen", Icons.Outlined.ContentCopy) {
                        clipboard.setText(AnnotatedString(summary(res, s)))
                    },
                )
            )
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 640.dp
            val detail: @Composable () -> Unit = {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    when (res) {
                        Res.CPU -> CpuDetail(s, h, logical)
                        Res.MEM -> MemDetail(s, h)
                        Res.DISK -> DiskDetail(s, h)
                        Res.WIFI -> WifiDetail(s, h)
                        Res.MOBILE -> MobileDetail(s, h)
                        Res.GPU -> GpuDetail(s, h)
                        Res.TEMP -> TempDetail(s, h)
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.width(230.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(6.dp)) {
                        Res.entries.forEach { r -> SideItem(r, r == res, sideData(r, s, h), Modifier.fillMaxWidth()) { res = r } }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) { detail() }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Res.entries.forEach { r -> SideItem(r, r == res, sideData(r, s, h), Modifier.width(196.dp)) { res = r } }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(win.divider))
                    Box(Modifier.weight(1f)) { detail() }
                }
            }
        }
    }
}

@Composable
private fun SideItem(r: Res, selected: Boolean, d: SideData, modifier: Modifier, onClick: () -> Unit) {
    val win = LocalWin.current
    val color = r.color(win)
    Row(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) win.hover else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TaskGraph(d.values, d.max, color, Modifier.size(64.dp, 44.dp), second = d.second, grid = false)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(r.title, fontSize = 14.sp, color = win.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(d.line1, fontSize = 11.sp, color = win.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (d.line2.isNotEmpty()) Text(d.line2, fontSize = 11.sp, color = win.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ===================== PIEZAS =====================

@Composable
private fun DetailHeader(title: String, subtitle: String) {
    val win = LocalWin.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = win.text)
        Text(
            subtitle, fontSize = 13.sp, color = win.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End, modifier = Modifier.weight(1f).padding(start = 12.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun BigGraph(
    topLeft: String,
    topRight: String,
    values: List<Float>,
    max: Float,
    color: Color,
    second: List<Float>? = null,
    height: Int = 200,
    bottomLabels: Boolean = true,
) {
    val win = LocalWin.current
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(topLeft, fontSize = 11.sp, color = win.sub)
            Text(topRight, fontSize = 11.sp, color = win.sub)
        }
        Spacer(Modifier.height(3.dp))
        TaskGraph(values, max, color, Modifier.fillMaxWidth().height(height.dp), second = second)
        if (bottomLabels) {
            Spacer(Modifier.height(3.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("60 segundos", fontSize = 11.sp, color = win.sub)
                Text("0", fontSize = 11.sp, color = win.sub)
            }
        }
    }
}

/** Estadísticas grandes (izquierda) y datos clave-valor (derecha), como en el PC. */
@Composable
private fun StatsBlock(stats: List<Pair<String, String>>, kv: List<Pair<String, String>>) {
    val wide = !LocalCompact.current
    if (wide) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.weight(1f)) { BigStats(stats) }
            Box(Modifier.weight(1f)) { KeyValues(kv) }
        }
    } else {
        BigStats(stats)
        if (kv.isNotEmpty()) KeyValues(kv)
    }
}

@Composable
private fun BigStats(items: List<Pair<String, String>>) {
    val win = LocalWin.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, fontSize = 12.sp, color = win.sub, maxLines = 1)
                        Text(value, fontSize = 19.sp, color = win.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun KeyValues(items: List<Pair<String, String>>) {
    val win = LocalWin.current
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        items.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth()) {
                Text("$k:", fontSize = 12.sp, color = win.sub, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(v, fontSize = 12.sp, color = win.text, modifier = Modifier.padding(start = 8.dp), maxLines = 1)
            }
        }
    }
}

@Composable
private fun Legend(color: Color, solid: String, dashed: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("━ $solid", fontSize = 12.sp, color = color)
        Text("╍ $dashed", fontSize = 12.sp, color = color)
    }
}

// ===================== CPU =====================

@Composable
private fun CpuDetail(s: Snapshot, h: History, logical: Boolean) {
    val win = LocalWin.current
    val color = Res.CPU.color(win)
    DetailHeader("CPU", socName())
    if (logical && s.cores.isNotEmpty()) {
        Text("% de uso por procesador lógico", fontSize = 11.sp, color = win.sub)
        val perRow = if (LocalCompact.current) 2 else 4
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            s.cores.chunked(perRow).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { c ->
                        Column(Modifier.weight(1f)) {
                            TaskGraph(h["core${c.index}"], 100f, color, Modifier.fillMaxWidth().height(70.dp))
                            Text(
                                "CPU ${c.index} · " + (if (c.curMhz > 0) formatGhz(c.curMhz) else "apagado"),
                                fontSize = 10.sp, color = win.sub,
                            )
                        }
                    }
                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    } else {
        BigGraph("% de uso", "100%", h["cpu"], 100f, color)
    }
    StatsBlock(
        listOf(
            "Uso" to "%.0f%%".format(s.cpuTotal),
            "Velocidad" to avgGhz(s),
            "Procesos" to s.processes.size.toString(),
            "Subprocesos" to s.threads.toString(),
            "Identificadores" to s.handles.toString(),
            "Tiempo activo" to formatUptime(s.uptimeSec),
        ),
        listOf(
            "Velocidad máxima" to (s.cores.maxOfOrNull { it.maxMhz }?.takeIf { it > 0 }?.let(::formatGhz) ?: "—"),
            "Núcleos" to s.cores.size.toString(),
            "Procesadores lógicos" to Runtime.getRuntime().availableProcessors().toString(),
            "Clústeres" to s.cores.map { it.maxMhz }.filter { it > 0 }.distinct().size.toString(),
            "Gobernador" to (s.governor ?: "—"),
            "Arquitectura" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "—"),
        ),
    )
    if (!logical && s.cores.isNotEmpty()) {
        Text("Frecuencia por núcleo", fontSize = 12.sp, color = win.sub)
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            s.cores.forEach { c ->
                val frac = if (c.maxMhz > 0) c.curMhz.toFloat() / c.maxMhz else 0f
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("CPU ${c.index}", fontSize = 12.sp, color = win.sub, modifier = Modifier.width(52.dp))
                    Box(Modifier.weight(1f).height(10.dp).background(color.copy(alpha = 0.12f)).border(1.dp, color.copy(alpha = 0.6f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(frac.coerceIn(0f, 1f)).background(color.copy(alpha = 0.6f)))
                    }
                    Text(
                        if (c.curMhz > 0) formatGhz(c.curMhz) else "Apagado",
                        fontSize = 12.sp, color = win.text, textAlign = TextAlign.End, modifier = Modifier.width(78.dp),
                    )
                }
            }
        }
    }
}

// ===================== MEMORIA =====================

@Composable
private fun MemDetail(s: Snapshot, h: History) {
    val win = LocalWin.current
    val color = Res.MEM.color(win)
    val m = s.mem
    DetailHeader("Memoria", formatGb(m.totalKb))
    BigGraph("Uso de memoria", formatGb(m.totalKb), h["mem"], 100f, color)
    if (m.totalKb > 0) {
        Column {
            Text("Composición de memoria", fontSize = 11.sp, color = win.sub)
            Spacer(Modifier.height(3.dp))
            val cached = minOf(m.cachedKb, m.availKb)
            val usedF = m.usedKb.toFloat() / m.totalKb
            val cachedF = cached.toFloat() / m.totalKb
            val freeF = 1f - usedF - cachedF
            Row(Modifier.fillMaxWidth().height(34.dp).border(1.dp, color)) {
                if (usedF > 0f) Box(Modifier.weight(usedF).fillMaxHeight().background(color.copy(alpha = 0.5f)))
                if (usedF > 0f && cachedF > 0f) Box(Modifier.width(1.dp).fillMaxHeight().background(color))
                if (cachedF > 0f) Box(Modifier.weight(cachedF).fillMaxHeight().background(color.copy(alpha = 0.2f)))
                if (cachedF > 0f && freeF > 0f) Box(Modifier.width(1.dp).fillMaxHeight().background(color))
                if (freeF > 0f) Box(Modifier.weight(freeF).fillMaxHeight())
            }
        }
    }
    val compressed = if (m.zramUsedBytes > 0) " (${formatBytes(m.zramUsedBytes)})" else ""
    StatsBlock(
        listOf(
            "En uso (comprimida)" to formatGb(m.usedKb) + compressed,
            "Disponible" to formatGb(m.availKb),
            "Confirmada" to "${formatGb(m.committedKb)}/${formatGb(m.commitLimitKb)}",
            "En caché" to formatGb(m.cachedKb),
            "Kernel (slab)" to formatKb(m.slabKb),
            "No recuperable" to formatKb(m.sunreclaimKb),
        ),
        listOf(
            "Swap / ZRAM" to "${formatGb(m.swapTotalKb - m.swapFreeKb)} de ${formatGb(m.swapTotalKb)}",
            "Datos en ZRAM" to formatBytes(m.zramOrigBytes),
            "Búferes" to formatKb(m.buffersKb),
        ),
    )
}

// ===================== DISCO =====================

@Composable
private fun DiskDetail(s: Snapshot, h: History) {
    val win = LocalWin.current
    val color = Res.DISK.color(win)
    val d = s.disk
    if (d == null) {
        DetailHeader("Disco 0", "")
        Text("No se encontró el almacenamiento interno.", fontSize = 13.sp, color = win.sub)
        return
    }
    DetailHeader("Disco 0", "${d.type} (${d.name})")
    BigGraph("Tiempo activo", "100%", h["dact"], 100f, color)
    val scale = niceScale((h["drd"] + h["dwr"]).maxOrNull() ?: 0f, 1_048_576f)
    BigGraph("Velocidad de transferencia del disco", formatRate(scale), h["drd"], scale, color, second = h["dwr"], height = 90)
    Legend(color, "Velocidad de lectura", "Velocidad de escritura")
    StatsBlock(
        listOf(
            "Tiempo activo" to "%.0f%%".format(d.activePct),
            "Tiempo de respuesta medio" to "%.1f ms".format(d.respMs),
            "Velocidad de lectura" to formatRate(d.readBps.toFloat()),
            "Velocidad de escritura" to formatRate(d.writeBps.toFloat()),
        ),
        listOf(
            "Capacidad (/data)" to formatBytes(d.totalBytes),
            "Disponible" to formatBytes(d.freeBytes),
            "Disco del sistema" to "Sí",
            "Tipo" to d.type,
        ),
    )
}

// ===================== WI-FI =====================

@Composable
private fun WifiDetail(s: Snapshot, h: History) {
    val win = LocalWin.current
    val color = Res.WIFI.color(win)
    val w = s.wifi
    val scale = netScale(h["wrx"], h["wtx"])
    DetailHeader("Wi‑Fi", w.ssid ?: if (w.ip != null) "Conectado" else "Desconectado")
    BigGraph("Rendimiento", formatBits(scale), h["wrx"], scale, color, second = h["wtx"])
    Legend(color, "Recibir", "Enviar")
    StatsBlock(
        listOf(
            "Enviar" to formatBits(w.net.txBps * 8f),
            "Recibir" to formatBits(w.net.rxBps * 8f),
            "Intensidad" to (w.rssi?.let { "$it dBm" } ?: "—"),
        ),
        listOf(
            "Nombre del adaptador" to "wlan0",
            "SSID" to (w.ssid ?: "—"),
            "Tipo de conexión" to (w.freqMhz?.let { band(it) } ?: "—"),
            "Calidad de señal" to (w.rssi?.let(::signalQuality) ?: "—"),
            "Velocidad del vínculo" to (w.linkMbps?.let { "$it Mbps" } ?: "—"),
            "Dirección IPv4" to (w.ip ?: "—"),
            "Dirección IPv6" to (w.ip6 ?: "—"),
            "Dirección MAC" to (w.mac ?: "—"),
            "Recibido desde el encendido" to formatBytes(w.net.rxTotal),
            "Enviado desde el encendido" to formatBytes(w.net.txTotal),
        ),
    )
}

// ===================== DATOS MÓVILES =====================

@Composable
private fun MobileDetail(s: Snapshot, h: History) {
    val win = LocalWin.current
    val color = Res.MOBILE.color(win)
    val m = s.mobile
    val scale = netScale(h["mrx"], h["mtx"])
    DetailHeader("Datos móviles", if (m == null) "Sin interfaz activa" else s.mobileIfaces.joinToString(", "))
    BigGraph("Rendimiento", formatBits(scale), h["mrx"], scale, color, second = h["mtx"])
    Legend(color, "Recibir", "Enviar")
    StatsBlock(
        listOf(
            "Enviar" to formatBits((m?.txBps ?: 0L) * 8f),
            "Recibir" to formatBits((m?.rxBps ?: 0L) * 8f),
        ),
        listOf(
            "Interfaces" to (s.mobileIfaces.joinToString(", ").ifEmpty { "—" }),
            "Recibido desde el encendido" to formatBytes(m?.rxTotal ?: 0L),
            "Enviado desde el encendido" to formatBytes(m?.txTotal ?: 0L),
        ),
    )
}

// ===================== GPU =====================

@Composable
private fun GpuDetail(s: Snapshot, h: History) {
    val win = LocalWin.current
    val color = Res.GPU.color(win)
    val g = s.gpu
    DetailHeader("GPU 0", g.model ?: g.source)
    if (g.load != null) {
        BigGraph("3D", "100%", h["gpu"], 100f, color)
    } else {
        Text("No se pudo leer el uso de la GPU en este chip.", fontSize = 13.sp, color = win.sub)
    }
    StatsBlock(
        listOf(
            "Uso" to (g.load?.let { "$it%" } ?: "—"),
            "Frecuencia" to (g.freqMhz?.let { "$it MHz" } ?: "—"),
            "Temperatura" to (g.tempC?.let { "%.0f °C".format(it) } ?: "—"),
        ),
        listOfNotNull(
            "Familia" to g.source,
            g.model?.let { "Modelo" to it },
            "Frecuencia máxima" to (g.maxMhz?.let { "$it MHz" } ?: "—"),
            g.memBytes?.let { "Memoria de GPU compartida" to formatBytes(it) },
        ),
    )
}

// ===================== BATERÍA Y TEMPERATURA =====================

@Composable
private fun TempDetail(s: Snapshot, h: History) {
    val win = LocalWin.current
    val color = Res.TEMP.color(win)
    val b = s.batt
    DetailHeader("Batería y temperatura", s.thermals.firstOrNull()?.name ?: "")
    BigGraph("°C del sensor más caliente", "100 °C", h["temp"], 100f, color)
    StatsBlock(
        listOf(
            "Máxima" to (s.thermals.firstOrNull()?.let { "%.1f °C".format(it.celsius) } ?: "—"),
            "Batería" to (b.tempC?.let { "%.1f °C".format(it) } ?: "—"),
            "Carga" to (b.level?.let { "$it%" } ?: "—"),
            "Corriente" to (b.currentMa?.let { "$it mA" } ?: "—"),
            "Voltaje" to (b.voltageMv?.let { "%.2f V".format(it / 1000f) } ?: "—"),
            "Estado" to (b.status ?: "—"),
        ),
        listOf("Salud de la batería" to (b.health ?: "—")) +
            s.thermals.take(40).map { it.name to "%.1f °C".format(it.celsius) },
    )
}

private fun summary(r: Res, s: Snapshot): String = when (r) {
    Res.CPU -> "CPU: ${socName()}\nUso: %.0f%%\nVelocidad: ${avgGhz(s)}\nProcesos: ${s.processes.size}\nSubprocesos: ${s.threads}\nTiempo activo: ${formatUptime(s.uptimeSec)}".format(s.cpuTotal)
    Res.MEM -> "Memoria: ${formatGb(s.mem.usedKb)} / ${formatGb(s.mem.totalKb)} (%.0f%%)\nDisponible: ${formatGb(s.mem.availKb)}\nEn caché: ${formatGb(s.mem.cachedKb)}".format(s.mem.usedPct)
    Res.DISK -> s.disk?.let { "Disco 0 (${it.type})\nTiempo activo: %.0f%%\nLectura: ${formatRate(it.readBps.toFloat())}\nEscritura: ${formatRate(it.writeBps.toFloat())}".format(it.activePct) } ?: "Disco no disponible"
    Res.WIFI -> "Wi‑Fi ${s.wifi.ssid ?: ""}\nEnviar: ${formatBits(s.wifi.net.txBps * 8f)}\nRecibir: ${formatBits(s.wifi.net.rxBps * 8f)}\nIPv4: ${s.wifi.ip ?: "—"}"
    Res.MOBILE -> "Datos móviles\nEnviar: ${formatBits((s.mobile?.txBps ?: 0L) * 8f)}\nRecibir: ${formatBits((s.mobile?.rxBps ?: 0L) * 8f)}"
    Res.GPU -> "GPU 0: ${s.gpu.model ?: s.gpu.source}\nUso: ${s.gpu.load?.let { "$it%" } ?: "—"}\nFrecuencia: ${s.gpu.freqMhz?.let { "$it MHz" } ?: "—"}"
    Res.TEMP -> "Batería: ${s.batt.level ?: "—"}% · ${s.batt.tempC ?: "—"} °C\n" + s.thermals.take(10).joinToString("\n") { "${it.name}: %.1f °C".format(it.celsius) }
}
