package com.kevxngg.monitorroot

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.abs

data class ProcInfo(
    val pid: Int,
    val user: String,
    val name: String,
    val cpu: Float,
    val rssKb: Long,
    val threads: Int,
    val pkg: String?,
    val label: String?,
    val icon: ImageBitmap?,
    val launchable: Boolean,
)

data class CoreInfo(val index: Int, val curMhz: Int, val maxMhz: Int)
data class ThermalInfo(val name: String, val celsius: Float)

data class Snapshot(
    val cpuTotal: Float,
    val cores: List<CoreInfo>,
    val threads: Int,
    val uptimeSec: Long,
    val memTotalKb: Long,
    val memAvailKb: Long,
    val memCachedKb: Long,
    val swapTotalKb: Long,
    val swapFreeKb: Long,
    val gpuSource: String,
    val gpuLoad: Int?,
    val gpuFreqMhz: Int?,
    val thermals: List<ThermalInfo>,
    val battLevel: Int?,
    val battTempC: Float?,
    val battCurrentMa: Int?,
    val wifiRxBps: Long,
    val wifiTxBps: Long,
    val wifiRxTotal: Long,
    val wifiTxTotal: Long,
    val wifiIp: String?,
    val wifiRssi: Int?,
    val wifiLinkMbps: Int?,
    val wifiFreqMhz: Int?,
    val processes: List<ProcInfo>,
)

private class AppMeta(val label: String, val icon: ImageBitmap?, val launchable: Boolean)

private val WS = Regex("\\s+")
private val NUM = Regex("\\d+")
private val CORE = Regex("/cpu(\\d+)/cpufreq/(scaling_cur_freq|cpuinfo_max_freq)$")
private val IPV4 = Regex("inet (\\d+\\.\\d+\\.\\d+\\.\\d+)")
private val GPU_NAME = Regex("gpu|mali|kgsl", RegexOption.IGNORE_CASE)

private fun nums(s: String?): List<Long> =
    if (s == null) emptyList() else NUM.findAll(s).mapNotNull { it.value.toLongOrNull() }.toList()

/** Normaliza una frecuencia que puede venir en Hz, kHz o MHz. */
private fun toMhz(v: Long): Int = when {
    v > 10_000_000 -> v / 1_000_000
    v > 10_000 -> v / 1000
    else -> v
}.toInt()

// Todo se lee con una sola llamada a la shell root.
// Las rutas de GPU cambian según el chip (Adreno, Mali, MediaTek).
private val SCRIPT = """
    echo @@STAT; head -n 1 /proc/stat
    echo @@UP; cat /proc/uptime
    echo @@PSTAT; cat /proc/[0-9]*/stat
    echo @@PS; ps -A -o PID,USER,RSS,NAME
    echo @@MEM; cat /proc/meminfo
    echo @@SYS; grep -H . /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq /sys/class/thermal/thermal_zone*/type /sys/class/thermal/thermal_zone*/temp /sys/class/power_supply/battery/capacity /sys/class/power_supply/battery/temp /sys/class/power_supply/battery/current_now /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage /sys/class/kgsl/kgsl-3d0/gpubusy /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq /sys/class/kgsl/kgsl-3d0/gpuclk /sys/kernel/gpu/gpu_busy /sys/kernel/gpu/gpu_clock /sys/kernel/ged/hal/gpu_utilization /sys/kernel/ged/hal/current_freqency /sys/class/devfreq/*/cur_freq
    echo @@NET; cat /proc/net/dev
    echo @@IP; ip -4 -o addr show wlan0
""".trimIndent()

class SystemReader(context: Context) {
    private val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val pm: PackageManager = context.packageManager
    private val apps = HashMap<String, AppMeta?>()

    private var prevTotal = 0L
    private var prevIdle = 0L
    private var prevProc = HashMap<Int, Long>()
    private var prevRx = -1L
    private var prevTx = -1L
    private var prevTime = 0L

    /** Nombre e icono de la app dueña del proceso (se guarda en caché). */
    private fun appMeta(pkg: String): AppMeta? {
        if (apps.containsKey(pkg)) return apps[pkg]
        val meta = try {
            @Suppress("DEPRECATION")
            val ai = pm.getApplicationInfo(pkg, 0)
            val icon = try {
                pm.getApplicationIcon(ai).toBitmap(64, 64).asImageBitmap()
            } catch (e: Exception) {
                null
            }
            AppMeta(pm.getApplicationLabel(ai).toString(), icon, pm.getLaunchIntentForPackage(pkg) != null)
        } catch (e: Exception) {
            null
        }
        apps[pkg] = meta
        return meta
    }

