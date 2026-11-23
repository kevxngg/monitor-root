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

/** State visible from the UI (Settings and the quick settings tile). */
object OverlayState {
    var running by mutableStateOf(false)
}

/** Lightweight readings for the floating monitor and the widget. */
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
        // Extra data for the large panel (all optional)
        val cpuMhz: Int? = null, val gpuMhz: Int? = null,
        val ramUsedKb: Long? = null, val ramTotalKb: Long? = null,
        val netUpKbs: Float? = null, val netDownKbs: Float? = null,
        val stUsedKb: Long? = null, val stTotalKb: Long? = null,
    )

    private var prevRx = -1L
    private var prevTx = -1L
    private var prevNetTime = 0L

    private val NET_IFACE = Regex("^(wlan\\d+|eth\\d+|rmnet_data\\d+|rmnet\\d+|ccmni\\d+|seth_lte\\d+|wwan\\d+)$")

    private fun zones(): List<String> {
        cpuZones?.let { return it }
        val found = RootShell.run("grep -H . /sys/class/thermal/thermal_zone*/type").mapNotNull { l ->
            val i = l.indexOf(':')
            if (i > 0 && CPU_ZONE.containsMatchIn(l.substring(i + 1))) l.substring(0, i).removeSuffix("/type") else null
        }
        // An empty answer (shell reconnecting) is not cached: it is tried again on the next reading
        if (found.isNotEmpty()) cpuZones = found
        return found
    }

    private fun cpuFrom(line: String?): Pair<Long, Long>? {
        val n = line?.trim()?.split(WS)?.drop(1)?.mapNotNull { it.toLongOrNull() } ?: return null
        if (n.size < 5) return null
        return n.take(8).sum() to (n[3] + n[4])
    }

    // ---- FPS of the foreground app ----
    private val FOCUS = Regex("mCurrentFocus=Window\\{\\S+ u\\d+ ([\\w.]+)/")
    private var layer: String? = null
    private var layerCheckedAt = 0L

    /** Finds the SurfaceFlinger layer the focused app draws to (every 4 s). */
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
        val layers = RootShell.run("dumpsys SurfaceFlinger --list").map { layerName(it.trim()) }.filter { it.contains(pkg) }
        layer = layers.firstOrNull { it.contains("SurfaceView[") && it.contains("BLAST") }
            ?: layers.firstOrNull { it.contains("SurfaceView[") && !it.startsWith("Background") }
            ?: layers.firstOrNull { it.contains("BLAST") }
            ?: layers.firstOrNull()
        return layer
    }

    /**
     * Name to pass to --latency. Since Android 14 the list shows each layer as
     * "RequestedLayerState{c5ea7fb SurfaceView[...](BLAST)#9085 parentId=9084}", and --latency only
     * accepts the part with the id ("c5ea7fb SurfaceView[...](BLAST)#9085").
     */
    private fun layerName(line: String): String =
        if (!line.startsWith("RequestedLayerState{")) line
        else line.removePrefix("RequestedLayerState{").removeSuffix("}").substringBefore(" parentId=").substringBefore(" z=").trim()

    /** Counts the frames the layer displayed in the last second. */
    private fun layerFps(): Int? {
        val name = currentLayer() ?: return null
        val lines = RootShell.run("dumpsys SurfaceFlinger --latency ${Actions.q(name)}")
        if (lines.size < 3) return null
        val times = lines.drop(1).mapNotNull { l -> l.trim().split(WS).getOrNull(1)?.toLongOrNull() }
            .filter { it > 0 && it != Long.MAX_VALUE }
        if (times.size < 2) return null
        // Timestamps use the same monotonic clock as System.nanoTime()
        val since = System.nanoTime() - 1_000_000_000L
        return times.count { it > since }.coerceAtMost(240)
    }

    // ---- SurfaceFlinger TimeStats: frames shown by the display ----
    private var tsReady = false
    private var tsLast = 0L
    private var tsWorks = true
    private var tsFails = 0

    fun startTimeStats() {
        RootShell.run("dumpsys SurfaceFlinger --timestats -enable -clear")
        tsReady = true
        tsWorks = true
        tsLast = SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun stopTimeStats() {
        RootShell.run("dumpsys SurfaceFlinger --timestats -disable")
        tsReady = false
    }

    /** Reads the total frames since the last read and resets it. */
    private fun timeStatsFrames(): Long? {
        // -enable first: if something else turned TimeStats off (another tool, a SurfaceFlinger restart),
        // the totals would stay frozen and the FPS would read 0 or disappear
        val out = RootShell.run(
            "dumpsys SurfaceFlinger --timestats -enable >/dev/null; dumpsys SurfaceFlinger --timestats -dump; dumpsys SurfaceFlinger --timestats -clear"
        )
        // The first "totalFrames" (before the layers) is the whole display's
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
            // One empty answer can be the shell reconnecting: it is only abandoned after several in a row
            if (++tsFails >= 5) tsWorks = false // this phone has no TimeStats: the other methods are used
            tsReady = false
            return null
        }
        tsFails = 0
        if (dt < 0.3f) return null
        return (frames / dt).toInt().coerceIn(0, 240)
    }

    /** Battery temperature (°C) and level from "path:value" lines; a missing file never shifts the other. */
    fun parseBattery(lines: List<String>): Pair<Float?, Int?> {
        fun value(name: String) = lines.firstOrNull { it.substringBefore(':').endsWith("/$name") }?.substringAfter(':')?.trim()
        return value("temp")?.toFloatOrNull()?.div(10f) to value("capacity")?.toIntOrNull()
    }

    /** Tests each method separately (for Settings > Test FPS). */
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

    /** [fps] = true also measures the display's frames per second. */
    @Synchronized
    /** [ctx] lets battery be filled in from the Android API if /sys is blocked (Shizuku). */
    fun read(fps: Boolean, ctx: Context? = null): Data {
        val z = zones()
        val script = buildString {
            append("head -n 1 /proc/stat; echo @@; ")
            append("grep -H . /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage /sys/kernel/gpu/gpu_busy 2>/dev/null; ")
            // "read" is a shell builtin: no extra process per sample
            append("if [ -e /sys/kernel/ged/hal/gpu_utilization ]; then for i in 1 2 3 4; do u=; read u r < /sys/kernel/ged/hal/gpu_utilization; echo ged:${'$'}u; sleep 0.04; done; ")
            append("l=; read l r < /sys/module/ged/parameters/gpu_loading 2>/dev/null && echo gl:${'$'}l; fi; echo @@; ")
            append("grep -E '^(MemTotal|MemAvailable):' /proc/meminfo; echo @@; ")
            append("cat /sys/devices/system/cpu/cpufreq/policy*/scaling_cur_freq 2>/dev/null; echo @@; ")
            append("cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq /sys/class/kgsl/kgsl-3d0/gpuclk /sys/kernel/ged/hal/current_freqency 2>/dev/null | head -n 1; ")
            append("for d in /sys/class/devfreq/*; do case \"${'$'}d\" in *mali*|*gpu*|*GPU*|*g3d*) cat \"${'$'}d/cur_freq\" 2>/dev/null; break;; esac; done; echo @@; ")
            append("cat /proc/net/dev; echo @@; ")
            append("echo @@; ")  // (formerly df: storage is now read with StatFs)
            append("grep -H . /sys/class/power_supply/battery/temp /sys/class/power_supply/battery/capacity 2>/dev/null; echo @@; ")
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
        // Take the highest of the sources (on MediaTek, gpu_utilization often reads 0 and gpu_loading is the real one)
        // On MediaTek the GPU works in bursts between frames: a single reading is often 0 % even
        // while playing, so several quick samples are averaged
        val gpuLines = parts.getOrNull(1)?.lines().orEmpty()
        val ged = gpuLines.filter { it.startsWith("ged:") }.mapNotNull { it.removePrefix("ged:").trim().toIntOrNull() }
        // gpu_utilization reads 0 on many kernels: gpu_loading counts too (same rule as the main page)
        val loading = gpuLines.firstOrNull { it.startsWith("gl:") }?.removePrefix("gl:")?.trim()?.toIntOrNull()
        val gpu = (if (ged.isNotEmpty()) maxOf(ged.average().toInt(), loading ?: 0) else gpuLines.filter { !it.startsWith("gl:") }.mapNotNull { l ->
            Regex("\\d+").find(l.substringAfter(':'))?.value?.toIntOrNull()
        }.maxOrNull())?.coerceIn(0, 100)
        // RAM
        val mem = parts.getOrNull(2)?.lines().orEmpty().associate { l ->
            l.substringBefore(':') to (l.split(WS).getOrNull(1)?.toLongOrNull() ?: 0L)
        }
        val total = mem["MemTotal"] ?: 0L
        val ram = if (total > 0) ((total - (mem["MemAvailable"] ?: 0L)) * 100 / total).toInt() else null
        // CPU frequency (highest of the clusters, in MHz)
        val cpuMhz = parts.getOrNull(3)?.lines().orEmpty().mapNotNull { it.trim().toLongOrNull() }
            .maxOrNull()?.let { (it / 1000).toInt() }
        // GPU frequency (kHz or Hz depending on the driver)
        // GPU frequency: the first line with data. On MediaTek it has two numbers
        // ("level frequency"), so the larger one is taken, same as in the main app.
        val gpuMhz = parts.getOrNull(4)?.lines().orEmpty()
            .firstNotNullOfOrNull { l -> l.trim().split(WS).mapNotNull { it.toLongOrNull() }.maxOrNull()?.takeIf { it > 0 } }
            ?.let { when { it > 10_000_000 -> (it / 1_000_000).toInt(); it > 10_000 -> (it / 1000).toInt(); else -> it.toInt() } }
        // Network: rx/tx byte difference between readings → KB/s
        var netUp: Float? = null; var netDown: Float? = null
        run {
            var rx = 0L; var tx = 0L
            parts.getOrNull(5)?.lines().orEmpty().forEach { l ->
                // Only the real interfaces: VPN, tethering and virtual ones carry the same traffic twice
                if (l.contains(':') && NET_IFACE.matches(l.substringBefore(':').trim())) {
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
        // Storage via the Android API: exact and independent of df's output format
        val stat = try { android.os.StatFs(android.os.Environment.getDataDirectory().path) } catch (e: Exception) { null }
        val stTotal = stat?.totalBytes?.div(1024)?.takeIf { it > 0 }
        val stUsed = stat?.let { (it.totalBytes - it.availableBytes) / 1024 }
        // Battery
        var (battTemp, battLevel) = parseBattery(parts.getOrNull(7)?.lines().orEmpty())
        if ((battTemp == null || battLevel == null) && ctx != null) {
            val api = AndroidStats.battery(ctx)
            battTemp = battTemp ?: api.tempC
            battLevel = battLevel ?: api.level
        }
        // CPU temperature (max of its sensors, discarding impossible values)
        val cpuTemp = parts.getOrNull(8)?.lines().orEmpty().mapNotNull { it.trim().toFloatOrNull() }
            .map { if (abs(it) >= 1000f) it / 1000f else it }
            .filter { it > 0f && it < 125f }
            .maxOrNull()
            ?: AndroidStats.cpuTemp()   // without /sys (Shizuku): system temperature service
        // Display FPS. First the SurfaceFlinger statistics (TimeStats),
        // which count the frames the display actually shows; if they are not
        // available, the other methods are used.
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

/** Small translucent bubble the panel minimizes to; it snaps to the edges. */
class BubbleView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((44 * d).toInt(), (44 * d).toInt())
    override fun onDraw(c: Canvas) {
        val r = width / 2f
        // Monitor logo, slightly translucent so it doesn't cover what's below
        drawAppLogo(c, context, r, r, r - 2 * d, alpha = 0xE0)
        p.style = Paint.Style.STROKE
        p.strokeWidth = (if (DiabloMode.active) 2.5f else 1.5f) * d
        p.color = if (DiabloMode.active) 0xFFFF4D4F.toInt() else 0x99FFFFFF.toInt()
        c.drawCircle(r, r, r - 1.5f * d, p)
    }
}

/** Floating window: panel with 4 styles, resizable and minimizable to a bubble. */
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
            val power = getSystemService(android.os.PowerManager::class.java)
            var screenWasOn = true
            while (alive) {
                // With the screen off nobody sees the panel: no readings, and SurfaceFlinger stops counting frames
                if (power?.isInteractive == false) {
                    if (screenWasOn) try { QuickStats.stopTimeStats() } catch (_: Exception) {}
                    screenWasOn = false
                    SystemClock.sleep(5000)
                    continue
                }
                screenWasOn = true
                // FPS only while the panel is shown (the minimized bubble does not show them)
                val d = try { if (RootShell.open()) QuickStats.read(fps = panel != null, ctx = applicationContext) else null } catch (_: Exception) { null }
                last = d
                main.post {
                    bubble?.invalidate()  // the red ring follows the Diablo Mode state
                    panel?.let { v ->
                        // Layout or transparency changed in Settings: applied without restarting the service
                        val st = Prefs.panelStyle.coerceIn(0, 3)
                        val a = alpha()
                        if (v.style != st || v.bgAlpha != a) {
                            v.style = st; v.bgAlpha = a
                            v.requestLayout(); v.invalidate()
                        }
                        v.data = d
                    }
                }
                // Nothing is shown while minimized: reading every 5 s saves battery
                SystemClock.sleep(if (panel == null) 5000 else 1000)
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
        main.postDelayed({ clampToScreen(); clampBubble() }, 150)
    }

    /** Keeps the bubble on screen and snapped to its edge (e.g. on rotation). */
    private fun clampBubble() {
        val v = bubble ?: return; val params = lp ?: return
        val (w, h) = screen()
        params.x = if (params.x + v.width / 2 < w / 2) 0 else (w - v.width).coerceAtLeast(0)
        params.y = params.y.coerceIn(0, (h - v.height).coerceAtLeast(0))
        try { wm?.updateViewLayout(v, params) } catch (_: Exception) {}
        prefs().edit().putInt("bubbleX", params.x).putInt("bubbleY", params.y).apply()
    }

    private fun prefs() = getSharedPreferences("prefs", Context.MODE_PRIVATE)
    private fun alpha() = when (prefs().getInt("ovOpacity", 1)) { 0 -> 0xF2; 2 -> 0x99; else -> 0xD8 }

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
                    // Bottom-right corner (~32dp) = resize
                    val corner = 36 * resources.displayMetrics.density
                    resizing = e.x > view.width - corner && e.y > view.height - corner
                    // Top-right corner (~36dp) = minimize
                    if (!resizing && e.x > view.width - corner && e.y < corner) {
                        main.post { minimize() }; return@setOnTouchListener true
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX; val dy = e.rawY - touchY
                    if (resizing) {
                        val factor = 1f + (dx + dy) / (400f * resources.displayMetrics.density)
                        // Never wider than the screen: the corner buttons would be out of reach
                        val base = if (v.scale > 0f) v.width / v.scale else 0f
                        val fit = if (base > 0f) (screen().first / base).coerceIn(0.7f, 1.5f) else 1.5f
                        v.scale = (startScale * factor).coerceIn(0.7f, fit)
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
                        // Single tap on the top-left corner of the content = change style
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

    // ---------- Bubble ----------
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
                        // Snap to the nearest edge (left or right)
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
        v.post { clampBubble() }
    }

    private fun minimize() { Prefs.panelMinimized = true; Prefs.save(); showBubble() }
    private fun expand() { Prefs.panelMinimized = false; Prefs.save(); showPanel() }

    // ---------- Position ----------
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
    /** With root, the "display over other apps" permission is granted without going to Settings. */
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

    /** Must be called off the main thread (may use root). */
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

/** Invisible activity: turns the monitor on or off (used by the quick settings tile). */
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

/** Quick settings tile "Monitor flotante" (floating monitor). */
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
        // Opens the invisible activity: Android allows starting the service from it
        val intent = Intent(this, OverlayToggleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
