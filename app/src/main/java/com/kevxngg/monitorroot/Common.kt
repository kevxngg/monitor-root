package com.kevxngg.monitorroot

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.core.graphics.drawable.toBitmap

const val HISTORY = 60

// ===================== TEMA (estilo Windows 11) =====================

data class Win(
    val dark: Boolean,
    val mica: Color,
    val content: Color,
    val card: Color,
    val text: Color,
    val sub: Color,
    val border: Color,
    val divider: Color,
    val hover: Color,
    val select: Color,
    val accent: Color,
    val heatLow: Color,
    val heatMid: Color,
    val heatHigh: Color,
    val eco: Color,
    val warn: Color,
    /** Redondez de las esquinas (1 = Windows 11, 0 = macOS). */
    val radiusScale: Float = 1f,
    /** true cuando el estilo macOS está activo (pestañas arriba, barra de totales abajo). */
    val mac: Boolean = false,
) {
    /** Color de celda según el uso (0..1): azul claro → azul → naranja. */
    fun heat(f: Float): Color {
        val x = f.coerceIn(0f, 1f)
        return if (x <= 0.75f) lerp(heatLow, heatMid, x / 0.75f) else lerp(heatMid, heatHigh, (x - 0.75f) / 0.25f)
    }
}

val LightWin = Win(
    dark = false,
    mica = Color(0xFFEEF2F7),
    content = Color(0xFFF9F9F9),
    card = Color(0xFFFFFFFF),
    text = Color(0xFF1B1B1B),
    sub = Color(0xFF616161),
    border = Color(0xFFE3E3E3),
    divider = Color(0xFFEBEBEB),
    hover = Color(0xFFE6E8EB),
    select = Color(0xFFCDE6F7),
    accent = Color(0xFF005FB8),
    heatLow = Color(0xFFE5F2FB),
    heatMid = Color(0xFFA9D6F5),
    heatHigh = Color(0xFFF6C38F),
    eco = Color(0xFF0F7B0F),
    warn = Color(0xFFB3800A),
)

val DarkWin = Win(
    dark = true,
    mica = Color(0xFF1C1C1C),
    content = Color(0xFF272727),
    card = Color(0xFF2D2D2D),
    text = Color(0xFFFFFFFF),
    sub = Color(0xFFA8A8A8),
    border = Color(0xFF3A3A3A),
    divider = Color(0xFF333333),
    hover = Color(0xFF333333),
    select = Color(0xFF1E4057),
    accent = Color(0xFF60CDFF),
    heatLow = Color(0xFF16303F),
    heatMid = Color(0xFF1F5A80),
    heatHigh = Color(0xFF8A4B12),
    eco = Color(0xFF6CCB5F),
    warn = Color(0xFFFCE100),
)

val LocalWin = staticCompositionLocalOf { LightWin }
val LocalCompact = staticCompositionLocalOf { true }

enum class Res(val title: String, val light: Color, val dark: Color) {
    CPU("CPU", Color(0xFF0E7DB6), Color(0xFF3FB8CC)),
    MEM("Memoria", Color(0xFF5B48C2), Color(0xFF8C8FF0)),
    DISK("Disco 0", Color(0xFF3F8F0E), Color(0xFF6CCB5F)),
    WIFI("Wi‑Fi", Color(0xFFB4387F), Color(0xFFE37AC4)),
    MOBILE("Datos móviles", Color(0xFFB45F06), Color(0xFFF0A050)),
    GPU("GPU 0", Color(0xFF7B3FB8), Color(0xFFB58CF0)),
    TEMP("Batería", Color(0xFFC0392B), Color(0xFFF07060));

    fun color(w: Win) = if (w.dark) dark else light
}

// ===================== AJUSTES =====================

object Prefs {
    private var sp: SharedPreferences? = null

    var theme by mutableIntStateOf(0)        // 0 sistema, 1 claro, 2 oscuro, 3 negro puro
    var uiStyle by mutableIntStateOf(0)      // 0 Windows 11, 1 macOS
    var startPage by mutableIntStateOf(0)
    var speed by mutableIntStateOf(1)        // 0 alta, 1 normal, 2 baja, 3 pausa
    var groupByType by mutableStateOf(true)
    var showPackageNames by mutableStateOf(false)
    var efficiency by mutableStateOf(setOf<String>())
    var autoUpdate by mutableStateOf(true)
    var language by mutableStateOf("")       // "" = idioma del sistema
    var defaultGovernor by mutableStateOf("") // gobernador original de la CPU (modo equilibrado)
    var defaultGpuGovernor by mutableStateOf("")
    var overlayStyle by mutableIntStateOf(0)    // 0 vertical, 1 horizontal
    var overlaySize by mutableIntStateOf(1)     // 0 pequeño, 1 normal, 2 grande
    var overlayOpacity by mutableIntStateOf(1)  // 0 baja, 1 media, 2 alta

