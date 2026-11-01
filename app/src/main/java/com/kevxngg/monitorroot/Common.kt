package com.kevxngg.monitorroot

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ===================== PALETA (estilo escritorio) =====================

data class WinPalette(
    val dark: Boolean,
    val bg: Color,
    val header: Color,
    val text: Color,
    val sub: Color,
    val border: Color,
    val select: Color,
    val selectBorder: Color,
    val button: Color,
    val buttonBorder: Color,
    val group: Color,
    val heatLow: Color,
    val heatHigh: Color,
) {
    fun heat(frac: Float): Color = lerp(heatLow, heatHigh, frac.coerceIn(0f, 1f))
}

val LightWin = WinPalette(
    dark = false,
    bg = Color.White,
    header = Color(0xFFF3F3F3),
    text = Color(0xFF1A1A1A),
    sub = Color(0xFF6B6B6B),
    border = Color(0xFFE0E0E0),
    select = Color(0xFFCCE8FF),
    selectBorder = Color(0xFF99D1FF),
    button = Color(0xFFE1E1E1),
    buttonBorder = Color(0xFFADADAD),
    group = Color(0xFF1E3B8C),
    heatLow = Color(0xFFFFF4C4),
    heatHigh = Color(0xFFFFB900),
)

val DarkWin = WinPalette(
    dark = true,
    bg = Color(0xFF1F1F1F),
    header = Color(0xFF2B2B2B),
    text = Color(0xFFF2F2F2),
    sub = Color(0xFFA6A6A6),
    border = Color(0xFF3A3A3A),
    select = Color(0xFF1C4466),
    selectBorder = Color(0xFF3A7BBF),
    button = Color(0xFF333333),
    buttonBorder = Color(0xFF555555),
    group = Color(0xFF8AB4F8),
    heatLow = Color(0xFF2E2A1C),
    heatHigh = Color(0xFF8A6500),
)

val LocalWin = staticCompositionLocalOf { LightWin }

enum class Res(val title: String, val light: Color, val dark: Color) {
    CPU("CPU", Color(0xFF117DBB), Color(0xFF4CC2FF)),
    MEM("Memoria", Color(0xFF8B12AE), Color(0xFFC86DD7)),
    GPU("GPU 0", Color(0xFF0E8A9A), Color(0xFF3FD0D4)),
    WIFI("Wi‑Fi", Color(0xFFA74F01), Color(0xFFE38A3B)),
    TEMP("Temperatura", Color(0xFF4DA60C), Color(0xFF7FD34E));

    fun color(p: WinPalette) = if (p.dark) dark else light
}

// ===================== MODIFICADORES =====================

fun Modifier.bottomBorder(color: Color) = drawBehind {
    drawLine(color, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
}

fun Modifier.topBorder(color: Color) = drawBehind {
    drawLine(color, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1f)
}

// ===================== GRÁFICA =====================

/**
 * Gráfica de línea con cuadrícula y relleno, como las de rendimiento del PC.
 * [values] van de 0 a [max]; el punto más nuevo queda a la derecha.
 */
@Composable
fun TaskGraph(
    values: List<Float>,
    max: Float,
    color: Color,
    modifier: Modifier,
    second: List<Float>? = null,
    grid: Boolean = true,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val step = w / (HISTORY - 1)
        drawRect(color.copy(alpha = 0.04f))
        if (grid) {
            val g = color.copy(alpha = 0.18f)
            for (i in 1 until 10) {
                val y = h * i / 10f
                drawLine(g, Offset(0f, y), Offset(w, y), 1f)
            }
            for (i in 1 until 20) {
                val x = w * i / 20f
                drawLine(g, Offset(x, 0f), Offset(x, h), 1f)
            }
        }
        fun pathFor(v: List<Float>, asFill: Boolean): Path? {
            if (v.size < 2) return null
            val p = Path()
            v.forEachIndexed { i, value ->
                val x = w - (v.size - 1 - i) * step
                val y = h * (1f - (value / max).coerceIn(0f, 1f))
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            if (asFill) {
                p.lineTo(w, h)
                p.lineTo(w - (v.size - 1) * step, h)
                p.close()
            }
            return p
        }
        pathFor(values, true)?.let { drawPath(it, color.copy(alpha = 0.15f)) }
        pathFor(values, false)?.let { drawPath(it, color, style = Stroke(width = 1.5.dp.toPx())) }
        if (second != null) {
            pathFor(second, false)?.let {
                drawPath(
                    it, color,
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f))),
                )
            }
        }
        drawRect(color, style = Stroke(width = 1.dp.toPx()))
    }
}

