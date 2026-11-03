package com.kevxngg.monitorroot

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlin.math.abs

/** Estado visible desde la interfaz (Configuración y botón de ajustes rápidos). */
object OverlayState {
    var running by mutableStateOf(false)
}

/** Lecturas ligeras para el monitor flotante y el widget. */
object QuickStats {
    private val WS = Regex("\\s+")
    private val CPU_ZONE = Regex("cpu|soc|tsens|mtktscpu|apc", RegexOption.IGNORE_CASE)
    private var cpuZones: List<String>? = null
    private var prevTotal = 0L
    private var prevIdle = 0L
    private var prevFlips = -1L
    private var prevFlipTime = 0L

    class Data(
        val cpu: Int?, val cpuTemp: Float?, val gpu: Int?, val ram: Int?,
        val battTemp: Float?, val battLevel: Int?, val fps: Int?,
    )

    private fun zones(): List<String> {
        cpuZones?.let { return it }
        val found = RootShell.run("grep -H . /sys/class/thermal/thermal_zone*/type").mapNotNull { l ->
            val i = l.indexOf(':')
            if (i > 0 && CPU_ZONE.containsMatchIn(l.substring(i + 1))) l.substring(0, i).removeSuffix("/type") else null
        }
        cpuZones = found
        return found
    }

    private fun cpuFrom(line: String?): Pair<Long, Long>? {
        val n = line?.trim()?.split(WS)?.drop(1)?.mapNotNull { it.toLongOrNull() } ?: return null
        if (n.size < 5) return null
        return n.take(8).sum() to (n[3] + n[4])
    }

    // ---- FPS de la app en primer plano ----
    private val FOCUS = Regex("mCurrentFocus=Window\\{\\S+ u\\d+ ([\\w.]+)/")
    private var layer: String? = null
    private var layerCheckedAt = 0L

    /** Busca la capa de SurfaceFlinger donde dibuja la app enfocada (cada 4 s). */
    private fun currentLayer(): String? {
        val now = SystemClock.elapsedRealtime()
        if (now - layerCheckedAt < 4000) return layer
        layerCheckedAt = now
        val focus = RootShell.run("dumpsys window | grep -m 1 mCurrentFocus").firstOrNull()
        val pkg = focus?.let { FOCUS.find(it)?.groupValues?.get(1) }
        if (pkg == null) {
            layer = null
            return null
        }
        val layers = RootShell.run("dumpsys SurfaceFlinger --list").map { it.trim() }.filter { it.contains(pkg) }
        layer = layers.firstOrNull { it.startsWith("SurfaceView") && it.contains("BLAST") }
            ?: layers.firstOrNull { it.startsWith("SurfaceView") }
            ?: layers.firstOrNull { it.contains("BLAST") }
            ?: layers.firstOrNull()
        return layer
    }

    /** Cuenta los cuadros que la capa mostró en el último segundo. */
    private fun layerFps(): Int? {
        val name = currentLayer() ?: return null
        val lines = RootShell.run("dumpsys SurfaceFlinger --latency ${Actions.q(name)}")
        if (lines.size < 3) return null
        val times = lines.drop(1).mapNotNull { l -> l.trim().split(WS).getOrNull(1)?.toLongOrNull() }
            .filter { it > 0 && it != Long.MAX_VALUE }
        if (times.size < 2) return null
        // Las marcas de tiempo usan el mismo reloj monótono que System.nanoTime()
        val since = System.nanoTime() - 1_000_000_000L
        return times.count { it > since }.coerceAtMost(240)
    }

    // ---- TimeStats de SurfaceFlinger: cuadros mostrados por la pantalla ----
    private var tsReady = false
    private var tsLast = 0L
    private var tsWorks = true

    fun startTimeStats() {
        RootShell.run("dumpsys SurfaceFlinger --timestats -enable -clear")
        tsReady = true
        tsWorks = true
        tsLast = SystemClock.elapsedRealtime()
    }

    fun stopTimeStats() {
        RootShell.run("dumpsys SurfaceFlinger --timestats -disable")
        tsReady = false
    }

