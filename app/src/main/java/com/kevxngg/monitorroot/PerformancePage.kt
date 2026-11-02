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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val STRIP_ITEM = 190.dp
private val STRIP_GAP = 4.dp

private class SideData(val values: List<Float>, val max: Float, val line1: String, val line2: String, val second: List<Float>? = null)

private fun netScale(a: List<Float>, b: List<Float>) = niceScale((a + b).maxOrNull() ?: 0f)

private fun sideData(r: Res, s: Snapshot, h: History): SideData = when (r) {
    Res.CPU -> SideData(h["cpu"], 100f, "%.0f%%".format(s.cpuTotal), avgGhz(s))
    Res.MEM -> SideData(h["mem"], 100f, "${formatGb(s.mem.usedKb)} / ${formatGb(s.mem.totalKb)}", "%.0f%%".format(s.mem.usedPct))
    Res.DISK -> SideData(h["dact"], 100f, s.disk?.type ?: "—", "%.0f%%".format(s.disk?.activePct ?: 0f))
    Res.WIFI -> SideData(
        h["wrx"], netScale(h["wrx"], h["wtx"]), s.wifi.ssid ?: "Wi‑Fi",
        "E: ${formatBits(s.wifi.net.txBps * 8f)} R: ${formatBits(s.wifi.net.rxBps * 8f)}", h["wtx"],
    )
    Res.MOBILE -> SideData(
        h["mrx"], netScale(h["mrx"], h["mtx"]), if (s.mobile == null) tr("Desconectado") else tr("Datos móviles"),
        "E: ${formatBits((s.mobile?.txBps ?: 0L) * 8f)} R: ${formatBits((s.mobile?.rxBps ?: 0L) * 8f)}", h["mtx"],
    )
    Res.GPU -> SideData(
        h["gpu"], 100f, s.gpu.model ?: tr(s.gpu.source),
        (s.gpu.load?.let { "$it%" } ?: "—") + (s.gpu.tempC?.let { " (%.0f °C)".format(it) } ?: ""),
    )
    Res.TEMP -> SideData(
        h["temp"], 100f, s.batt.level?.let { "${tr("Batería")} $it%" } ?: tr("Batería"),
        s.mainTempC?.let { "CPU %.0f °C".format(it) } ?: "—",
    )
}

