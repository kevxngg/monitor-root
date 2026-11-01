package com.kevxngg.monitorroot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PerformanceScreen(s: Snapshot, h: History) {
    val win = LocalWin.current
    var res by remember { mutableStateOf(Res.CPU) }
    Column(Modifier.fillMaxSize()) {
        // Selector superior con mini-gráficas (equivale a la barra lateral)
        Row(
            Modifier
                .fillMaxWidth()
                .background(win.header)
                .bottomBorder(win.border)
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Res.entries.forEach { r -> SideItem(r, r == res, s, h) { res = r } }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (res) {
                Res.CPU -> CpuDetail(s, h)
                Res.MEM -> MemDetail(s, h)
                Res.GPU -> GpuDetail(s, h)
                Res.WIFI -> WifiDetail(s, h)
                Res.TEMP -> TempDetail(s, h)
            }
        }
    }
}

private fun netScale(h: History) = niceScale((h.rx + h.tx).maxOrNull() ?: 0f)

@Composable
private fun SideItem(r: Res, selected: Boolean, s: Snapshot, h: History, onClick: () -> Unit) {
    val win = LocalWin.current
    val color = r.color(win)
    val used = s.memTotalKb - s.memAvailKb
    val memPct = if (s.memTotalKb > 0) used * 100 / s.memTotalKb else 0
    val second: List<Float>? = if (r == Res.WIFI) h.tx else null
    val (values, max, sub) = when (r) {
        Res.CPU -> Triple(h.cpu, 100f, "%.0f%%  %s".format(s.cpuTotal, avgGhz(s)))
        Res.MEM -> Triple(h.mem, 100f, "${formatGb(used)}/${formatGb(s.memTotalKb)} ($memPct%)")
        Res.GPU -> Triple(h.gpu, 100f, "${s.gpuSource}  ${s.gpuLoad?.let { "$it%" } ?: "—"}")
        Res.WIFI -> Triple(h.rx, netScale(h), "E: ${formatBits(s.wifiTxBps * 8f)}  R: ${formatBits(s.wifiRxBps * 8f)}")
        Res.TEMP -> Triple(h.temp, 100f, s.thermals.firstOrNull()?.let { "%.0f °C".format(it.celsius) } ?: "—")
    }
    Row(
        Modifier
            .width(184.dp)
            .background(if (selected) win.select else Color.Transparent)
            .border(1.dp, if (selected) win.selectBorder else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TaskGraph(values, max, color, Modifier.size(58.dp, 40.dp), second = second, grid = false)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(r.title, fontSize = 14.sp, color = win.text)
            Text(sub, fontSize = 11.sp, color = win.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ===================== PIEZAS COMUNES =====================

@Composable
private fun DetailHeader(title: String, subtitle: String) {
    val win = LocalWin.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Light, color = win.text)
        Text(
            subtitle,
            fontSize = 13.sp,
            color = win.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 12.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun BigGraph(topLeft: String, topRight: String, values: List<Float>, max: Float, color: Color, second: List<Float>? = null) {
    val win = LocalWin.current
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(topLeft, fontSize = 11.sp, color = win.sub)
            Text(topRight, fontSize = 11.sp, color = win.sub)
        }
        Spacer(Modifier.height(2.dp))
        TaskGraph(values, max, color, Modifier.fillMaxWidth().height(210.dp), second = second)
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("60 segundos", fontSize = 11.sp, color = win.sub)
            Text("0", fontSize = 11.sp, color = win.sub)
        }
    }
}

@Composable
private fun Stats(vararg items: Pair<String, String>) {
    val win = LocalWin.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.toList().chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, fontSize = 12.sp, color = win.sub)
                        Text(value, fontSize = 20.sp, color = win.text, maxLines = 1)
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth()) {
                Text("$k:", fontSize = 12.sp, color = win.sub, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(v, fontSize = 12.sp, color = win.text, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 13.sp, color = LocalWin.current.sub)
}

// ===================== CPU =====================

@Composable
private fun CpuDetail(s: Snapshot, h: History) {
    val win = LocalWin.current
    val color = Res.CPU.color(win)
    DetailHeader("CPU", socName())
    BigGraph("% de uso", "100%", h.cpu, 100f, color)
    Stats(
        "Uso" to "%.0f%%".format(s.cpuTotal),
        "Velocidad" to avgGhz(s),
        "Núcleos" to s.cores.size.toString(),
        "Procesos" to s.processes.size.toString(),
        "Subprocesos" to s.threads.toString(),
        "Tiempo activo" to formatUptime(s.uptimeSec),
    )
    if (s.cores.isNotEmpty()) {
        SectionTitle("Frecuencia por núcleo")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            s.cores.forEach { c ->
                val frac = if (c.maxMhz > 0) c.curMhz.toFloat() / c.maxMhz else 0f
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Núcleo ${c.index}", fontSize = 12.sp, color = win.sub, modifier = Modifier.width(70.dp))
                    Box(Modifier.weight(1f).height(12.dp).background(color.copy(alpha = 0.12f)).border(1.dp, color.copy(alpha = 0.5f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(frac.coerceIn(0f, 1f)).background(color.copy(alpha = 0.6f)))
                    }
                    Text(
                        if (c.curMhz > 0) formatGhz(c.curMhz) else "Apagado",
                        fontSize = 12.sp, color = win.text, textAlign = TextAlign.End,
                        modifier = Modifier.width(76.dp),
                    )
                }
            }
        }
        KeyValues(listOf("Velocidad máxima" to (s.cores.maxOfOrNull { it.maxMhz }?.takeIf { it > 0 }?.let(::formatGhz) ?: "—")))
    }
}

// ===================== MEMORIA =====================

@Composable
private fun MemDetail(s: Snapshot, h: History) {
    val win = LocalWin.current
    val color = Res.MEM.color(win)
    val total = s.memTotalKb
    val used = total - s.memAvailKb
    val cached = minOf(s.memCachedKb, s.memAvailKb)
    DetailHeader("Memoria", formatGb(total))
    BigGraph("Uso de memoria", formatGb(total), h.mem, 100f, color)
    if (total > 0) {
        Column {
            SectionTitle("Composición de memoria")
            Spacer(Modifier.height(4.dp))
            val usedF = used.toFloat() / total
            val cachedF = cached.toFloat() / total
            val freeF = 1f - usedF - cachedF
            Row(Modifier.fillMaxWidth().height(30.dp).border(1.dp, color)) {
                if (usedF > 0f) Box(Modifier.weight(usedF).fillMaxHeight().background(color.copy(alpha = 0.45f)))
                if (cachedF > 0f) Box(Modifier.weight(cachedF).fillMaxHeight().background(color.copy(alpha = 0.18f)))
                if (freeF > 0f) Box(Modifier.weight(freeF).fillMaxHeight())
            }
        }
    }
    Stats(
        "En uso" to formatGb(used),
        "Disponible" to formatGb(s.memAvailKb),
        "En caché" to formatGb(s.memCachedKb),
        "Total" to formatGb(total),
        "Swap/ZRAM usada" to formatGb(s.swapTotalKb - s.swapFreeKb),
        "Swap/ZRAM total" to formatGb(s.swapTotalKb),
    )
}

// ===================== GPU =====================

@Composable
private fun GpuDetail(s: Snapshot, h: History) {
    val win = LocalWin.current
    val color = Res.GPU.color(win)
    DetailHeader("GPU 0", s.gpuSource)
    if (s.gpuLoad != null) {
        BigGraph("% de uso", "100%", h.gpu, 100f, color)
    } else {
        Text("No se pudo leer el uso de la GPU en este chip.", fontSize = 13.sp, color = win.sub)
    }
    Stats(
        "Uso" to (s.gpuLoad?.let { "$it%" } ?: "—"),
        "Frecuencia" to (s.gpuFreqMhz?.let { "$it MHz" } ?: "—"),
    )
}

// ===================== WI-FI =====================

@Composable
private fun WifiDetail(s: Snapshot, h: History) {
    val win = LocalWin.current
    val color = Res.WIFI.color(win)
    val scale = netScale(h)
    DetailHeader("Wi‑Fi", s.wifiFreqMhz?.let { band(it) } ?: "Desconectado")
    BigGraph("Rendimiento", formatBits(scale), h.rx, scale, color, second = h.tx)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("━ Recibir", fontSize = 12.sp, color = color)
        Text("╍ Enviar", fontSize = 12.sp, color = color)
    }
    Stats(
        "Enviar" to formatBits(s.wifiTxBps * 8f),
        "Recibir" to formatBits(s.wifiRxBps * 8f),
    )
    KeyValues(
        listOf(
            "Intensidad de señal" to (s.wifiRssi?.let { "${signalQuality(it)} ($it dBm)" } ?: "—"),
            "Banda" to (s.wifiFreqMhz?.let { "${band(it)} ($it MHz)" } ?: "—"),
            "Velocidad del vínculo" to (s.wifiLinkMbps?.let { "$it Mbps" } ?: "—"),
            "Dirección IPv4" to (s.wifiIp ?: "—"),
            "Recibido desde el encendido" to formatBytes(s.wifiRxTotal),
            "Enviado desde el encendido" to formatBytes(s.wifiTxTotal),
        )
    )
}

// ===================== TEMPERATURA =====================

@Composable
private fun TempDetail(s: Snapshot, h: History) {
    val win = LocalWin.current
    val color = Res.TEMP.color(win)
    DetailHeader("Temperatura", s.thermals.firstOrNull()?.name ?: "")
    BigGraph("°C del sensor más caliente", "100 °C", h.temp, 100f, color)
    Stats(
        "Máxima" to (s.thermals.firstOrNull()?.let { "%.1f °C".format(it.celsius) } ?: "—"),
        "Batería" to (s.battTempC?.let { "%.1f °C".format(it) } ?: "—"),
        "Carga" to (s.battLevel?.let { "$it%" } ?: "—"),
        "Corriente" to (s.battCurrentMa?.let { "$it mA" } ?: "—"),
    )
    if (s.thermals.isNotEmpty()) {
        SectionTitle("Sensores")
        KeyValues(s.thermals.take(30).map { it.name to "%.1f °C".format(it.celsius) })
    }
}
