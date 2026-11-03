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
            if (fps) append("cat /sys/class/drm/sde-crtc-0/measured_fps; echo @@; service call SurfaceFlinger 1013; ")
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
        // FPS: se prueban tres métodos y se usa el mayor valor válido.
        //  1) cuadros de la capa del juego en primer plano (el más preciso)
        //  2) cuadros que compone SurfaceFlinger en toda la pantalla
        //  3) contador del panel (solo Qualcomm), si da más de 0
        var fpsValue: Int? = null
        if (fps) {
            val measured = parts.getOrNull(5)?.let { Regex("fps:\\s*([\\d.]+)").find(it)?.groupValues?.get(1)?.toFloatOrNull()?.toInt() }
                ?.takeIf { it > 0 }
            val flips = parts.getOrNull(6)?.let { Regex("Parcel\\(([0-9a-fA-F]+)").find(it)?.groupValues?.get(1)?.toLongOrNull(16) }
            val now = SystemClock.elapsedRealtime()
            var flipFps: Int? = null
            if (flips != null && prevFlips >= 0 && now > prevFlipTime && flips >= prevFlips) {
                flipFps = ((flips - prevFlips) * 1000 / (now - prevFlipTime)).toInt().coerceIn(0, 240)
            }
            if (flips != null) {
                prevFlips = flips; prevFlipTime = now
            }
            fpsValue = listOfNotNull(layerFps(), flipFps, measured).maxOrNull()
        }
        return Data(cpu, cpuTemp, gpu, ram, battTemp, battLevel, fpsValue)
    }
}

/** Ventana flotante con CPU, GPU, RAM, temperaturas y FPS encima de cualquier app. */
class OverlayService : Service() {
    private var wm: WindowManager? = null
    private var label: TextView? = null
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
                val horizontal = getSharedPreferences("prefs", Context.MODE_PRIVATE).getInt("ovStyle", 0) == 1
                main.post {
                    label?.let { tv ->
                        applyLook(tv)
                        tv.text = build(d, horizontal)
                    }
                }
                SystemClock.sleep(1000)
            }
        }.start()
    }

    override fun onDestroy() {
        alive = false
        OverlayState.running = false
        label?.let { v -> try { wm?.removeView(v) } catch (_: Exception) {} }
        label = null
        TileService.requestListeningState(this, ComponentName(this, MonitorTileService::class.java))
        super.onDestroy()
    }

    // Colores según la carga: verde, amarillo o rojo
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

    private fun build(d: QuickStats.Data?, horizontal: Boolean): CharSequence {
        val sb = SpannableStringBuilder()
        fun span(text: String, color: Int, vararg extra: Any) {
            val start = sb.length
            sb.append(text)
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            extra.forEach { sb.setSpan(it, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        if (d == null) {
            span("Monitor Root  ", WHITE)
            span(tr("Sin acceso root"), RED)
            return sb
        }
        fun pct(v: Int?) = (v?.let { "$it%" } ?: "—").padEnd(4)
        fun deg(t: Float?) = t?.let { "%.0f°".format(it) } ?: ""
        val sep = if (horizontal) "   " else "\n"
        // FPS destacado
        span((d.fps?.toString() ?: "—").padStart(if (horizontal) 1 else 3), ACCENT, RelativeSizeSpan(if (horizontal) 1.25f else 1.7f), StyleSpan(Typeface.BOLD))
        span(" FPS", LABEL)
        val rows = listOf(
            Triple("CPU", d.cpu, d.cpuTemp),
            Triple("GPU", d.gpu, null),
            Triple("RAM", d.ram, null),
            Triple("BAT", d.battLevel, d.battTemp),
        )
        rows.forEach { (name, v, t) ->
            sb.append(sep)
            span("$name ", LABEL)
            // La batería no es "carga": siempre en blanco
            span(pct(v), if (name == "BAT") WHITE else loadColor(v))
            if (t != null) span(" " + deg(t), tempColor(t))
        }
        return sb
    }

    /** Aplica estilo, tamaño y opacidad elegidos en Configuración. */
    private fun applyLook(tv: TextView) {
        val p = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val size = p.getInt("ovSize", 1)
        val opacity = p.getInt("ovOpacity", 1)
        val dp = resources.displayMetrics.density
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, when (size) { 0 -> 9.5f; 2 -> 13.5f; else -> 11f })
        val alpha = when (opacity) { 0 -> 0x80; 2 -> 0xF0; else -> 0xC0 }
        (tv.background as? GradientDrawable)?.setColor((alpha shl 24) or 0x161616)
        val pad = when (size) { 0 -> 7; 2 -> 12; else -> 9 }
        tv.setPadding((pad * 1.3f * dp).toInt(), (pad * dp).toInt(), (pad * 1.3f * dp).toInt(), (pad * dp).toInt())
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addView() {
        val manager = getSystemService(WindowManager::class.java)
        wm = manager
        val dp = resources.displayMetrics.density
        val tv = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setLineSpacing(0f, 1.1f)
            setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 14 * dp
                setColor(0xC0161616.toInt())
                setStroke((1 * dp).toInt(), 0x6660CDFF)
            }
            setShadowLayer(2f, 0f, 1f, 0xAA000000.toInt())
            text = "Monitor Root"
        }
        val sp = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = sp.getInt("overlayX", (16 * dp).toInt())
            y = sp.getInt("overlayY", (120 * dp).toInt())
        }
        // Se arrastra con el dedo; la posición se recuerda
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y; touchX = e.rawX; touchY = e.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - touchX).toInt()
                    lp.y = startY + (e.rawY - touchY).toInt()
                    try { manager.updateViewLayout(v, lp) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    sp.edit().putInt("overlayX", lp.x).putInt("overlayY", lp.y).apply(); true
                }
                else -> false
            }
        }
        manager.addView(tv, lp)
        label = tv
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
            .setContentText(tr("Toca Cerrar para ocultarlo."))
            .setContentIntent(open)
            .addAction(0, tr("Cerrar"), stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val LABEL = 0xFFA8A8A8.toInt()
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
