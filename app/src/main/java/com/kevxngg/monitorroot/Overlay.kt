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
        // Datos extra para el panel grande (todos opcionales)
        val cpuMhz: Int? = null, val gpuMhz: Int? = null,
        val ramUsedKb: Long? = null, val ramTotalKb: Long? = null,
        val netUpKbs: Float? = null, val netDownKbs: Float? = null,
        val stUsedKb: Long? = null, val stTotalKb: Long? = null,
    )

    private var prevRx = -1L
    private var prevTx = -1L
    private var prevNetTime = 0L

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
            append("cat /sys/devices/system/cpu/cpufreq/policy*/scaling_cur_freq 2>/dev/null; echo @@; ")
            append("cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq /sys/class/kgsl/kgsl-3d0/gpuclk /sys/kernel/ged/hal/current_freqV2 2>/dev/null | head -n 1; echo @@; ")
            append("cat /proc/net/dev; echo @@; ")
            append("df -k /data | tail -n 1; echo @@; ")
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
        // Frecuencia de la CPU (la más alta de los clústeres, en MHz)
        val cpuMhz = parts.getOrNull(3)?.lines().orEmpty().mapNotNull { it.trim().toLongOrNull() }
            .maxOrNull()?.let { (it / 1000).toInt() }
        // Frecuencia de la GPU (kHz o Hz según el driver)
        val gpuMhz = parts.getOrNull(4)?.lines()?.firstOrNull()?.trim()?.toLongOrNull()?.let {
            when { it > 10_000_000 -> (it / 1_000_000).toInt(); it > 10_000 -> (it / 1000).toInt(); else -> it.toInt() }
        }
        // Red: diferencia de bytes rx/tx entre lecturas → KB/s
        var netUp: Float? = null; var netDown: Float? = null
        run {
            var rx = 0L; var tx = 0L
            parts.getOrNull(5)?.lines().orEmpty().forEach { l ->
                if (l.contains(':') && !l.contains("lo:")) {
                    val f = l.substringAfter(':').trim().split(WS)
                    rx += f.getOrNull(0)?.toLongOrNull() ?: 0L
                    tx += f.getOrNull(8)?.toLongOrNull() ?: 0L
                }
            }
            val now = SystemClock.elapsedRealtime()
            if (prevRx >= 0 && now > prevNetTime) {
                val dt = (now - prevNetTime) / 1000f
                netDown = ((rx - prevRx) / 1024f / dt).coerceAtLeast(0f)
                netUp = ((tx - prevTx) / 1024f / dt).coerceAtLeast(0f)
            }
            prevRx = rx; prevTx = tx; prevNetTime = now
        }
        // Almacenamiento (df -k /data): total y usado en KB
        val df = parts.getOrNull(6)?.lines()?.firstOrNull()?.trim()?.split(WS)
        val stTotal = df?.getOrNull(1)?.toLongOrNull()
        val stUsed = df?.getOrNull(2)?.toLongOrNull()
        // Batería
        val batt = parts.getOrNull(7)?.lines().orEmpty()
        val battTemp = batt.getOrNull(0)?.trim()?.toFloatOrNull()?.div(10f)
        val battLevel = batt.getOrNull(1)?.trim()?.toIntOrNull()
        // Temperatura de la CPU (máxima de sus sensores, descartando valores imposibles)
        val cpuTemp = parts.getOrNull(8)?.lines().orEmpty().mapNotNull { it.trim().toFloatOrNull() }
            .map { if (abs(it) >= 1000f) it / 1000f else it }
            .filter { it > 0f && it < 125f }
            .maxOrNull()
        // FPS de la pantalla. Primero las estadísticas de SurfaceFlinger (TimeStats),
        // que cuentan los cuadros que la pantalla muestra realmente; si no están
        // disponibles, se usan los otros métodos.
        var fpsValue: Int? = null
        if (fps) {
            val measured = parts.getOrNull(9)?.let { txt ->
                Regex("fps:\\s*([\\d.]+)").findAll(txt).mapNotNull { it.groupValues[1].toFloatOrNull()?.toInt() }.maxOrNull()
            }?.takeIf { it > 0 }
            val flips = parts.getOrNull(10)?.let { Regex("Parcel\\(([0-9a-fA-F]+)").find(it)?.groupValues?.get(1)?.toLongOrNull(16) }
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
        return Data(
            cpu, cpuTemp, gpu, ram, battTemp, battLevel, fpsValue,
            cpuMhz = cpuMhz, gpuMhz = gpuMhz,
            ramUsedKb = if (total > 0) total - (mem["MemAvailable"] ?: 0L) else null,
            ramTotalKb = total.takeIf { it > 0 },
            netUpKbs = netUp, netDownKbs = netDown,
            stUsedKb = stUsed?.let { it }, stTotalKb = stTotal,
        )
    }
}

