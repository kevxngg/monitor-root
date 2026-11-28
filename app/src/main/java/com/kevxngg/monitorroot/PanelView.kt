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
 * Draws the Monitor logo (same as the app's): blue circle with the white icon.
 * [alpha] lets the minimized bubble look translucent.
 */
fun drawAppLogo(c: Canvas, ctx: Context, cx: Float, cy: Float, r: Float, alpha: Int = 0xFF) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.style = Paint.Style.FILL
    p.color = (alpha shl 24) or 0x117DBB
    c.drawCircle(cx, cy, r, p)
    // ic_fg is the adaptive icon's foreground: the symbol takes up ~55 % of the canvas
    val icon = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.ic_fg) ?: return
    icon.setBounds((cx - r).toInt(), (cy - r).toInt(), (cx + r).toInt(), (cy + r).toInt())
    icon.alpha = alpha
    icon.draw(c)
}

/**
 * Draws the floating monitor in its four styles (full, compact, gamer, retro)
 * with a native Canvas, so it works as a floating window without Compose.
 * Reuses the QuickStats.Data data and keeps a small history for the graphs.
 */
class PanelView(ctx: Context) : View(ctx) {
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * density * scale

    var scale = 1f
    private val appVersion: String by lazy {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }
    var style = 0
    var bgAlpha = 0xD8
    var data: QuickStats.Data? = null
        set(value) { field = value; pushHistory(value); invalidate() }

    // History for the mini graphs
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