@Composable
fun PerformancePage(s: Snapshot, h: History, onRun: () -> Unit) {
    val win = LocalWin.current
    val clipboard = LocalClipboardManager.current
    var res by remember { mutableStateOf(Res.CPU) }
    var logical by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        PageHeader(tr("Rendimiento")) {
            CmdButton(Icons.Outlined.AddBox, tr("Ejecutar nueva tarea"), onClick = onRun)
            CmdMore(
                listOf(
                    MenuEntry(tr("Gráfico: uso general"), checked = !logical, enabled = res == Res.CPU) { logical = false },
                    MenuEntry(tr("Gráfico: procesadores lógicos"), checked = logical, enabled = res == Res.CPU) { logical = true },
                    MenuEntry(tr("Copiar resumen"), Icons.Outlined.ContentCopy) {
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
                    Column(
                        Modifier.width(230.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(STRIP_GAP),
                    ) {
                        Res.entries.forEach { r -> SideItem(r, r == res, sideData(r, s, h), Modifier.fillMaxWidth()) { res = r } }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) { detail() }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    val strip = rememberScrollState()
                    val density = LocalDensity.current
                    LaunchedEffect(res) {
                        val itemPx = with(density) { (STRIP_ITEM + STRIP_GAP).toPx() }
                        strip.animateScrollTo((res.ordinal * itemPx - itemPx / 3).toInt().coerceAtLeast(0))
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(strip)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(STRIP_GAP),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Res.entries.forEach { r -> SideItem(r, r == res, sideData(r, s, h), Modifier.width(STRIP_ITEM)) { res = r } }
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
            .height(68.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) win.hover else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(22.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) win.accent else Color.Transparent),
        )
        Spacer(Modifier.width(7.dp))
        TaskGraph(d.values, d.max, color, Modifier.size(64.dp, 44.dp), second = d.second, grid = false)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(if (r == Res.TEMP) tr("Temperatura") else tr(r.title), fontSize = 14.sp, color = win.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(d.line1.ifEmpty { " " }, fontSize = 11.sp, color = win.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(d.line2.ifEmpty { " " }, fontSize = 11.sp, color = win.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        Text(tr("% de uso por procesador lógico"), fontSize = 11.sp, color = win.sub)
        val perRow = if (LocalCompact.current) 2 else 4
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            s.cores.chunked(perRow).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { c ->
                        Column(Modifier.weight(1f)) {
                            TaskGraph(h["core${c.index}"], 100f, color, Modifier.fillMaxWidth().height(70.dp))
                            Text(
                                "CPU ${c.index} · " + (if (c.curMhz > 0) formatGhz(c.curMhz) else tr("apagado")),
                                fontSize = 10.sp, color = win.sub,
                            )
                        }
                    }
                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    } else {
        BigGraph(tr("% de uso"), "100%", h["cpu"], 100f, color)
    }
    StatsBlock(
        listOf(
            tr("Uso") to "%.0f%%".format(s.cpuTotal),
            tr("Velocidad") to avgGhz(s),
            tr("Procesos") to s.processes.size.toString(),
            tr("Subprocesos") to s.threads.toString(),
            tr("Identificadores") to s.handles.toString(),
            tr("Tiempo activo") to formatUptime(s.uptimeSec),
        ),
        listOf(
            tr("Velocidad máxima") to (s.cores.maxOfOrNull { it.maxMhz }?.takeIf { it > 0 }?.let(::formatGhz) ?: "—"),
            tr("Núcleos") to s.cores.size.toString(),
            tr("Procesadores lógicos") to Runtime.getRuntime().availableProcessors().toString(),
            tr("Clústeres") to s.cores.map { it.maxMhz }.filter { it > 0 }.distinct().size.toString(),
            tr("Gobernador") to (s.governor ?: "—"),
            tr("Arquitectura") to (Build.SUPPORTED_ABIS.firstOrNull() ?: "—"),
        ),
    )
    if (!logical && s.cores.isNotEmpty()) {
        Text(tr("Frecuencia por núcleo"), fontSize = 12.sp, color = win.sub)
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            s.cores.forEach { c ->
                val frac = if (c.maxMhz > 0) c.curMhz.toFloat() / c.maxMhz else 0f
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("CPU ${c.index}", fontSize = 12.sp, color = win.sub, modifier = Modifier.width(52.dp))
                    Box(Modifier.weight(1f).height(10.dp).background(color.copy(alpha = 0.12f)).border(1.dp, color.copy(alpha = 0.6f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(frac.coerceIn(0f, 1f)).background(color.copy(alpha = 0.6f)))
                    }
                    Text(
                        if (c.curMhz > 0) formatGhz(c.curMhz) else tr("Apagado"),
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
    DetailHeader(tr("Memoria"), formatGb(m.totalKb))
    BigGraph(tr("Uso de memoria"), formatGb(m.totalKb), h["mem"], 100f, color)
    if (m.totalKb > 0) {
        Column {
            Text(tr("Composición de memoria"), fontSize = 11.sp, color = win.sub)
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
            tr("En uso (comprimida)") to formatGb(m.usedKb) + compressed,
            tr("Disponible") to formatGb(m.availKb),
            tr("Confirmada") to "${formatGb(m.committedKb)}/${formatGb(m.commitLimitKb)}",
            tr("En caché") to formatGb(m.cachedKb),
            tr("Kernel (slab)") to formatKb(m.slabKb),
            tr("No recuperable") to formatKb(m.sunreclaimKb),
        ),
        listOf(
            tr("Swap / ZRAM") to tr("%s de %s", formatGb(m.swapTotalKb - m.swapFreeKb), formatGb(m.swapTotalKb)),
            tr("Datos en ZRAM") to formatBytes(m.zramOrigBytes),
            tr("Búferes") to formatKb(m.buffersKb),
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
        DetailHeader(tr("Disco 0"), "")
        Text(tr("No se encontró el almacenamiento interno."), fontSize = 13.sp, color = win.sub)
        return
    }
    DetailHeader(tr("Disco 0"), "${d.type} (${d.name})")
    BigGraph(tr("Tiempo activo"), "100%", h["dact"], 100f, color)
    val scale = niceScale((h["drd"] + h["dwr"]).maxOrNull() ?: 0f, 1_048_576f)
    BigGraph(tr("Velocidad de transferencia del disco"), formatRate(scale), h["drd"], scale, color, second = h["dwr"], height = 90)
    Legend(color, tr("Velocidad de lectura"), tr("Velocidad de escritura"))
    StatsBlock(
        listOf(
            tr("Tiempo activo") to "%.0f%%".format(d.activePct),
            tr("Tiempo de respuesta medio") to "%.1f ms".format(d.respMs),
            tr("Velocidad de lectura") to formatRate(d.readBps.toFloat()),
            tr("Velocidad de escritura") to formatRate(d.writeBps.toFloat()),
        ),
        listOf(
            tr("Capacidad (/data)") to formatBytes(d.totalBytes),
            tr("Disponible") to formatBytes(d.freeBytes),
            tr("Disco del sistema") to tr("Sí"),
            tr("Tipo") to d.type,
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
    DetailHeader("Wi‑Fi", w.ssid ?: if (w.ip != null) tr("Conectado") else tr("Desconectado"))
    BigGraph(tr("Rendimiento"), formatBits(scale), h["wrx"], scale, color, second = h["wtx"])
    Legend(color, tr("Recibir"), tr("Enviar"))
    StatsBlock(
        listOf(
            tr("Enviar") to formatBits(w.net.txBps * 8f),
            tr("Recibir") to formatBits(w.net.rxBps * 8f),
            tr("Intensidad") to (w.rssi?.let { "$it dBm" } ?: "—"),
        ),
        listOf(
            tr("Nombre del adaptador") to "wlan0",
            "SSID" to (w.ssid ?: "—"),
            tr("Tipo de conexión") to (w.freqMhz?.let { band(it) } ?: "—"),
            tr("Calidad de señal") to (w.rssi?.let(::signalQuality) ?: "—"),
            tr("Velocidad del vínculo") to (w.linkMbps?.let { "$it Mbps" } ?: "—"),
            tr("Dirección IPv4") to (w.ip ?: "—"),
            tr("Dirección IPv6") to (w.ip6 ?: "—"),
            tr("Dirección MAC") to (w.mac ?: "—"),
            tr("Recibido desde el encendido") to formatBytes(w.net.rxTotal),
            tr("Enviado desde el encendido") to formatBytes(w.net.txTotal),
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
    DetailHeader(tr("Datos móviles"), if (m == null) tr("Sin interfaz activa") else s.mobileIfaces.joinToString(", "))
    BigGraph(tr("Rendimiento"), formatBits(scale), h["mrx"], scale, color, second = h["mtx"])
    Legend(color, tr("Recibir"), tr("Enviar"))
    StatsBlock(
        listOf(
            tr("Enviar") to formatBits((m?.txBps ?: 0L) * 8f),
            tr("Recibir") to formatBits((m?.rxBps ?: 0L) * 8f),
        ),
        listOf(
            tr("Interfaces") to (s.mobileIfaces.joinToString(", ").ifEmpty { "—" }),
            tr("Recibido desde el encendido") to formatBytes(m?.rxTotal ?: 0L),
            tr("Enviado desde el encendido") to formatBytes(m?.txTotal ?: 0L),
        ),
    )
}

// ===================== GPU =====================

@Composable
private fun GpuDetail(s: Snapshot, h: History) {
    val win = LocalWin.current
    val color = Res.GPU.color(win)
    val g = s.gpu
    DetailHeader("GPU 0", g.model ?: tr(g.source))
    if (g.load != null) {
        BigGraph("3D", "100%", h["gpu"], 100f, color)
    } else {
        Text(tr("No se pudo leer el uso de la GPU en este chip."), fontSize = 13.sp, color = win.sub)
    }
    StatsBlock(
        listOf(
            tr("Uso") to (g.load?.let { "$it%" } ?: "—"),
            tr("Frecuencia") to (g.freqMhz?.let { "$it MHz" } ?: "—"),
            tr("Temperatura") to (g.tempC?.let { "%.0f °C".format(it) + if (g.tempIsSoc) " (SoC)" else "" } ?: "—"),
        ),
        listOfNotNull(
            tr("Familia") to tr(g.source),
            g.model?.let { tr("Modelo") to it },
            tr("Frecuencia máxima") to (g.maxMhz?.let { "$it MHz" } ?: "—"),
            g.memBytes?.let { tr("Memoria de GPU compartida") to formatBytes(it) },
        ),
    )
}

// ===================== BATERÍA Y TEMPERATURA =====================

@Composable
private fun TempDetail(s: Snapshot, h: History) {
    val win = LocalWin.current
    val color = Res.TEMP.color(win)
    val b = s.batt
    DetailHeader(tr("Batería y temperatura"), s.mainTempName ?: "")
    BigGraph(tr("Temperatura del procesador"), "100 °C", h["temp"], 100f, color)
    StatsBlock(
        listOf(
            tr("Máxima") to (s.thermals.firstOrNull()?.let { "%.1f °C".format(it.celsius) } ?: "—"),
            tr("Batería") to (b.tempC?.let { "%.1f °C".format(it) } ?: "—"),
            tr("Carga") to (b.level?.let { "$it%" } ?: "—"),
            tr("Corriente") to (b.currentMa?.let { "$it mA" } ?: "—"),
            tr("Voltaje") to (b.voltageMv?.let { "%.2f V".format(it / 1000f) } ?: "—"),
            tr("Estado") to (b.status?.let { tr(it) } ?: "—"),
        ),
        listOf(tr("Salud de la batería") to (b.health?.let { tr(it) } ?: "—")) +
            s.thermals.take(40).map { it.name to "%.1f °C".format(it.celsius) },
    )
}

private fun summary(r: Res, s: Snapshot): String {
    fun line(k: String, v: String) = "${tr(k)}: $v"
    val lines: List<String> = when (r) {
        Res.CPU -> listOf(
            "CPU: ${socName()}", line("Uso", "%.0f%%".format(s.cpuTotal)), line("Velocidad", avgGhz(s)),
            line("Procesos", s.processes.size.toString()), line("Subprocesos", s.threads.toString()),
            line("Tiempo activo", formatUptime(s.uptimeSec)),
        )
        Res.MEM -> listOf(
            line("Memoria", "${formatGb(s.mem.usedKb)} / ${formatGb(s.mem.totalKb)} (%.0f%%)".format(s.mem.usedPct)),
            line("Disponible", formatGb(s.mem.availKb)), line("En caché", formatGb(s.mem.cachedKb)),
        )
        Res.DISK -> s.disk?.let {
            listOf(
                "${tr("Disco 0")} (${it.type})", line("Tiempo activo", "%.0f%%".format(it.activePct)),
                line("Velocidad de lectura", formatRate(it.readBps.toFloat())),
                line("Velocidad de escritura", formatRate(it.writeBps.toFloat())),
            )
        } ?: listOf(tr("Disco no disponible"))
        Res.WIFI -> listOf(
            "Wi‑Fi ${s.wifi.ssid ?: ""}", line("Enviar", formatBits(s.wifi.net.txBps * 8f)),
            line("Recibir", formatBits(s.wifi.net.rxBps * 8f)), "IPv4: ${s.wifi.ip ?: "—"}",
        )
        Res.MOBILE -> listOf(
            tr("Datos móviles"), line("Enviar", formatBits((s.mobile?.txBps ?: 0L) * 8f)),
            line("Recibir", formatBits((s.mobile?.rxBps ?: 0L) * 8f)),
        )
        Res.GPU -> listOf(
            "GPU 0: ${s.gpu.model ?: tr(s.gpu.source)}", line("Uso", s.gpu.load?.let { "$it%" } ?: "—"),
            line("Frecuencia", s.gpu.freqMhz?.let { "$it MHz" } ?: "—"),
        )
        Res.TEMP -> listOf(line("Batería", "${s.batt.level ?: "—"}% · ${s.batt.tempC ?: "—"} °C")) +
            s.thermals.take(10).map { "${it.name}: %.1f °C".format(it.celsius) }
    }
    return lines.joinToString("\n")
}
