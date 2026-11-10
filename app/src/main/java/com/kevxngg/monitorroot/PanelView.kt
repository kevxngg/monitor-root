package com.kevxngg.monitorroot

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Dibuja el logo de Monitor (el mismo de la app): círculo azul con el icono blanco.
 * [alpha] permite que la burbuja minimizada se vea translúcida.
 */
fun drawAppLogo(c: Canvas, ctx: Context, cx: Float, cy: Float, r: Float, alpha: Int = 0xFF) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.style = Paint.Style.FILL
    p.color = (alpha shl 24) or 0x117DBB
    c.drawCircle(cx, cy, r, p)
    // ic_fg es el primer plano del icono adaptativo: el símbolo ocupa ~55 % del lienzo
    val icon = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.ic_fg) ?: return
    icon.setBounds((cx - r).toInt(), (cy - r).toInt(), (cx + r).toInt(), (cy + r).toInt())
    icon.alpha = alpha
    icon.draw(c)
}

/**
 * Dibuja el monitor flotante en sus cuatro estilos (completo, compacto, gamer, retro)
 * con Canvas nativo, para que funcione como ventana flotante sin Compose.
 * Reutiliza los datos de QuickStats.Data y mantiene un pequeño historial para las gráficas.
 */
class PanelView(ctx: Context) : View(ctx) {
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * density * scale

    var scale = 1f
    var style = 0
    var bgAlpha = 0xD8
    var data: QuickStats.Data? = null
        set(value) { field = value; pushHistory(value); invalidate() }

    // Historial para las mini-gráficas
    private val cpuHist = ArrayDeque<Float>()
    private val ramHist = ArrayDeque<Float>()
    private val netHist = ArrayDeque<Float>()
    private val fpsHist = ArrayDeque<Float>()
    private val gpuHist = ArrayDeque<Float>()
    private val HISTN = 48

    private fun pushHistory(d: QuickStats.Data?) {
        d ?: return
        fun push(q: ArrayDeque<Float>, v: Float?) { if (v != null) { q.addLast(v); while (q.size > HISTN) q.removeFirst() } }
        push(cpuHist, d.cpu?.toFloat())
        push(ramHist, d.ram?.toFloat())
        push(gpuHist, d.gpu?.toFloat())
        push(fpsHist, d.fps?.toFloat())
        push(netHist, d.netDownKbs?.let { min(it, 2000f) })
    }