    // Modo juego
    var gameMode by mutableStateOf(false)
    var games by mutableStateOf(setOf<String>())
    var gamePower by mutableIntStateOf(1)       // 0 sin cambios, 1 rendimiento, 2 equilibrado, 3 eficiencia
    var gameOverlay by mutableStateOf(true)
    var gameKill by mutableStateOf(false)
    var gameDnd by mutableStateOf(false)
    var gameRecord by mutableStateOf(true)

    // Alertas
    var alertTemp by mutableStateOf(false)
    var alertTempLimit by mutableIntStateOf(1)  // 45, 50, 55, 60 °C
    var alertCpu by mutableStateOf(false)
    var alertCpuLimit by mutableIntStateOf(1)   // 25, 50, 75 %
    var alertCharge by mutableStateOf(false)
    var alertChargeLimit by mutableIntStateOf(0) // 80, 85, 90, 100 %

    // true si el usuario cambió a mano los límites de frecuencia de la CPU en Monitor
    var cpuCustomLimits by mutableStateOf(false)

    // Historial de aplicaciones: se cuenta desde aquí ("Eliminar historial de uso")
    var historyResetAt by mutableStateOf(0L)
    var skippedBuild by mutableIntStateOf(0)

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        sp = p
        theme = p.getInt("theme", 0)
        uiStyle = p.getInt("uiStyle", 0)
        startPage = p.getInt("startPage", 0)
        speed = p.getInt("speed", 1)
        groupByType = p.getBoolean("group", true)
        showPackageNames = p.getBoolean("pkgNames", false)
        efficiency = p.getStringSet("eff", emptySet())?.toSet() ?: emptySet()
        autoUpdate = p.getBoolean("autoUpdate", true)
        language = p.getString("lang", "") ?: ""
        defaultGovernor = p.getString("defaultGov", "") ?: ""
        defaultGpuGovernor = p.getString("defaultGpuGov", "") ?: ""
        overlayStyle = p.getInt("ovStyle", 0)
        overlaySize = p.getInt("ovSize", 1)
        overlayOpacity = p.getInt("ovOpacity", 1)
        gameMode = p.getBoolean("gmEnabled", false)
        games = p.getStringSet("gmGames", emptySet())?.toSet() ?: emptySet()
        gamePower = p.getInt("gmPower", 1)
        gameOverlay = p.getBoolean("gmOverlay", true)
        gameKill = p.getBoolean("gmKill", false)
        gameDnd = p.getBoolean("gmDnd", false)
        gameRecord = p.getBoolean("gmRecord", true)
        alertTemp = p.getBoolean("alTemp", false)
        alertTempLimit = p.getInt("alTempLimit", 1)
        alertCpu = p.getBoolean("alCpu", false)
        alertCpuLimit = p.getInt("alCpuLimit", 1)
        alertCharge = p.getBoolean("alCharge", false)
        alertChargeLimit = p.getInt("alChargeLimit", 0)
        historyResetAt = p.getLong("historyResetAt", 0L)
        cpuCustomLimits = p.getBoolean("cpuCustomLimits", false)
        skippedBuild = p.getInt("skipped", 0)
    }

    fun save() {
        sp?.edit()
            ?.putInt("theme", theme)
            ?.putInt("uiStyle", uiStyle)
            ?.putInt("startPage", startPage)
            ?.putInt("speed", speed)
            ?.putBoolean("group", groupByType)
            ?.putBoolean("pkgNames", showPackageNames)
            ?.putStringSet("eff", efficiency)
            ?.putBoolean("autoUpdate", autoUpdate)
            ?.putString("lang", language)
            ?.putString("defaultGov", defaultGovernor)
            ?.putString("defaultGpuGov", defaultGpuGovernor)
            ?.putInt("ovStyle", overlayStyle)
            ?.putInt("ovSize", overlaySize)
            ?.putInt("ovOpacity", overlayOpacity)
            ?.putBoolean("gmEnabled", gameMode)
            ?.putStringSet("gmGames", games)
            ?.putInt("gmPower", gamePower)
            ?.putBoolean("gmOverlay", gameOverlay)
            ?.putBoolean("gmKill", gameKill)
            ?.putBoolean("gmDnd", gameDnd)
            ?.putBoolean("gmRecord", gameRecord)
            ?.putBoolean("alTemp", alertTemp)
            ?.putInt("alTempLimit", alertTempLimit)
            ?.putBoolean("alCpu", alertCpu)
            ?.putInt("alCpuLimit", alertCpuLimit)
            ?.putBoolean("alCharge", alertCharge)
            ?.putInt("alChargeLimit", alertChargeLimit)
            ?.putLong("historyResetAt", historyResetAt)
            ?.putBoolean("cpuCustomLimits", cpuCustomLimits)
            ?.putInt("skipped", skippedBuild)
            ?.apply()
    }

    val TEMP_LIMITS = listOf(45, 50, 55, 60)
    val CPU_LIMITS = listOf(25, 50, 75)
    val CHARGE_LIMITS = listOf(80, 85, 90, 100)

    fun refreshMs(): Long = when (speed) {
        0 -> 500L
        2 -> 4000L
        3 -> -1L
        else -> 1000L
    }
}