    /** Lee el total de cuadros desde la última lectura y lo reinicia. */
    private fun timeStatsFrames(): Long? {
        val out = RootShell.run("dumpsys SurfaceFlinger --timestats -dump; dumpsys SurfaceFlinger --timestats -clear")
        // El primer "totalFrames" (antes de las capas) es el de toda la pantalla
        for (l in out) {
            val t = l.trim()
            if (t.startsWith("layerName")) break
            if (t.startsWith("totalFrames")) return t.substringAfter('=').trim().toLongOrNull()
        }
        return null
    }

    private fun timeStatsFps(): Int? {
        if (!tsWorks) return null
        if (!tsReady) {
            startTimeStats()
            return null
        }
        val frames = timeStatsFrames()
        val now = SystemClock.elapsedRealtime()
        val dt = (now - tsLast) / 1000f
        tsLast = now
        if (frames == null) {
            tsWorks = false // este teléfono no tiene TimeStats: se usan los otros métodos
            return null
        }
        if (dt < 0.3f) return null
        return (frames / dt).toInt().coerceIn(0, 240)
    }

    /** Prueba cada método por separado (para Configuración > Probar FPS). */
    @Synchronized
    fun diagnoseFps(): String {
        val sb = StringBuilder()
        val focus = RootShell.run("dumpsys window | grep -m 1 mCurrentFocus").firstOrNull()?.trim()
        sb.append("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
        sb.append("Foco: ${focus ?: "—"}\n")
        // TimeStats
        RootShell.run("dumpsys SurfaceFlinger --timestats -enable -clear")
        val t0 = SystemClock.elapsedRealtime()
        val f0 = RootShell.run("service call SurfaceFlinger 1013").joinToString(" ")
        SystemClock.sleep(1000)
        val ts = timeStatsFrames()
        val dt = (SystemClock.elapsedRealtime() - t0) / 1000f
        val f1 = RootShell.run("service call SurfaceFlinger 1013").joinToString(" ")
        sb.append("TimeStats: ${ts?.let { "%.0f FPS".format(it / dt) } ?: "no disponible"}\n")
        val re = Regex("Parcel\\(([0-9a-fA-F]+)")
        val a = re.find(f0)?.groupValues?.get(1)?.toLongOrNull(16)
        val b = re.find(f1)?.groupValues?.get(1)?.toLongOrNull(16)
        sb.append("SurfaceFlinger 1013: ${if (a != null && b != null) "%.0f FPS".format((b - a) / dt) else "no disponible"} [${f1.take(60)}]\n")
        layerCheckedAt = 0
        val layerName = currentLayer()
        sb.append("Capa: ${layerName ?: "—"}\n")
        sb.append("Capa (latency): ${layerFps()?.let { "$it FPS" } ?: "no disponible"}\n")
        val m = RootShell.run("cat /sys/class/drm/sde-crtc-*/measured_fps").joinToString(" | ")
        sb.append("measured_fps: ${m.ifBlank { "no disponible" }}\n")
        if (!tsReady) RootShell.run("dumpsys SurfaceFlinger --timestats -disable")
        return sb.toString()
    }

    /** [fps] = true también mide los cuadros por segundo de la pantalla. */
    @Synchronized
    fun read(fps: Boolean): Data {
        val z = zones()
        val script = buildString {
            append("head -n 1 /proc/stat; echo @@; ")
            append("grep -H . /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage /sys/kernel/gpu/gpu_busy /sys/kernel/ged/hal/gpu_utilization; echo @@; ")
            append("grep -E '^(MemTotal|MemAvailable):' /proc/meminfo; echo @@; ")
            append("cat /sys/class/power_supply/battery/temp /sys/class/power_supply/battery/capacity; echo @@; ")
            if (z.isNotEmpty()) append("cat ${z.joinToString(" ") { "$it/temp" }}; ")
            append("echo @@; ")
            if (fps) append("cat /sys/class/drm/sde-crtc-*/measured_fps; echo @@; service call SurfaceFlinger 1013; ")
        }
        val parts = RootShell.run(script).joinToString("\n").split("@@").map { it.trim() }

        // CPU
        var cpu: Int? = null
        cpuFrom(parts.getOrNull(0)?.lines()?.firstOrNull())?.let { (total, idle) ->
            if (prevTotal > 0 && total > prevTotal) {
                cpu = (((total - prevTotal) - (idle - prevIdle)) * 100 / (total - prevTotal)).toInt().coerceIn(0, 100)
            }
            prevTotal = total; prevIdle = idle
        }
        // GPU
        val gpu = parts.getOrNull(1)?.lines()?.firstNotNullOfOrNull { l ->
            Regex("\\d+").find(l.substringAfter(':'))?.value?.toIntOrNull()
        }?.coerceIn(0, 100)
        // RAM
        val mem = parts.getOrNull(2)?.lines().orEmpty().associate { l ->
            l.substringBefore(':') to (l.split(WS).getOrNull(1)?.toLongOrNull() ?: 0L)
        }
        val total = mem["MemTotal"] ?: 0L
        val ram = if (total > 0) ((total - (mem["MemAvailable"] ?: 0L)) * 100 / total).toInt() else null
        // Batería
        val batt = parts.getOrNull(3)?.lines().orEmpty()
        val battTemp = batt.getOrNull(0)?.trim()?.toFloatOrNull()?.div(10f)
        val battLevel = batt.getOrNull(1)?.trim()?.toIntOrNull()
        // Temperatura de la CPU (máxima de sus sensores, descartando valores imposibles)
        val cpuTemp = parts.getOrNull(4)?.lines().orEmpty().mapNotNull { it.trim().toFloatOrNull() }
            .map { if (abs(it) >= 1000f) it / 1000f else it }
            .filter { it > 0f && it < 125f }
            .maxOrNull()
        // FPS de la pantalla. Primero las estadísticas de SurfaceFlinger (TimeStats),
        // que cuentan los cuadros que la pantalla muestra realmente; si no están
        // disponibles, se usan los otros métodos.
        var fpsValue: Int? = null
        if (fps) {
            val measured = parts.getOrNull(5)?.let { txt ->
                Regex("fps:\\s*([\\d.]+)").findAll(txt).mapNotNull { it.groupValues[1].toFloatOrNull()?.toInt() }.maxOrNull()
            }?.takeIf { it > 0 }
            val flips = parts.getOrNull(6)?.let { Regex("Parcel\\(([0-9a-fA-F]+)").find(it)?.groupValues?.get(1)?.toLongOrNull(16) }
            val now = SystemClock.elapsedRealtime()
            var flipFps: Int? = null
            if (flips != null && prevFlips >= 0 && now > prevFlipTime && flips >= prevFlips) {
                flipFps = ((flips - prevFlips) * 1000 / (now - prevFlipTime)).toInt().coerceIn(0, 240)
            }
            if (flips != null) {
                prevFlips = flips; prevFlipTime = now
            }
            val screen = timeStatsFps()
            fpsValue = screen ?: listOfNotNull(layerFps(), flipFps?.takeIf { it > 0 }, measured).maxOrNull()
        }
        return Data(cpu, cpuTemp, gpu, ram, battTemp, battLevel, fpsValue)
    }
}

/** Mini gráfica del FPS de los últimos segundos (diseño "Gráfica"). */
class OverlayGraph(ctx: Context) : View(ctx) {
    var values: List<Int> = emptyList()
        set(v) { field = v; invalidate() }
    private val dp = ctx.resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f * dp; color = 0xFF60CDFF.toInt(); strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x4460CDFF }
    private val grid = Paint().apply { color = 0x22FFFFFF; strokeWidth = 1f }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((150 * dp).toInt(), (34 * dp).toInt())

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 1..3) c.drawLine(0f, h * i / 4f, w, h * i / 4f, grid)
        if (values.size < 2) return
        val top = maxOf(60, values.maxOrNull() ?: 60).toFloat()
        val step = w / (MAX - 1)
        val p = Path()
        values.forEachIndexed { i, v ->
            val x = w - (values.size - 1 - i) * step
            val y = h - (v / top) * (h - 2 * dp) - dp
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        val area = Path(p).apply {
            lineTo(w, h); lineTo(w - (values.size - 1) * step, h); close()
        }
        c.drawPath(area, fill)
        c.drawPath(p, line)
    }

    companion object { const val MAX = 40 }
}