    fun sample(): Snapshot {
        val sec = HashMap<String, MutableList<String>>()
        var cur: MutableList<String>? = null
        for (line in RootShell.run(SCRIPT)) {
            if (line.startsWith("@@")) {
                val l = ArrayList<String>()
                sec[line.removePrefix("@@").trim()] = l
                cur = l
            } else {
                cur?.add(line)
            }
        }

        // ---- CPU total ----
        val cpuF = sec["STAT"]?.firstOrNull()?.trim()?.split(WS)?.drop(1)
            ?.mapNotNull { it.toLongOrNull() }.orEmpty()
        val hadPrev = prevTotal > 0
        var cpuTotal = 0f
        var dTotal = 0L
        if (cpuF.size >= 5) {
            val total = cpuF.take(8).sum()
            val idle = cpuF[3] + cpuF[4]
            dTotal = total - prevTotal
            if (hadPrev && dTotal > 0) cpuTotal = (dTotal - (idle - prevIdle)) * 100f / dTotal
            prevTotal = total
            prevIdle = idle
        }

        val uptime = sec["UP"]?.firstOrNull()?.substringBefore(' ')?.toDoubleOrNull()?.toLong() ?: 0L

        // ---- Procesos ----
        val names = HashMap<Int, Pair<String, String>>()
        val rss = HashMap<Int, Long>()
        sec["PS"]?.drop(1)?.forEach { l ->
            val p = l.trim().split(WS, limit = 4)
            if (p.size == 4) {
                val pid = p[0].toIntOrNull() ?: return@forEach
                names[pid] = p[1] to p[3]
                rss[pid] = p[2].toLongOrNull() ?: 0L
            }
        }
        val newProc = HashMap<Int, Long>()
        val procs = ArrayList<ProcInfo>()
        var threadsTotal = 0
        sec["PSTAT"]?.forEach { l ->
            val open = l.indexOf('(')
            val close = l.lastIndexOf(')')
            if (open < 0 || close < 0 || close + 2 > l.length) return@forEach
            val pid = l.substring(0, open).trim().toIntOrNull() ?: return@forEach
            val comm = l.substring(open + 1, close)
            val f = l.substring(close + 2).split(' ')
            if (f.size < 18) return@forEach
            val ticks = (f[11].toLongOrNull() ?: 0L) + (f[12].toLongOrNull() ?: 0L) // utime + stime
            val threads = f[17].toIntOrNull() ?: 0
            threadsTotal += threads
            newProc[pid] = ticks
            val prev = prevProc[pid]
            val cpu = if (hadPrev && prev != null && dTotal > 0) (ticks - prev) * 100f / dTotal else 0f
            val (user, rawName) = names[pid] ?: ("?" to comm)
            val pkgCandidate = rawName.substringBefore(':')
            val meta = if (pkgCandidate.contains('.') && !pkgCandidate.startsWith("/")) appMeta(pkgCandidate) else null
            procs.add(
                ProcInfo(
                    pid = pid,
                    user = user,
                    name = if (meta != null) rawName else rawName.substringAfterLast('/'),
                    cpu = cpu.coerceAtLeast(0f),
                    rssKb = rss[pid] ?: 0L,
                    threads = threads,
                    pkg = if (meta != null) pkgCandidate else null,
                    label = meta?.label,
                    icon = meta?.icon,
                    launchable = meta?.launchable ?: false,
                )
            )
        }
        prevProc = newProc

        // ---- Memoria ----
        val mem = sec["MEM"].orEmpty()
        fun memKb(key: String) =
            mem.firstOrNull { it.startsWith("$key:") }?.split(WS)?.getOrNull(1)?.toLongOrNull() ?: 0L

        // ---- Archivos de /sys (ruta -> valor) ----
        val sys = LinkedHashMap<String, String>()
        sec["SYS"]?.forEach { l ->
            val i = l.indexOf(':')
            if (i > 0) sys.putIfAbsent(l.substring(0, i), l.substring(i + 1).trim())
        }

        val curF = HashMap<Int, Int>()
        val maxF = HashMap<Int, Int>()
        sys.forEach { (k, v) ->
            val m = CORE.find(k) ?: return@forEach
            val idx = m.groupValues[1].toInt()
            val mhz = ((v.toLongOrNull() ?: 0L) / 1000).toInt()
            if (m.groupValues[2] == "scaling_cur_freq") curF[idx] = mhz else maxF[idx] = mhz
        }
        val cores = (curF.keys + maxF.keys).sorted().map { CoreInfo(it, curF[it] ?: 0, maxF[it] ?: 0) }

        val thermals = sys.keys
            .filter { it.contains("thermal_zone") && it.endsWith("/temp") }
            .mapNotNull { k ->
                val base = k.removeSuffix("/temp")
                val raw = sys[k]?.toFloatOrNull() ?: return@mapNotNull null
                val c = if (abs(raw) >= 1000f) raw / 1000f else raw
                if (c <= 0f || c > 150f) null
                else ThermalInfo(sys["$base/type"] ?: base.substringAfterLast('/'), c)
            }
            .sortedByDescending { it.celsius }

        val bp = "/sys/class/power_supply/battery/"
        val battLevel = sys[bp + "capacity"]?.toIntOrNull()
        val battTemp = sys[bp + "temp"]?.toFloatOrNull()?.div(10f)
        val battCur = sys[bp + "current_now"]?.toLongOrNull()
            ?.let { if (abs(it) > 10_000) (it / 1000).toInt() else it.toInt() }

        // ---- GPU ----
        val kg = "/sys/class/kgsl/kgsl-3d0/"
        var gpuSrc = "No detectada"
        var gpuLoad: Int? = null
        var gpuFreqRaw: String? = null
        when {
            sys.keys.any { it.startsWith(kg) } -> {
                gpuSrc = "Adreno"
                gpuLoad = nums(sys[kg + "gpu_busy_percentage"]).firstOrNull()?.toInt()
                    ?: nums(sys[kg + "gpubusy"]).let { n ->
                        if (n.size >= 2 && n[1] > 0) (n[0] * 100 / n[1]).toInt() else null
                    }
                gpuFreqRaw = sys[kg + "devfreq/cur_freq"] ?: sys[kg + "gpuclk"]
            }
            "/sys/kernel/gpu/gpu_busy" in sys || "/sys/kernel/gpu/gpu_clock" in sys -> {
                gpuSrc = "Mali"
                gpuLoad = nums(sys["/sys/kernel/gpu/gpu_busy"]).firstOrNull()?.toInt()
                gpuFreqRaw = sys["/sys/kernel/gpu/gpu_clock"]
            }
            "/sys/kernel/ged/hal/gpu_utilization" in sys -> {
                gpuSrc = "Mali (MediaTek)"
                gpuLoad = nums(sys["/sys/kernel/ged/hal/gpu_utilization"]).firstOrNull()?.toInt()
                gpuFreqRaw = sys["/sys/kernel/ged/hal/current_freqency"]
            }
            else -> {
                val d = sys.keys.firstOrNull { it.startsWith("/sys/class/devfreq/") && GPU_NAME.containsMatchIn(it) }
                if (d != null) {
                    gpuSrc = d.removePrefix("/sys/class/devfreq/").substringBefore('/')
                    gpuFreqRaw = sys[d]
                }
            }
        }
        val gpuFreq = nums(gpuFreqRaw).maxOrNull()?.let(::toMhz)

        // ---- Wi-Fi ----
        val now = SystemClock.elapsedRealtime()
        var rx = 0L
        var tx = 0L
        sec["NET"]?.firstOrNull { it.trim().startsWith("wlan0:") }?.let { l ->
            val f = l.substringAfter(':').trim().split(WS)
            rx = f.getOrNull(0)?.toLongOrNull() ?: 0L
            tx = f.getOrNull(8)?.toLongOrNull() ?: 0L
        }
        val dt = (now - prevTime) / 1000f
        val rxBps = if (prevRx >= 0 && dt > 0) ((rx - prevRx) / dt).toLong().coerceAtLeast(0) else 0L
        val txBps = if (prevTx >= 0 && dt > 0) ((tx - prevTx) / dt).toLong().coerceAtLeast(0) else 0L
        prevRx = rx
        prevTx = tx
        prevTime = now

        val ip = sec["IP"]?.firstNotNullOfOrNull { IPV4.find(it)?.groupValues?.get(1) }

        @Suppress("DEPRECATION")
        val info = wifi.connectionInfo
        val rssi = info?.rssi?.takeIf { it > -127 && it < 0 }
        val link = info?.linkSpeed?.takeIf { it > 0 }
        val freq = info?.frequency?.takeIf { it > 0 }

        return Snapshot(
            cpuTotal = cpuTotal.coerceIn(0f, 100f),
            cores = cores,
            threads = threadsTotal,
            uptimeSec = uptime,
            memTotalKb = memKb("MemTotal"),
            memAvailKb = memKb("MemAvailable"),
            memCachedKb = memKb("Cached"),
            swapTotalKb = memKb("SwapTotal"),
            swapFreeKb = memKb("SwapFree"),
            gpuSource = gpuSrc,
            gpuLoad = gpuLoad?.coerceIn(0, 100),
            gpuFreqMhz = gpuFreq,
            thermals = thermals,
            battLevel = battLevel,
            battTempC = battTemp,
            battCurrentMa = battCur,
            wifiRxBps = rxBps,
            wifiTxBps = txBps,
            wifiRxTotal = rx,
            wifiTxTotal = tx,
            wifiIp = ip,
            wifiRssi = rssi,
            wifiLinkMbps = link,
            wifiFreqMhz = freq,
            processes = procs,
        )
    }
}