    // Paleta
    private val cBlue = 0xFF3FA9FF.toInt()
    private val cPurple = 0xFF9B5CFF.toInt()
    private val cGreen = 0xFF36D399.toInt()
    private val cOrange = 0xFFFF9F43.toInt()
    private val cText = 0xFFFFFFFF.toInt()
    private val cSub = 0xFF9AA4B2.toInt()
    private val cTrack = 0x33FFFFFF
    private val cGreenFps = 0xFF36D399.toInt()

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cText }
    private val mono = Typeface.MONOSPACE

    private fun pct(v: Int?) = v?.let { "$it%" } ?: "—"
    private fun load(v: Int?) = when { v == null -> cSub; v < 60 -> cGreen; v < 85 -> cOrange; else -> 0xFFFF5A5A.toInt() }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w: Float; val h: Float
        when (style) {
            1 -> { w = dp(330f); h = dp(92f) }      // compacto (horizontal)
            2 -> { w = dp(360f); h = dp(150f) }     // gamer
            3 -> { w = dp(360f); h = dp(140f) }     // retro
            else -> { w = dp(340f); h = dp(300f) }  // completo
        }
        setMeasuredDimension(w.toInt(), h.toInt())
    }

    override fun onDraw(c: Canvas) {
        when (style) {
            1 -> drawCompact(c)
            2 -> drawGamer(c)
            3 -> drawRetro(c)
            else -> drawFull(c)
        }
    }

    // ---------- utilidades de dibujo ----------
    private fun roundBg(c: Canvas, color: Int, radius: Float, strokeColor: Int? = null) {
        p.style = Paint.Style.FILL
        p.color = (bgAlpha shl 24) or (color and 0xFFFFFF)
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        c.drawRoundRect(r, radius, radius, p)
        if (strokeColor != null) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = dp(1f)
            p.color = strokeColor
            val inset = dp(0.5f)
            c.drawRoundRect(RectF(inset, inset, width - inset, height - inset), radius, radius, p)
        }
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false, right: Boolean = false, center: Boolean = false, face: Typeface? = null) {
        textP.textSize = dp(size)
        textP.color = color
        textP.typeface = if (bold) Typeface.create(face ?: Typeface.DEFAULT, Typeface.BOLD) else (face ?: Typeface.DEFAULT)
        textP.textAlign = when { right -> Paint.Align.RIGHT; center -> Paint.Align.CENTER; else -> Paint.Align.LEFT }
        c.drawText(s, x, y, textP)
    }

    /** Anillo tipo medidor (arco de 270°) con valor en el centro. */
    private fun gauge(c: Canvas, cx: Float, cy: Float, radius: Float, frac: Float, color: Int, center: String, unit: String?, label: String) {
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(6f)
        p.strokeCap = Paint.Cap.ROUND
        p.color = cTrack
        c.drawArc(rect, 135f, 270f, false, p)
        p.color = color
        c.drawArc(rect, 135f, 270f * frac.coerceIn(0f, 1f), false, p)
        // etiqueta arriba
        text(c, label, cx, cy - radius - dp(8f), 11f, cSub, center = true)
        // valor central
        text(c, center, cx, cy + dp(5f), 17f, cText, bold = true, center = true)
        if (unit != null) text(c, unit, cx, cy + dp(20f), 9f, cSub, center = true)
    }

    /** Mini gráfica de área. */
    private fun spark(c: Canvas, left: Float, top: Float, right: Float, bottom: Float, hist: ArrayDeque<Float>, color: Int, maxV: Float = 100f) {
        if (hist.size < 2) return
        val w = right - left; val h = bottom - top
        val n = hist.size
        val stepX = w / (HISTN - 1)
        val path = Path(); val fill = Path()
        hist.forEachIndexed { i, v ->
            val x = right - (n - 1 - i) * stepX
            val y = bottom - (v / maxV).coerceIn(0f, 1f) * h
            if (i == 0) { path.moveTo(x, y); fill.moveTo(x, bottom); fill.lineTo(x, y) }
            else { path.lineTo(x, y); fill.lineTo(x, y) }
        }
        fill.lineTo(right, bottom); fill.close()
        p.style = Paint.Style.FILL; p.color = (color and 0xFFFFFF) or 0x40000000; c.drawPath(fill, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1.4f); p.color = color; c.drawPath(path, p)
    }

    /** Porcentaje de almacenamiento usado, o null si no se conoce el total (evita dividir entre 0). */
    private fun stPct(d: QuickStats.Data?): Long? =
        d?.stTotalKb?.takeIf { it > 0 }?.let { (d.stUsedKb ?: 0) * 100 / it }

    private fun gb(kb: Long?) = kb?.let { "%.1f".format(it / 1048576.0) } ?: "—"

    // ---------- Estilo 0: panel completo (Windows 11) ----------
    private fun drawFull(c: Canvas) {
        roundBg(c, 0x0E1420, dp(18f), 0x553FA9FF)
        val d = data
        val pad = dp(16f)
        // cabecera
        drawAppLogo(c, context, pad + dp(10f), pad + dp(9f), dp(10f))
        text(c, "Monitor", pad + dp(26f), pad + dp(14f), 15f, cText, bold = true)
        p.style = Paint.Style.FILL; p.color = cGreen
        c.drawCircle(width - pad - dp(96f), pad + dp(9f), dp(3.5f), p)
        text(c, "Live", width - pad - dp(86f), pad + dp(13f), 11f, cGreen)
        // cuatro medidores
        val gy = pad + dp(58f); val gr = dp(26f)
        val cols = 4; val gw = (width - pad * 2) / cols
        val vals = listOf(
            Quad(cBlue, pct(d?.cpu), d?.cpuMhz?.let { "%.2f GHz".format(it / 1000f) } ?: "", "CPU", (d?.cpu ?: 0) / 100f),
            Quad(cPurple, pct(d?.gpu), d?.gpuMhz?.let { "$it MHz" } ?: "", "GPU", (d?.gpu ?: 0) / 100f),
            Quad(cGreen, pct(d?.ram), "${gb(d?.ramUsedKb)}/${gb(d?.ramTotalKb)} GB", "RAM", (d?.ram ?: 0) / 100f),
            Quad(cOrange, d?.cpuTemp?.let { "%.0f".format(it) } ?: "—", "CPU", "TEMP", ((d?.cpuTemp ?: 0f) / 100f)),
        )
        vals.forEachIndexed { i, q ->
            val cx = pad + gw * i + gw / 2
            gauge(c, cx, gy, gr, q.frac, q.color, q.center, if (i == 3) "°C" else null, q.label)
            text(c, q.sub, cx, gy + gr + dp(18f), 9f, cSub, center = true)
        }
        // dos gráficas: CPU y RAM
        val cardTop = gy + gr + dp(30f)
        val cardH = dp(66f); val gap = dp(8f)
        val cardW = (width - pad * 2 - gap) / 2
        drawMiniCard(c, pad, cardTop, pad + cardW, cardTop + cardH, "CPU", pct(d?.cpu), cpuHist, cBlue)
        drawMiniCard(c, pad + cardW + gap, cardTop, width - pad, cardTop + cardH, "RAM", pct(d?.ram), ramHist, cGreen)
        // fila inferior: almacenamiento, red, fps
        val rTop = cardTop + cardH + gap
        val rH = dp(60f); val rw = (width - pad * 2 - gap * 2) / 3
        drawInfoCard(c, pad, rTop, pad + rw, rTop + rH, "Almacen.", stPct(d)?.let { "$it%" } ?: "—",
            d?.let { "${gb(it.stUsedKb)}/${gb(it.stTotalKb)} GB" } ?: "", (stPct(d) ?: 0L) / 100f)
        drawNetCard(c, pad + rw + gap, rTop, pad + rw * 2 + gap, rTop + rH, d)
        drawFpsCard(c, pad + rw * 2 + gap * 2, rTop, width - pad, rTop + rH, d)
    }

    private class Quad(val color: Int, val center: String, val sub: String, val label: String, val frac: Float)

    private fun card(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        p.style = Paint.Style.FILL; p.color = 0x22FFFFFF
        c.drawRoundRect(RectF(l, t, r, b), dp(10f), dp(10f), p)
    }

    private fun drawMiniCard(c: Canvas, l: Float, t: Float, r: Float, b: Float, title: String, value: String, hist: ArrayDeque<Float>, color: Int) {
        card(c, l, t, r, b)
        text(c, title, l + dp(8f), t + dp(15f), 10f, color, bold = true)
        text(c, value, r - dp(8f), t + dp(15f), 11f, cText, bold = true, right = true)
        spark(c, l + dp(8f), t + dp(22f), r - dp(8f), b - dp(6f), hist, color)
    }

    private fun drawInfoCard(c: Canvas, l: Float, t: Float, r: Float, b: Float, title: String, value: String, sub: String, frac: Float) {
        card(c, l, t, r, b)
        text(c, title, l + dp(8f), t + dp(14f), 9f, cSub)
        text(c, value, r - dp(8f), t + dp(14f), 11f, cText, bold = true, right = true)
        // barra
        val by = t + dp(26f); val bw = r - l - dp(16f)
        p.style = Paint.Style.FILL; p.color = cTrack
        c.drawRoundRect(RectF(l + dp(8f), by, r - dp(8f), by + dp(5f)), dp(2.5f), dp(2.5f), p)
        p.color = cBlue
        c.drawRoundRect(RectF(l + dp(8f), by, l + dp(8f) + bw * frac.coerceIn(0f, 1f), by + dp(5f)), dp(2.5f), dp(2.5f), p)
        text(c, sub, l + dp(8f), b - dp(7f), 8.5f, cSub)
    }

    private fun drawNetCard(c: Canvas, l: Float, t: Float, r: Float, b: Float, d: QuickStats.Data?) {
        card(c, l, t, r, b)
        text(c, "Red", l + dp(8f), t + dp(14f), 9f, cSub)
        text(c, "↑ ${net(d?.netUpKbs)}", l + dp(8f), t + dp(28f), 9.5f, cBlue)
        text(c, "↓ ${net(d?.netDownKbs)}", l + dp(8f), t + dp(40f), 9.5f, cGreen)
        spark(c, l + dp(8f), t + dp(44f), r - dp(8f), b - dp(6f), netHist, cBlue, maxV = 2000f)
    }

    private fun drawFpsCard(c: Canvas, l: Float, t: Float, r: Float, b: Float, d: QuickStats.Data?) {
        card(c, l, t, r, b)
        text(c, "FPS", l + dp(8f), t + dp(14f), 9f, cSub)
        text(c, d?.fps?.toString() ?: "—", l + dp(8f), t + dp(30f), 15f, cGreenFps, bold = true)
        spark(c, l + dp(8f), t + dp(34f), r - dp(8f), b - dp(6f), fpsHist, cGreenFps, maxV = 120f)
    }

    private fun net(v: Float?) = when { v == null -> "—"; v >= 1024 -> "%.1f MB/s".format(v / 1024); else -> "%.0f KB/s".format(v) }

    // ---------- Estilo 1: compacto (una línea horizontal) ----------
    private fun drawCompact(c: Canvas) {
        roundBg(c, 0x0E1420, dp(14f), 0x553FA9FF)
        val d = data
        val items = listOf(
            Triple("CPU", pct(d?.cpu), load(d?.cpu)),
            Triple("GPU", pct(d?.gpu), cPurple),
            Triple("RAM", pct(d?.ram), load(d?.ram)),
            Triple("TEMP", d?.cpuTemp?.let { "%.0f°".format(it) } ?: "—", cOrange),
            Triple("FPS", d?.fps?.toString() ?: "—", cGreenFps),
        )
        val colW = width / items.size.toFloat()
        items.forEachIndexed { i, (label, value, color) ->
            val cx = colW * i + colW / 2
            text(c, label, cx, dp(22f), 10f, cSub, center = true)
            text(c, value, cx, dp(44f), 16f, cText, bold = true, center = true)
            // barrita de actividad bajo cada valor (menos FPS, que lleva mini-gráfica)
            if (i < 4) {
                val frac = when (label) { "TEMP" -> (d?.cpuTemp ?: 0f) / 100f; else -> (value.filter { it.isDigit() }.toIntOrNull() ?: 0) / 100f }
                p.style = Paint.Style.FILL; p.color = cTrack
                c.drawRoundRect(RectF(cx - dp(22f), dp(56f), cx + dp(22f), dp(60f)), dp(2f), dp(2f), p)
                p.color = color
                c.drawRoundRect(RectF(cx - dp(22f), dp(56f), cx - dp(22f) + dp(44f) * frac.coerceIn(0f, 1f), dp(60f)), dp(2f), dp(2f), p)
            } else {
                spark(c, cx - dp(26f), dp(50f), cx + dp(26f), dp(64f), fpsHist, cGreenFps, maxV = 120f)
            }
        }
    }

    // ---------- Estilo 2: gamer ----------
    private fun drawGamer(c: Canvas) {
        // marco con esquinas recortadas y degradado neón
        val r = RectF(dp(2f), dp(2f), width - dp(2f), height - dp(2f))
        p.style = Paint.Style.FILL; p.color = (bgAlpha shl 24) or 0x0A0A14
        val cut = dp(16f)
        val path = Path().apply {
            moveTo(r.left + cut, r.top); lineTo(r.right - cut, r.top); lineTo(r.right, r.top + cut)
            lineTo(r.right, r.bottom - cut); lineTo(r.right - cut, r.bottom); lineTo(r.left + cut, r.bottom)
            lineTo(r.left, r.bottom - cut); lineTo(r.left, r.top + cut); close()
        }
        c.drawPath(path, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(2f)
        p.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), intArrayOf(cBlue, cPurple, cGreen), null, Shader.TileMode.CLAMP)
        c.drawPath(path, p); p.shader = null
        val d = data
        // Logo de la app con un anillo neón alrededor
        val lx = dp(50f); val ly = dp(56f); val lr = dp(24f)
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(2f)
        p.shader = LinearGradient(lx - lr, ly - lr, lx + lr, ly + lr, intArrayOf(cBlue, cPurple), null, Shader.TileMode.CLAMP)
        c.drawCircle(lx, ly, lr + dp(4f), p); p.shader = null
        drawAppLogo(c, context, lx, ly, lr)
        text(c, "MONITOR", lx, ly + lr + dp(18f), 10f, cPurple, bold = true, center = true)
        // cuatro medidores
        val gy = dp(62f); val gr = dp(24f)
        val startX = dp(96f); val span = width - startX - dp(16f); val gw = span / 4
        listOf(
            Triple("CPU", pct(d?.cpu), cBlue), Triple("GPU", pct(d?.gpu), cPurple),
            Triple("RAM", pct(d?.ram), cGreen), Triple("TEMP", d?.cpuTemp?.let { "%.0f°".format(it) } ?: "—", cOrange),
        ).forEachIndexed { i, (label, value, color) ->
            val cx = startX + gw * i + gw / 2
            val frac = when (label) { "TEMP" -> (d?.cpuTemp ?: 0f) / 100f; else -> (value.filter { it.isDigit() }.toIntOrNull() ?: 0) / 100f }
            gauge(c, cx, gy, gr, frac, color, value, null, label)
        }
        // franja inferior: FPS, red, almacenamiento
        val by = height - dp(22f)
        text(c, "FPS ${d?.fps ?: "—"}", dp(18f), by, 11f, cPurple, bold = true)
        text(c, "↓${net(d?.netDownKbs)}  ↑${net(d?.netUpKbs)}", dp(130f), by, 10f, cBlue)
        text(c, stPct(d)?.let { "$it%" } ?: "—", width - dp(18f), by, 11f, cGreen, bold = true, right = true)
    }

    // ---------- Estilo 3: retro monocromo ----------
    private fun drawRetro(c: Canvas) {
        p.style = Paint.Style.FILL; p.color = (bgAlpha shl 24) or 0x0A120A
        c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), dp(6f), dp(6f), p)
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1f); p.color = 0xFF2E7D32.toInt()
        c.drawRoundRect(RectF(dp(1f), dp(1f), width - dp(1f), height - dp(1f)), dp(6f), dp(6f), p)
        val green = 0xFF4ADE80.toInt(); val d = data
        val pad = dp(14f)
        text(c, "Monitor Root v3.0", pad, pad + dp(12f), 11f, green, bold = true, face = mono)
        p.style = Paint.Style.FILL; p.color = green; c.drawCircle(width - pad - dp(40f), pad + dp(8f), dp(3f), p)
        text(c, "Live", width - pad - dp(32f), pad + dp(12f), 10f, green, face = mono)
        // columna izquierda: valores
        val lines = listOf("CPU" to pct(d?.cpu), "GPU" to pct(d?.gpu), "RAM" to pct(d?.ram), "TEMP" to (d?.cpuTemp?.let { "%.0f°C".format(it) } ?: "—"))
        var y = pad + dp(34f)
        lines.forEach { (k, v) ->
            text(c, k.padEnd(5) + ": " + v, pad, y, 11f, green, face = mono)
            y += dp(20f)
        }
        // barras de actividad a la derecha (como la gráfica de barras del boceto)
        val bl = width * 0.42f; val br = width - pad; val bt = pad + dp(26f); val bb = height - dp(34f)
        val bars = cpuHist.toList().takeLast(26)
        if (bars.isNotEmpty()) {
            val bw = (br - bl) / bars.size
            bars.forEachIndexed { i, v ->
                val bh = (v / 100f).coerceIn(0.05f, 1f) * (bb - bt)
                p.color = green
                c.drawRect(bl + i * bw + dp(1f), bb - bh, bl + (i + 1) * bw - dp(1f), bb, p)
            }
        }
        // pie
        text(c, "FPS: ${d?.fps ?: "—"}  |  NET ↑${net(d?.netUpKbs)} ↓${net(d?.netDownKbs)}  |  ST ${stPct(d)?.let { "$it%" } ?: "—"}",
            pad, height - dp(12f), 9.5f, green, face = mono)
    }
}