/** Ventana flotante con CPU, GPU, RAM, temperaturas y FPS encima de cualquier app. */
class OverlayService : Service() {
    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private var label: TextView? = null
    private var graph: OverlayGraph? = null
    private var lp: WindowManager.LayoutParams? = null
    private var last: QuickStats.Data? = null
    private val fpsHistory = ArrayList<Int>()
    @Volatile private var alive = false
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.init(applicationContext)
        I18n.init(applicationContext)
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        addView()
        alive = true
        OverlayState.running = true
        Thread {
            while (alive) {
                val d = try {
                    if (RootShell.open()) QuickStats.read(fps = true) else null
                } catch (_: Exception) {
                    null
                }
                main.post { show(d) }
                SystemClock.sleep(1000)
            }
            // Al cerrar se apagan las estadísticas de SurfaceFlinger
            try { QuickStats.stopTimeStats() } catch (_: Exception) {}
        }.start()
    }

    override fun onDestroy() {
        alive = false
        OverlayState.running = false
        root?.let { v -> try { wm?.removeView(v) } catch (_: Exception) {} }
        root = null
        TileService.requestListeningState(this, ComponentName(this, MonitorTileService::class.java))
        super.onDestroy()
    }

    /** Al girar la pantalla, la ventana se recoloca para quedar siempre visible. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        main.postDelayed({ placeFromFractions() }, 150)
    }

    private fun prefs() = getSharedPreferences("prefs", Context.MODE_PRIVATE)

    private fun style() = prefs().getInt("ovStyle", 0).coerceIn(0, STYLES - 1)

    // ---- Colores según la carga: verde, amarillo o rojo ----
    private fun loadColor(v: Int?) = when {
        v == null -> WHITE
        v < 60 -> GREEN
        v < 85 -> AMBER
        else -> RED
    }

    private fun tempColor(t: Float?) = when {
        t == null -> WHITE
        t < 45f -> GREEN
        t < 60f -> AMBER
        else -> RED
    }

    private fun fpsColor(f: Int?) = when {
        f == null -> WHITE
        f >= 50 -> ACCENT
        f >= 30 -> AMBER
        else -> RED
    }

    /** Arma el texto del diseño elegido. */
    private fun build(d: QuickStats.Data?, style: Int): CharSequence {
        val sb = SpannableStringBuilder()
        fun span(text: String, color: Int, vararg extra: Any) {
            val start = sb.length
            sb.append(text)
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            extra.forEach { sb.setSpan(it, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        if (d == null) {
            span("Monitor  ", WHITE)
            span(tr("Sin acceso root"), RED)
            return sb
        }
        fun pct(v: Int?) = v?.let { "$it%" } ?: "—"
        fun deg(t: Float?) = t?.let { "%.0f°".format(it) } ?: "—"
        val fpsText = d.fps?.toString() ?: "—"
        fun bigFps(scale: Float) {
            span(fpsText, fpsColor(d.fps), RelativeSizeSpan(scale), StyleSpan(Typeface.BOLD))
            span(" FPS", LABEL)
        }
        when (style) {
            // Completo (vertical)
            0 -> {
                bigFps(1.7f)
                listOf(Triple("CPU", d.cpu, d.cpuTemp), Triple("GPU", d.gpu, null), Triple("RAM", d.ram, null), Triple("BAT", d.battLevel, d.battTemp))
                    .forEach { (name, v, t) ->
                        sb.append("\n")
                        span("$name ", LABEL)
                        span(pct(v).padEnd(4), if (name == "BAT") WHITE else loadColor(v))
                        if (t != null) span(" " + deg(t), tempColor(t))
                    }
            }
            // Horizontal (una línea)
            1 -> {
                bigFps(1.25f)
                span("  │ ", DIM); span("CPU ", LABEL); span(pct(d.cpu), loadColor(d.cpu)); span(" " + deg(d.cpuTemp), tempColor(d.cpuTemp))
                span("  │ ", DIM); span("GPU ", LABEL); span(pct(d.gpu), loadColor(d.gpu))
                span("  │ ", DIM); span("RAM ", LABEL); span(pct(d.ram), loadColor(d.ram))
                span("  │ ", DIM); span("BAT ", LABEL); span(pct(d.battLevel), WHITE); span(" " + deg(d.battTemp), tempColor(d.battTemp))
            }
            // Compacto: FPS, CPU y temperatura
            2 -> {
                bigFps(1.25f)
                span("  ", LABEL); span(pct(d.cpu), loadColor(d.cpu))
                span("  ", LABEL); span(deg(d.cpuTemp), tempColor(d.cpuTemp))
            }
            // Solo FPS
            3 -> {
                span(fpsText, fpsColor(d.fps), RelativeSizeSpan(2.2f), StyleSpan(Typeface.BOLD))
                span("\nFPS", LABEL, RelativeSizeSpan(0.85f))
            }
            // Gráfica: FPS + CPU/GPU debajo de la mini gráfica
            else -> {
                bigFps(1.5f)
                span("   CPU ", LABEL); span(pct(d.cpu), loadColor(d.cpu))
                span("  GPU ", LABEL); span(pct(d.gpu), loadColor(d.gpu))
            }
        }
        return sb
    }

    /** Aplica diseño, tamaño y opacidad elegidos. */
    private fun applyLook(style: Int) {
        val tv = label ?: return
        val p = prefs()
        val size = p.getInt("ovSize", 1)
        val opacity = p.getInt("ovOpacity", 1)
        val dp = resources.displayMetrics.density
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, when (size) { 0 -> 9.5f; 2 -> 13.5f; else -> 11f })
        tv.gravity = if (style == 3) Gravity.CENTER else Gravity.START
        tv.typeface = if (style == 3) Typeface.DEFAULT_BOLD else Typeface.MONOSPACE
        val alpha = when (opacity) { 0 -> 0x80; 2 -> 0xF0; else -> 0xC0 }
        (root?.background as? GradientDrawable)?.setColor((alpha shl 24) or 0x161616)
        val pad = when (size) { 0 -> 7; 2 -> 12; else -> 9 }
        root?.setPadding((pad * 1.3f * dp).toInt(), (pad * dp).toInt(), (pad * 1.3f * dp).toInt(), (pad * dp).toInt())
        graph?.visibility = if (style == 4) View.VISIBLE else View.GONE
    }

    private fun show(d: QuickStats.Data?) {
        last = d
        d?.fps?.let {
            fpsHistory.add(it)
            while (fpsHistory.size > OverlayGraph.MAX) fpsHistory.removeAt(0)
        }
        val style = style()
        applyLook(style)
        label?.text = build(d, style)
        graph?.values = fpsHistory.toList()
        root?.post { clampToScreen() }
    }

    // ---- Posición: se guarda como fracción de la pantalla para que sirva en vertical y horizontal ----
    private fun screen(): Pair<Int, Int> {
        val m = resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    private fun clampToScreen() {
        val v = root ?: return
        val p = lp ?: return
        val (w, h) = screen()
        val maxX = (w - v.width).coerceAtLeast(0)
        val maxY = (h - v.height).coerceAtLeast(0)
        val x = p.x.coerceIn(0, maxX)
        val y = p.y.coerceIn(0, maxY)
        if (x != p.x || y != p.y) {
            p.x = x; p.y = y
            try { wm?.updateViewLayout(v, p) } catch (_: Exception) {}
        }
    }

    private fun saveFractions() {
        val v = root ?: return
        val p = lp ?: return
        val (w, h) = screen()
        val fx = if (w - v.width > 0) p.x.toFloat() / (w - v.width) else 0f
        val fy = if (h - v.height > 0) p.y.toFloat() / (h - v.height) else 0f
        prefs().edit().putFloat("overlayFx", fx.coerceIn(0f, 1f)).putFloat("overlayFy", fy.coerceIn(0f, 1f)).apply()
    }

    private fun placeFromFractions() {
        val v = root ?: return
        val p = lp ?: return
        val (w, h) = screen()
        p.x = (prefs().getFloat("overlayFx", 0.03f) * (w - v.width).coerceAtLeast(0)).toInt()
        p.y = (prefs().getFloat("overlayFy", 0.12f) * (h - v.height).coerceAtLeast(0)).toInt()
        try { wm?.updateViewLayout(v, p) } catch (_: Exception) {}
    }

    /** Toque: siguiente diseño. Mantener presionado: abrir Monitor Root. Arrastrar: mover. */
    private fun nextStyle() {
        val next = (style() + 1) % STYLES
        prefs().edit().putInt("ovStyle", next).apply()
        Prefs.overlayStyle = next
        show(last)
        root?.post { clampToScreen(); saveFractions() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addView() {
        val manager = getSystemService(WindowManager::class.java)
        wm = manager
        val dp = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 14 * dp
                setColor(0xC0161616.toInt())
                setStroke((1 * dp).toInt(), 0x6660CDFF)
            }
        }
        val tv = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setLineSpacing(0f, 1.1f)
            setShadowLayer(2f, 0f, 1f, 0xAA000000.toInt())
            text = "Monitor"
        }
        val g = OverlayGraph(this).apply {
            visibility = View.GONE
            setPadding(0, (4 * dp).toInt(), 0, 0)
        }
        box.addView(tv)
        box.addView(g)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * dp).toInt()
            y = (120 * dp).toInt()
        }
        lp = params

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragging = false
        var longPressed = false
        val longPress = Runnable {
            longPressed = true
            try {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        }
        box.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y; touchX = e.rawX; touchY = e.rawY
                    dragging = false; longPressed = false
                    main.postDelayed(longPress, 600)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        main.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        try { manager.updateViewLayout(v, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (dragging) {
                        clampToScreen()
                        saveFractions()
                    } else if (!longPressed) {
                        nextStyle()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress); true
                }
                else -> false
            }
        }
        manager.addView(box, params)
        root = box
        label = tv
        graph = g
        box.post { placeFromFractions() }
    }

    private fun notification(): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("Monitor flotante"), NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_fg)
            .setContentTitle(tr("Monitor flotante activo"))
            .setContentText(tr("Toca el monitor para cambiar de diseño. Mantenlo presionado para abrir la app."))
            .setContentIntent(open)
            .addAction(0, tr("Cerrar"), stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val STYLES = 5
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val LABEL = 0xFFA8A8A8.toInt()
        private const val DIM = 0xFF5A5A5A.toInt()
        private const val ACCENT = 0xFF60CDFF.toInt()
        private const val GREEN = 0xFF6CCB5F.toInt()
        private const val AMBER = 0xFFFCE100.toInt()
        private const val RED = 0xFFFF6B6B.toInt()
        private const val CHANNEL = "overlay"
        private const val NOTIF_ID = 2001
        const val ACTION_STOP = "com.kevxngg.monitorroot.STOP_OVERLAY"
    }
}