    // Palette
    private val cBlue = 0xFF3FA9FF.toInt()
    private val cPurple = 0xFF9B5CFF.toInt()
    private val cGreen = 0xFF36D399.toInt()
    private val cOrange = 0xFFFF9F43.toInt()
    private val cText = 0xFFFFFFFF.toInt()
    private val cSub = 0xFF9AA4B2.toInt()
    private val cTrack = 0x33FFFFFF
    private val cGreenFps = 0xFF36D399.toInt()
    private val cDiablo = 0xFFFF4D4F.toInt()
    private val diablo get() = DiabloMode.active

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    // Tabular digits: a value keeps its width as it changes, so nothing jumps around every second
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cText; fontFeatureSettings = "tnum" }
    private val mono = Typeface.MONOSPACE
    private val inter: Typeface = try {
        androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.inter) ?: Typeface.DEFAULT
    } catch (_: Exception) {
        Typeface.DEFAULT
    }
    private val interBold: Typeface = Typeface.create(inter, Typeface.BOLD)
    private val monoBold: Typeface = Typeface.create(mono, Typeface.BOLD)

    /** Battery level and temperature, e.g. "83% · 34°C". */
    private fun batt(d: QuickStats.Data?): String? {
        val parts = listOfNotNull(d?.battLevel?.let { "$it%" }, d?.battTemp?.let { "%.0f°C".format(it) })
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    private fun pct(v: Int?) = v?.let { "$it%" } ?: "—"
    private val cHot = 0xFFFF5A5A.toInt()
    private fun load(v: Int?) = when { v == null -> cSub; v < 60 -> cGreen; v < 85 -> cOrange; else -> cHot }
    /** Temperature colour: orange as usual, red when it is really hot. */
    private fun tempColor(t: Float?) = if ((t ?: 0f) >= HOT_C) cHot else cOrange
    private val HOT_C = 80f

    /** Graph scale for FPS: the next usual refresh rate above what was seen (60, 90, 120, 144...). */
    private fun fpsScale(): Float {
        val top = fpsHist.maxOrNull() ?: 60f
        return listOf(60f, 90f, 120f, 144f, 165f, 240f).firstOrNull { it >= top } ?: top
    }

    /** Network graph scale: follows the traffic, so a slow connection is still visible. */
    private fun netScale(): Float = max(64f, (netHist.maxOrNull() ?: 0f) * 1.2f)

    /** Lowest FPS of the last ~30 readings (the stutters the average hides). */
    private fun fpsMin(): Int? = fpsHist.toList().takeLast(30).filter { it > 0f }.takeIf { it.size >= 5 }?.minOrNull()?.toInt()

    /** Width of a text with the given size and font. */
    private fun textW(s: String, size: Float, bold: Boolean = false, face: Typeface? = null): Float {
        textP.textSize = dp(size)
        textP.typeface = when {
            face === mono -> if (bold) monoBold else mono
            else -> if (bold) interBold else inter
        }
        return textP.measureText(s)
    }

    /**
     * Status at the right of the header, ending at [right]: a red DIABLO badge, "Live" when
     * readings arrive, grey "Connecting…" when there are none yet (no root, shell reconnecting).
     */
    private fun status(c: Canvas, right: Float, baseline: Float, live: Int, face: Typeface? = null) {
        if (diablo) {
            val label = "DIABLO"
            val w = textW(label, 9.5f, bold = true, face = face) + dp(12f)
            p.style = Paint.Style.FILL; p.color = cDiablo
            c.drawRoundRect(RectF(right - w, baseline - dp(11f), right, baseline + dp(4f)), dp(7.5f), dp(7.5f), p)
            text(c, label, right - w / 2, baseline, 9.5f, cText, bold = true, center = true, face = face)
            return
        }
        val ok = data != null
        val label = if (ok) tr("En vivo") else tr("Conectando…")
        val color = if (ok) live else cSub
        val w = textW(label, 11f, face = face)
        text(c, label, right, baseline, 11f, color, right = true, face = face)
        p.style = Paint.Style.FILL; p.color = color
        c.drawCircle(right - w - dp(8f), baseline - dp(4f), dp(3.5f), p)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w: Float; val h: Float
        when (style) {
            1 -> { w = dp(330f); h = dp(92f) }      // compact (horizontal)
            2 -> { w = dp(360f); h = dp(150f) }     // gamer
            3 -> { w = dp(360f); h = dp(140f) }     // retro
            else -> { w = dp(340f); h = dp(300f) }  // full
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

    // ---------- drawing utilities ----------
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
        textP.typeface = when {
            face === mono -> if (bold) monoBold else mono
            face != null -> if (bold) Typeface.create(face, Typeface.BOLD) else face
            else -> if (bold) interBold else inter
        }
        textP.textAlign = when { right -> Paint.Align.RIGHT; center -> Paint.Align.CENTER; else -> Paint.Align.LEFT }
        c.drawText(s, x, y, textP)
    }

    /** Gauge-style ring (270° arc) with the value in the center. */
    private fun gauge(c: Canvas, cx: Float, cy: Float, radius: Float, frac: Float, color: Int, center: String, unit: String?, label: String) {
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(6f)
        p.strokeCap = Paint.Cap.ROUND
        p.color = cTrack
        c.drawArc(rect, 135f, 270f, false, p)
        p.color = color
        c.drawArc(rect, 135f, 270f * frac.coerceIn(0f, 1f), false, p)
        // label on top
        text(c, label, cx, cy - radius - dp(8f), 11f, cSub, center = true)
        // center value
        text(c, center, cx, cy + dp(5f), 17f, cText, bold = true, center = true)
        if (unit != null) text(c, unit, cx, cy + dp(20f), 9f, cSub, center = true)
    }

    /** Mini area graph. */
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

    /** Percentage of storage used, or null if the total is unknown (avoids dividing by 0). */
    private fun stPct(d: QuickStats.Data?): Long? =
        d?.stTotalKb?.takeIf { it > 0 }?.let { (d.stUsedKb ?: 0) * 100 / it }

    private fun gb(kb: Long?) = kb?.let { "%.1f".format(it / 1048576.0) } ?: "—"

    // ---------- Style 0: full panel (Windows 11) ----------
    private fun drawFull(c: Canvas) {
        roundBg(c, 0x0E1420, dp(18f), 0x553FA9FF)
        val d = data
        val pad = dp(16f)
        // header
        drawAppLogo(c, context, pad + dp(10f), pad + dp(9f), dp(10f))
        text(c, "Monitor", pad + dp(26f), pad + dp(14f), 15f, cText, bold = true)
        batt(d)?.let { text(c, it, pad + dp(32f) + textW("Monitor", 15f, bold = true), pad + dp(13.5f), 10.5f, cSub) }
        // The top-right corner is the minimize area: the status ends before it
        status(c, width - pad - dp(40f), pad + dp(13f), cGreen)
        // four gauges
        val gy = pad + dp(66f); val gr = dp(26f)   // room between the header and the gauge labels
        val cols = 4; val gw = (width - pad * 2) / cols
        val vals = listOf(
            Quad(cBlue, pct(d?.cpu), d?.cpuMhz?.let { "%.2f GHz".format(it / 1000f) } ?: "", "CPU", (d?.cpu ?: 0) / 100f),
            Quad(cPurple, pct(d?.gpu), d?.gpuMhz?.let { "$it MHz" } ?: "", "GPU", (d?.gpu ?: 0) / 100f),
            Quad(cGreen, pct(d?.ram), "${gb(d?.ramUsedKb)}/${gb(d?.ramTotalKb)} GB", "RAM", (d?.ram ?: 0) / 100f),
            Quad(tempColor(d?.cpuTemp), d?.cpuTemp?.let { "%.0f".format(it) } ?: "—", "CPU", "TEMP", ((d?.cpuTemp ?: 0f) / 100f)),
        )
        vals.forEachIndexed { i, q ->
            val cx = pad + gw * i + gw / 2
            gauge(c, cx, gy, gr, q.frac, q.color, q.center, if (i == 3) "°C" else null, q.label)
            text(c, q.sub, cx, gy + gr + dp(18f), 9f, cSub, center = true)
        }
        // two graphs: CPU and RAM
        val cardTop = gy + gr + dp(30f)
        val cardH = dp(66f); val gap = dp(8f)
        val cardW = (width - pad * 2 - gap) / 2
        drawMiniCard(c, pad, cardTop, pad + cardW, cardTop + cardH, "CPU", pct(d?.cpu), cpuHist, cBlue)
        drawMiniCard(c, pad + cardW + gap, cardTop, width - pad, cardTop + cardH, "RAM", pct(d?.ram), ramHist, cGreen)
        // bottom row: storage, network, fps
        val rTop = cardTop + cardH + gap
        val rH = dp(60f); val rw = (width - pad * 2 - gap * 2) / 3
        drawInfoCard(c, pad, rTop, pad + rw, rTop + rH, tr("Disco"), stPct(d)?.let { "$it%" } ?: "—",
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
        // bar
        val by = t + dp(26f); val bw = r - l - dp(16f)
        p.style = Paint.Style.FILL; p.color = cTrack
        c.drawRoundRect(RectF(l + dp(8f), by, r - dp(8f), by + dp(5f)), dp(2.5f), dp(2.5f), p)
        p.color = cBlue
        c.drawRoundRect(RectF(l + dp(8f), by, l + dp(8f) + bw * frac.coerceIn(0f, 1f), by + dp(5f)), dp(2.5f), dp(2.5f), p)
        text(c, sub, l + dp(8f), b - dp(7f), 8.5f, cSub)
    }

    private fun drawNetCard(c: Canvas, l: Float, t: Float, r: Float, b: Float, d: QuickStats.Data?) {
        card(c, l, t, r, b)
        text(c, tr("Red"), l + dp(8f), t + dp(14f), 9f, cSub)
        text(c, "↑ ${net(d?.netUpKbs)}", l + dp(8f), t + dp(28f), 9.5f, cBlue)
        text(c, "↓ ${net(d?.netDownKbs)}", l + dp(8f), t + dp(40f), 9.5f, cGreen)
        spark(c, l + dp(8f), t + dp(44f), r - dp(8f), b - dp(6f), netHist, cGreen, maxV = netScale())
    }

    private fun drawFpsCard(c: Canvas, l: Float, t: Float, r: Float, b: Float, d: QuickStats.Data?) {
        card(c, l, t, r, b)
        text(c, "FPS", l + dp(8f), t + dp(14f), 9f, cSub)
        text(c, d?.fps?.toString() ?: "—", l + dp(8f), t + dp(30f), 15f, cGreenFps, bold = true)
        // Lowest of the last seconds: the stutters an average hides
        fpsMin()?.let { text(c, tr("mín %d", it), r - dp(8f), t + dp(14f), 8.5f, cSub, right = true) }
        spark(c, l + dp(8f), t + dp(34f), r - dp(8f), b - dp(6f), fpsHist, cGreenFps, maxV = fpsScale())
    }

    private fun net(v: Float?) = when { v == null -> "—"; v >= 1024 -> "%.1f MB/s".format(v / 1024); else -> "%.0f KB/s".format(v) }

    // ---------- Style 1: compact (single horizontal line) ----------
    private fun drawCompact(c: Canvas) {
        roundBg(c, 0x0E1420, dp(14f), if (diablo) cDiablo else 0x553FA9FF)
        val d = data
        val items = listOf(
            Triple("CPU", pct(d?.cpu), load(d?.cpu)),
            Triple("GPU", pct(d?.gpu), cPurple),
            Triple("RAM", pct(d?.ram), load(d?.ram)),
            Triple("TEMP", d?.cpuTemp?.let { "%.0f°".format(it) } ?: "—", tempColor(d?.cpuTemp)),
            Triple("FPS", d?.fps?.toString() ?: "—", cGreenFps),
        )
        val colW = width / items.size.toFloat()
        items.forEachIndexed { i, (label, value, color) ->
            val cx = colW * i + colW / 2
            text(c, label, cx, dp(22f), 10f, cSub, center = true)
            text(c, value, cx, dp(44f), 16f, cText, bold = true, center = true)
            // small activity bar under each value (except FPS, which has a mini graph)
            if (i < 4) {
                val frac = when (label) { "TEMP" -> (d?.cpuTemp ?: 0f) / 100f; else -> (value.filter { it.isDigit() }.toIntOrNull() ?: 0) / 100f }
                p.style = Paint.Style.FILL; p.color = cTrack
                c.drawRoundRect(RectF(cx - dp(22f), dp(56f), cx + dp(22f), dp(60f)), dp(2f), dp(2f), p)
                p.color = color
                c.drawRoundRect(RectF(cx - dp(22f), dp(56f), cx - dp(22f) + dp(44f) * frac.coerceIn(0f, 1f), dp(60f)), dp(2f), dp(2f), p)
            } else {
                spark(c, cx - dp(26f), dp(50f), cx + dp(26f), dp(64f), fpsHist, cGreenFps, maxV = fpsScale())
            }
        }
        val foot = listOfNotNull("↓ ${net(d?.netDownKbs)}", "↑ ${net(d?.netUpKbs)}", batt(d)?.let { "BAT $it" }).joinToString("    ")
        text(c, foot, width / 2f, dp(81f), 9.5f, cSub, center = true)
    }

    // ---------- Style 2: gamer ----------
    private fun drawGamer(c: Canvas) {
        // frame with clipped corners and neon gradient
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
        // App logo with a neon ring around it
        val lx = dp(50f); val ly = dp(56f); val lr = dp(24f)
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(2f)
        p.shader = LinearGradient(lx - lr, ly - lr, lx + lr, ly + lr, intArrayOf(cBlue, cPurple), null, Shader.TileMode.CLAMP)
        c.drawCircle(lx, ly, lr + dp(4f), p); p.shader = null
        drawAppLogo(c, context, lx, ly, lr)
        text(c, if (diablo) "DIABLO" else "MONITOR", lx, ly + lr + dp(18f), 10f, if (diablo) cDiablo else cPurple, bold = true, center = true)
        // four gauges
        val gy = dp(62f); val gr = dp(24f)
        val startX = dp(96f); val span = width - startX - dp(16f); val gw = span / 4
        listOf(
            Triple("CPU", pct(d?.cpu), cBlue), Triple("GPU", pct(d?.gpu), cPurple),
            Triple("RAM", pct(d?.ram), cGreen), Triple("TEMP", d?.cpuTemp?.let { "%.0f°".format(it) } ?: "—", tempColor(d?.cpuTemp)),
        ).forEachIndexed { i, (label, value, color) ->
            val cx = startX + gw * i + gw / 2
            val frac = when (label) { "TEMP" -> (d?.cpuTemp ?: 0f) / 100f; else -> (value.filter { it.isDigit() }.toIntOrNull() ?: 0) / 100f }
            gauge(c, cx, gy, gr, frac, color, value, null, label)
        }
        // bottom strip: FPS, network, storage
        val by = height - dp(22f)
        text(c, "FPS ${d?.fps ?: "—"}", dp(18f), by, 11f, cPurple, bold = true)
        // Centred between FPS and battery: long values (MB/s, 3-digit FPS) never run into each other
        text(c, "↓${net(d?.netDownKbs)}  ↑${net(d?.netUpKbs)}", width / 2f + dp(14f), by, 10f, cBlue, center = true)
        text(c, d?.battLevel?.let { "BAT $it%" } ?: "—", width - dp(18f), by, 11f, cGreen, bold = true, right = true)
    }

    // ---------- Style 3: retro monochrome ----------
    private fun drawRetro(c: Canvas) {
        p.style = Paint.Style.FILL; p.color = (bgAlpha shl 24) or 0x0A120A
        c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), dp(6f), dp(6f), p)
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1f); p.color = 0xFF2E7D32.toInt()
        c.drawRoundRect(RectF(dp(1f), dp(1f), width - dp(1f), height - dp(1f)), dp(6f), dp(6f), p)
        val green = 0xFF4ADE80.toInt(); val d = data
        val pad = dp(14f)
        text(c, "Monitor Root v$appVersion", pad, pad + dp(12f), 11f, green, bold = true, face = mono)
        status(c, width - pad - dp(36f), pad + dp(12f), green, face = mono)
        // left column: values
        val lines = listOf("CPU" to pct(d?.cpu), "GPU" to pct(d?.gpu), "RAM" to pct(d?.ram), "TEMP" to (d?.cpuTemp?.let { "%.0f°C".format(it) } ?: "—"))
        var y = pad + dp(34f)
        lines.forEach { (k, v) ->
            text(c, k.padEnd(5) + ": " + v, pad, y, 11f, green, face = mono)
            y += dp(20f)
        }
        // activity bars on the right (like the bar chart in the sketch)
        val bl = width * 0.42f; val br = width - pad; val bt = pad + dp(26f); val bb = height - dp(34f)
        val bars = cpuHist.toList().takeLast(26)
        // What the bars are: CPU over the last seconds, on a dim baseline
        text(c, "CPU", br, bt - dp(2f), 8.5f, 0xFF2E7D32.toInt(), right = true, face = mono)
        p.style = Paint.Style.FILL; p.color = 0xFF1B5E20.toInt()
        c.drawRect(bl, bb, br, bb + dp(1f), p)
        if (bars.isNotEmpty()) {
            val bw = (br - bl) / bars.size
            bars.forEachIndexed { i, v ->
                val bh = (v / 100f).coerceIn(0.05f, 1f) * (bb - bt)
                p.color = green
                c.drawRect(bl + i * bw + dp(1f), bb - bh, bl + (i + 1) * bw - dp(1f), bb, p)
            }
        }
        // footer
        text(c, "FPS: ${d?.fps ?: "—"}  |  NET ↑${net(d?.netUpKbs)} ↓${net(d?.netDownKbs)}  |  BAT ${d?.battLevel?.let { "$it%" } ?: "—"}",
            pad, height - dp(12f), 9.5f, green, face = mono)
    }
}