/** Burbuja pequeña y translúcida a la que se minimiza el panel; se pega a los bordes. */
class BubbleView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((44 * d).toInt(), (44 * d).toInt())
    override fun onDraw(c: Canvas) {
        val r = width / 2f
        p.style = Paint.Style.FILL; p.color = 0xCC0E1420.toInt()
        c.drawCircle(r, r, r - d, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = 0xFF3FA9FF.toInt()
        c.drawCircle(r, r, r - d, p)
        // "M" de Monitor
        p.style = Paint.Style.FILL; p.color = 0xFF3FA9FF.toInt()
        p.textAlign = Paint.Align.CENTER; p.textSize = 20 * d; p.typeface = Typeface.DEFAULT_BOLD
        c.drawText("M", r, r + 7 * d, p)
    }
}

/** Ventana flotante: panel con 4 estilos, redimensionable y minimizable a una burbuja. */
class OverlayService : Service() {
    private var wm: WindowManager? = null
    private var panel: PanelView? = null
    private var bubble: BubbleView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var last: QuickStats.Data? = null
    @Volatile private var alive = false
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
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
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        if (Prefs.panelMinimized) showBubble() else showPanel()
        alive = true
        OverlayState.running = true
        Thread {
            while (alive) {
                val d = try { if (RootShell.open()) QuickStats.read(fps = true) else null } catch (_: Exception) { null }
                last = d
                main.post { panel?.data = d }
                SystemClock.sleep(1000)
            }
            try { QuickStats.stopTimeStats() } catch (_: Exception) {}
        }.start()
    }

    override fun onDestroy() {
        alive = false
        OverlayState.running = false
        removeViews()
        TileService.requestListeningState(this, ComponentName(this, MonitorTileService::class.java))
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        main.postDelayed({ clampToScreen() }, 150)
    }

    private fun prefs() = getSharedPreferences("prefs", Context.MODE_PRIVATE)
    private fun alpha() = when (prefs().getInt("ovOpacity", 1)) { 0 -> 0x99; 2 -> 0xF2; else -> 0xD8 }

    private fun removeViews() {
        panel?.let { try { wm?.removeView(it) } catch (_: Exception) {} }; panel = null
        bubble?.let { try { wm?.removeView(it) } catch (_: Exception) {} }; bubble = null
    }

    private fun baseParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    // ---------- Panel ----------
    @SuppressLint("ClickableViewAccessibility")
    private fun showPanel() {
        removeViews()
        val manager = getSystemService(WindowManager::class.java); wm = manager
        val v = PanelView(this).apply {
            scale = Prefs.panelScale.coerceIn(0.7f, 1.5f)
            style = Prefs.panelStyle.coerceIn(0, 3)
            bgAlpha = alpha()
            data = last
        }
        val params = baseParams()
        lp = params
        placeFromFractions(params, v)
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        var dragging = false; var resizing = false; var startScale = 1f
        v.setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y; touchX = e.rawX; touchY = e.rawY
                    dragging = false; startScale = v.scale
                    // Esquina inferior derecha (~32dp) = redimensionar
                    val corner = 36 * resources.displayMetrics.density
                    resizing = e.x > view.width - corner && e.y > view.height - corner
                    // Esquina superior derecha (~36dp) = minimizar
                    if (!resizing && e.x > view.width - corner && e.y < corner) {
                        minimize(); return@setOnTouchListener true
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX; val dy = e.rawY - touchY
                    if (resizing) {
                        val factor = 1f + (dx + dy) / (400f * resources.displayMetrics.density)
                        v.scale = (startScale * factor).coerceIn(0.7f, 1.5f)
                        v.requestLayout()
                    } else {
                        if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                        if (dragging) {
                            params.x = startX + dx.toInt(); params.y = startY + dy.toInt()
                            try { manager.updateViewLayout(view, params) } catch (_: Exception) {}
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (resizing) { Prefs.panelScale = v.scale; Prefs.save() }
                    else if (dragging) { clampToScreen(); saveFractions() }
                    else {
                        // Toque simple en la esquina superior izquierda del contenido = cambiar estilo
                        if (e.x < 120 * resources.displayMetrics.density && e.y < 40 * resources.displayMetrics.density) nextStyle()
                    }
                    resizing = false
                    true
                }
                else -> false
            }
        }
        manager.addView(v, params)
        panel = v
        v.post { clampToScreen() }
    }

    private fun nextStyle() {
        val next = (Prefs.panelStyle + 1) % 4
        Prefs.panelStyle = next; Prefs.save()
        panel?.let { it.style = next; it.requestLayout() }
    }

    // ---------- Burbuja ----------
    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        removeViews()
        val manager = getSystemService(WindowManager::class.java); wm = manager
        val v = BubbleView(this)
        val params = baseParams()
        lp = params
        val m = resources.displayMetrics
        params.x = prefs().getInt("bubbleX", 0)
        params.y = prefs().getInt("bubbleY", (m.heightPixels * 0.3f).toInt())
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f; var dragging = false
        v.setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { startX = params.x; startY = params.y; touchX = e.rawX; touchY = e.rawY; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX; val dy = e.rawY - touchY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) { params.x = startX + dx.toInt(); params.y = startY + dy.toInt(); try { manager.updateViewLayout(view, params) } catch (_: Exception) {} }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        // Pegar al borde más cercano (izquierda o derecha)
                        val maxX = m.widthPixels - view.width
                        params.x = if (params.x + view.width / 2 < m.widthPixels / 2) 0 else maxX
                        params.y = params.y.coerceIn(0, m.heightPixels - view.height)
                        try { manager.updateViewLayout(view, params) } catch (_: Exception) {}
                        prefs().edit().putInt("bubbleX", params.x).putInt("bubbleY", params.y).apply()
                    } else expand()
                    true
                }
                else -> false
            }
        }
        manager.addView(v, params)
        bubble = v
    }

    private fun minimize() { Prefs.panelMinimized = true; Prefs.save(); showBubble() }
    private fun expand() { Prefs.panelMinimized = false; Prefs.save(); showPanel() }

    // ---------- Posición ----------
    private fun screen(): Pair<Int, Int> {
        val m = resources.displayMetrics; return m.widthPixels to m.heightPixels
    }

    private fun clampToScreen() {
        val v = panel ?: return; val params = lp ?: return
        val (w, h) = screen()
        params.x = params.x.coerceIn(0, (w - v.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (h - v.height).coerceAtLeast(0))
        try { wm?.updateViewLayout(v, params) } catch (_: Exception) {}
    }

    private fun saveFractions() {
        val v = panel ?: return; val params = lp ?: return
        val (w, h) = screen()
        prefs().edit()
            .putFloat("panelFx", if (w - v.width > 0) params.x.toFloat() / (w - v.width) else 0f)
            .putFloat("panelFy", if (h - v.height > 0) params.y.toFloat() / (h - v.height) else 0f)
            .apply()
    }

    private fun placeFromFractions(params: WindowManager.LayoutParams, v: View) {
        val (w, h) = screen()
        v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        params.x = (prefs().getFloat("panelFx", 0.04f) * (w - v.measuredWidth).coerceAtLeast(0)).toInt()
        params.y = (prefs().getFloat("panelFy", 0.1f) * (h - v.measuredHeight).coerceAtLeast(0)).toInt()
    }

    private fun notification(): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("Monitor flotante"), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_fg)
            .setContentTitle(tr("Monitor flotante activo"))
            .setContentText(tr("Toca la esquina para cambiar de diseño; minimiza con la esquina superior."))
            .setContentIntent(open)
            .addAction(0, tr("Cerrar"), stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "overlay"
        private const val NOTIF_ID = 2001
        const val ACTION_STOP = "com.kevxngg.monitorroot.STOP_OVERLAY"
    }
}

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