/** Encender y apagar el monitor flotante. */
object OverlayControl {
    /** Con root se concede el permiso de "mostrar sobre otras apps" sin ir a Ajustes. */
    fun ensurePermission(ctx: Context): Boolean {
        if (Settings.canDrawOverlays(ctx)) return true
        if (RootShell.open()) RootShell.run("appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW allow")
        return Settings.canDrawOverlays(ctx)
    }

    fun openPermissionSettings(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }

    /** Debe llamarse fuera del hilo principal (puede usar root). */
    fun start(ctx: Context): Boolean {
        if (!ensurePermission(ctx)) return false
        val i = Intent(ctx, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        return true
    }

    fun stop(ctx: Context) {
        ctx.stopService(Intent(ctx, OverlayService::class.java))
        OverlayState.running = false
    }
}

/** Actividad invisible: enciende o apaga el monitor (la usa el botón de ajustes rápidos). */
class OverlayToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext
        Thread {
            if (OverlayState.running) OverlayControl.stop(app)
            else if (!OverlayControl.start(app)) OverlayControl.openPermissionSettings(app)
            TileService.requestListeningState(app, ComponentName(app, MonitorTileService::class.java))
        }.start()
        finish()
    }
}

/** Botón de ajustes rápidos "Monitor flotante". */
class MonitorTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        Prefs.init(applicationContext)
        I18n.init(applicationContext)
        tile.state = if (OverlayState.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = tr("Monitor flotante")
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (OverlayState.running) tr("Activado") else tr("Desactivado")
        tile.icon = Icon.createWithResource(this, R.drawable.ic_fg)
        tile.updateTile()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (OverlayState.running) {
            OverlayControl.stop(this)
            refresh()
            return
        }
        // Se abre la actividad invisible: Android permite iniciar el servicio desde ella
        val intent = Intent(this, OverlayToggleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