// ===================== APPS (nombre e icono en caché) =====================

class AppMeta(
    val pkg: String,
    val label: String,
    val icon: ImageBitmap?,
    val launchable: Boolean,
    val system: Boolean,
    val uid: Int,
)

object Apps {
    private var pm: PackageManager? = null
    private val cache = HashMap<String, AppMeta?>()

    fun init(ctx: Context) {
        pm = ctx.packageManager
    }

    @Synchronized
    fun meta(pkg: String): AppMeta? {
        if (cache.containsKey(pkg)) return cache[pkg]
        val p = pm ?: return null
        val meta = try {
            @Suppress("DEPRECATION")
            val ai = p.getApplicationInfo(pkg, 0)
            val icon = try {
                p.getApplicationIcon(ai).toBitmap(64, 64).asImageBitmap()
            } catch (e: Exception) {
                null
            }
            AppMeta(
                pkg = pkg,
                label = p.getApplicationLabel(ai).toString(),
                icon = icon,
                launchable = p.getLaunchIntentForPackage(pkg) != null,
                system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                uid = ai.uid,
            )
        } catch (e: Exception) {
            null
        }
        cache[pkg] = meta
        return meta
    }

    fun launchablePackages(): List<String> {
        val p = pm ?: return emptyList()
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return p.queryIntentActivities(i, 0).map { it.activityInfo.packageName }.distinct()
    }

    fun packageForUid(uid: Int): String? = pm?.getPackagesForUid(uid)?.firstOrNull()

    fun installerLabel(pkg: String): String? {
        val p = pm ?: return null
        val inst = try {
            if (Build.VERSION.SDK_INT >= 30) p.getInstallSourceInfo(pkg).installingPackageName
            else p.getInstallerPackageName(pkg)
        } catch (e: Exception) {
            null
        } ?: return null
        return meta(inst)?.label ?: inst
    }

    fun pm(): PackageManager? = pm
}

fun openAppInfo(ctx: Context, pkg: String) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
    }
}

fun searchOnline(ctx: Context, q: String) {
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
    }
}

// ===================== HISTORIAL DE GRÁFICAS =====================

class History(private val m: Map<String, List<Float>> = emptyMap()) {
    operator fun get(k: String): List<Float> = m[k] ?: emptyList()

    fun add(s: Snapshot): History {
        val v = HashMap<String, Float>()
        v["cpu"] = s.cpuTotal
        v["mem"] = s.mem.usedPct
        v["gpu"] = (s.gpu.load ?: 0).toFloat()
        v["wrx"] = s.wifi.net.rxBps * 8f
        v["wtx"] = s.wifi.net.txBps * 8f
        v["mrx"] = (s.mobile?.rxBps ?: 0L) * 8f
        v["mtx"] = (s.mobile?.txBps ?: 0L) * 8f
        v["dact"] = s.disk?.activePct ?: 0f
        v["drd"] = (s.disk?.readBps ?: 0L).toFloat()
        v["dwr"] = (s.disk?.writeBps ?: 0L).toFloat()
        v["temp"] = s.mainTempC ?: 0f
        v["batt"] = (s.batt.level ?: 0).toFloat()
        s.cores.forEach { v["core${it.index}"] = it.usage }
        val n = HashMap(m)
        v.forEach { (k, value) -> n[k] = ((m[k] ?: emptyList()) + value).takeLast(HISTORY) }
        return History(n)
    }

}