// ===================== CONTROLES =====================

@Composable
fun WinTabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val win = LocalWin.current
    Row(
        Modifier.fillMaxWidth().background(win.header).bottomBorder(win.border).padding(start = 6.dp, top = 6.dp),
    ) {
        titles.forEachIndexed { i, t ->
            val sel = i == selected
            val base = Modifier.clickable { onSelect(i) }
            Box(
                (if (sel) base.background(win.bg).border(1.dp, win.border) else base)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(t, fontSize = 14.sp, color = if (sel) win.text else win.sub)
            }
        }
    }
}

@Composable
fun WinButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val win = LocalWin.current
    Box(
        Modifier
            .border(1.dp, if (enabled) win.buttonBorder else win.border)
            .background(win.button)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Text(text, fontSize = 13.sp, color = if (enabled) win.text else win.sub)
    }
}

@Composable
fun BottomBar(enabled: Boolean, onEnd: () -> Unit) {
    val win = LocalWin.current
    Row(
        Modifier.fillMaxWidth().background(win.header).topBorder(win.border).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        WinButton("Finalizar tarea", enabled, onEnd)
    }
}

@Composable
fun AppIcon(icon: ImageBitmap?, name: String) {
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(18.dp))
    } else {
        Box(
            Modifier.size(18.dp).background(Color(0xFF7A7A7A), RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.take(1).uppercase(), fontSize = 10.sp, color = Color.White)
        }
    }
}

// ===================== FORMATO =====================

fun formatKb(kb: Long): String = formatBytes(kb * 1024)

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
    b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

fun formatGb(kb: Long): String = "%.1f GB".format(kb / 1048576.0)

fun formatBits(bps: Float): String = when {
    bps >= 1e9f -> "%.1f Gbps".format(bps / 1e9f)
    bps >= 1e6f -> "%.1f Mbps".format(bps / 1e6f)
    bps >= 1e3f -> "%.0f Kbps".format(bps / 1e3f)
    else -> "%.0f bps".format(bps)
}

fun formatGhz(mhz: Int): String = "%.2f GHz".format(mhz / 1000f)

fun formatUptime(sec: Long): String =
    "%d:%02d:%02d:%02d".format(sec / 86400, (sec % 86400) / 3600, (sec % 3600) / 60, sec % 60)

/** Escala "redonda" para la gráfica de red: 100 Kbps, 250 Kbps, 500 Kbps, 1 Mbps... */
fun niceScale(v: Float): Float {
    var mag = 100_000f
    while (true) {
        for (k in floatArrayOf(1f, 2.5f, 5f)) {
            val s = mag * k
            if (s >= v) return s
        }
        mag *= 10f
    }
}

fun signalQuality(rssi: Int): String = when {
    rssi >= -50 -> "Excelente"
    rssi >= -60 -> "Buena"
    rssi >= -70 -> "Regular"
    else -> "Débil"
}

fun band(freqMhz: Int): String = when {
    freqMhz < 3000 -> "2,4 GHz"
    freqMhz < 5925 -> "5 GHz"
    else -> "6 GHz"
}

fun socName(): String =
    if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL != Build.UNKNOWN) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
    else Build.HARDWARE

fun avgGhz(s: Snapshot): String {
    val on = s.cores.map { it.curMhz }.filter { it > 0 }
    return if (on.isEmpty()) "—" else formatGhz(on.average().toInt())
}