// ===================== FORMATO =====================

fun formatKb(kb: Long): String = formatBytes(kb * 1024)

fun formatBytes(b: Long): String = when {
    b >= 1L shl 40 -> "%.1f TB".format(b / 1099511627776.0)
    b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
    b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

fun formatGb(kb: Long): String = "%.1f GB".format(kb / 1048576.0)

fun formatMBs(bps: Long): String = "%.1f MB/s".format(bps / 1048576.0)

fun formatRate(bps: Float): String = when {
    bps >= 1048576f -> "%.1f MB/s".format(bps / 1048576f)
    else -> "%.0f KB/s".format(bps / 1024f)
}

fun formatBits(bps: Float): String = when {
    bps >= 1e9f -> "%.1f Gbps".format(bps / 1e9f)
    bps >= 1e6f -> "%.1f Mbps".format(bps / 1e6f)
    bps >= 1e3f -> "%.0f Kbps".format(bps / 1e3f)
    else -> "%.0f bps".format(bps)
}

fun formatGhz(mhz: Int): String = "%.2f GHz".format(mhz / 1000f)

fun formatUptime(sec: Long): String =
    "%d:%02d:%02d:%02d".format(sec / 86400, (sec % 86400) / 3600, (sec % 3600) / 60, sec % 60)

fun formatDuration(sec: Long): String =
    if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, (sec % 3600) / 60, sec % 60)
    else "%d:%02d".format(sec / 60, sec % 60)

/** Escala "redonda" para gráficas de velocidad. */
fun niceScale(v: Float, min: Float = 100_000f): Float {
    // Un valor inválido (infinito o NaN) dejaría el bucle sin fin y congelaría la app
    if (!v.isFinite() || v <= min) return min
    var mag = min
    while (true) {
        for (k in floatArrayOf(1f, 2.5f, 5f)) {
            val s = mag * k
            if (s >= v) return s
        }
        mag *= 10f
    }
}

fun signalQuality(rssi: Int): String = when {
    rssi >= -50 -> tr("Excelente")
    rssi >= -60 -> tr("Buena")
    rssi >= -70 -> tr("Regular")
    else -> tr("Débil")
}

fun band(freqMhz: Int): String = when {
    freqMhz < 3000 -> "2,4 GHz"
    freqMhz < 5925 -> "5 GHz"
    else -> "6 GHz"
}

/** "Wi‑Fi 6 (802.11ax)", "Wi‑Fi 6E" en 6 GHz, etc. */
fun wifiStandardLabel(standard: Int?, freqMhz: Int?): String? = when (standard) {
    1 -> "802.11a/b/g"
    4 -> "Wi‑Fi 4 (802.11n)"
    5 -> "Wi‑Fi 5 (802.11ac)"
    6 -> if ((freqMhz ?: 0) >= 5925) "Wi‑Fi 6E (802.11ax)" else "Wi‑Fi 6 (802.11ax)"
    7 -> "WiGig (802.11ad)"
    8 -> "Wi‑Fi 7 (802.11be)"
    else -> null
}

fun socName(): String =
    if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL != Build.UNKNOWN) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
    else Build.HARDWARE

fun avgGhz(s: Snapshot): String {
    val on = s.cores.map { it.curMhz }.filter { it > 0 }
    return if (on.isEmpty()) "—" else formatGhz(on.average().toInt())
}

fun stateText(c: Char): String = when (c) {
    'R' -> tr("En ejecución")
    'S', 'I' -> tr("En espera")
    'D' -> tr("En E/S")
    'T', 't' -> tr("Suspendido")
    'Z' -> tr("Zombi")
    else -> c.toString()
}

fun priorityText(nice: Int): String = when {
    nice <= -20 -> tr("Tiempo real")
    nice <= -10 -> tr("Alta")
    nice < 0 -> tr("Por encima de lo normal")
    nice == 0 -> tr("Normal")
    nice < 10 -> tr("Por debajo de lo normal")
    else -> tr("Baja")
}

val PRIORITIES = listOf(
    "Tiempo real" to -20,
    "Alta" to -10,
    "Por encima de lo normal" to -5,
    "Normal" to 0,
    "Por debajo de lo normal" to 5,
    "Baja" to 19,
)
