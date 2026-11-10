package com.kevxngg.monitorroot

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.abs

data class ProcInfo(
    val pid: Int,
    val ppid: Int,
    val user: String,
    val androidUser: Int?,
    val name: String,
    val state: Char,
    val nice: Int,
    val cpu: Float,
    val cpuTicks: Long,
    val startTicks: Long,
    val rssKb: Long,
    val threads: Int,
    val diskBps: Long,
    val pkg: String?,
    val label: String?,
    val icon: ImageBitmap?,
    val launchable: Boolean,
    /** 64 o 32 bits según el zygote que lanzó la app (null en procesos nativos). */
    val bits: Int? = null,
)

data class CoreInfo(val index: Int, val curMhz: Int, val maxMhz: Int, val usage: Float)
data class ThermalInfo(val name: String, val celsius: Float)
data class NetStat(val rxBps: Long = 0, val txBps: Long = 0, val rxTotal: Long = 0, val txTotal: Long = 0)

data class DiskInfo(
    val name: String,
    val type: String,
    val activePct: Float,
    val readBps: Long,
    val writeBps: Long,
    val respMs: Float,
    val totalBytes: Long,
    val freeBytes: Long,
    /** false si solo se conoce la capacidad (por ejemplo con Shizuku, sin /proc/diskstats). */
    val ioAvailable: Boolean = true,
)

data class GpuInfo(
    val source: String,
    val model: String?,
    val load: Int?,
    val freqMhz: Int?,
    val maxMhz: Int?,
    val memBytes: Long?,
    val tempC: Float?,
    val tempIsSoc: Boolean,
)

data class BattInfo(
    val level: Int?,
    val tempC: Float?,
    val currentMa: Int?,
    val status: String?,
    val health: String?,
    val voltageMv: Int?,
)

data class WifiData(
    val net: NetStat,
    val ip: String?,
    val ip6: String?,
    val rssi: Int?,
    val linkMbps: Int?,
    val freqMhz: Int?,
    val ssid: String?,
    val mac: String?,
    /** Estándar Wi‑Fi de la conexión (ScanResult.WIFI_STANDARD_*), null si no se sabe. */
    val standard: Int? = null,
)

data class MemInfo(
    val totalKb: Long,
    val availKb: Long,
    val cachedKb: Long,
    val buffersKb: Long,
    val slabKb: Long,
    val sunreclaimKb: Long,
    val committedKb: Long,
    val commitLimitKb: Long,
    val swapTotalKb: Long,
    val swapFreeKb: Long,
    val zramOrigBytes: Long,
    val zramUsedBytes: Long,
) {
    val usedKb get() = totalKb - availKb
    val usedPct get() = if (totalKb > 0) usedKb * 100f / totalKb else 0f
}

data class Snapshot(
    val cpuTotal: Float,
    val cores: List<CoreInfo>,
    val governor: String?,
    val threads: Int,
    val handles: Long,
    val uptimeSec: Long,
    val mem: MemInfo,
    val disk: DiskInfo?,
    val gpu: GpuInfo,
    val thermals: List<ThermalInfo>,
    val mainTempC: Float?,
    val mainTempName: String?,
    val batt: BattInfo,
    val wifi: WifiData,
    val mobile: NetStat?,
    val mobileIfaces: List<String>,
    val processes: List<ProcInfo>,
)

private val WS = Regex("\\s+")
private val NUM = Regex("\\d+")
private val CORE = Regex("/cpu(\\d+)/cpufreq/(scaling_cur_freq|cpuinfo_max_freq)$")
private val IPV4 = Regex("inet (\\d+\\.\\d+\\.\\d+\\.\\d+)")
private val IPV6 = Regex("inet6 ([0-9a-fA-F:]+)/\\d+ scope global")
private val GPU_NAME = Regex("gpu|mali|kgsl", RegexOption.IGNORE_CASE)
private val DISK_RE = Regex("^(sd[a-z]|mmcblk\\d+|nvme\\d+n\\d+|vd[a-z])$")
private val MOBILE_RE = Regex("^(rmnet_data\\d+|rmnet\\d+|ccmni\\d+|seth_lte\\d+|wwan\\d+)$")
private val USER_RE = Regex("^u(\\d+)_")
private val SSID_RE = Regex("SSID: \"([^\"]*)\"")
// Sensores del procesador (CPU/GPU/SoC): son los que se grafican
private val CPU_TEMP = Regex("cpu|gpu|soc|tsens|apc|mtktscpu|cluster", RegexOption.IGNORE_CASE)
private val OPP_FREQ = Regex("freq\\s*[:=]\\s*(\\d+)", RegexOption.IGNORE_CASE)
private val SOC_TEMP = Regex("soc|mtktscpu|mtktsap|cpu|tsens|apc", RegexOption.IGNORE_CASE)
private val SSID_RE2 = Regex("connected to \"([^\"]*)\"")

private fun nums(s: String?): List<Long> =
    if (s == null) emptyList() else NUM.findAll(s).mapNotNull { it.value.toLongOrNull() }.toList()

private fun List<String>.long(i: Int) = getOrNull(i)?.toLongOrNull() ?: 0L

/** Normaliza una frecuencia que puede venir en Hz, kHz o MHz. */
private fun toMhz(v: Long): Int = when {
    v > 10_000_000 -> v / 1_000_000
    v > 10_000 -> v / 1000
    else -> v
}.toInt()

private const val KG = "/sys/class/kgsl/kgsl-3d0/"
private const val BAT = "/sys/class/power_supply/battery/"

private val SYS_FILES = listOf(
    "/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq",
    "/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq",
    "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor",
    "/sys/class/thermal/thermal_zone*/type",
    "/sys/class/thermal/thermal_zone*/temp",
    "/sys/class/thermal/thermal_zone*/mode",
    "${BAT}capacity", "${BAT}temp", "${BAT}current_now", "${BAT}status", "${BAT}health", "${BAT}voltage_now",
    "/sys/block/zram0/mm_stat",
    "${KG}gpu_busy_percentage", "${KG}gpubusy", "${KG}devfreq/cur_freq", "${KG}gpuclk",
    "${KG}gpu_model", "${KG}max_gpuclk", "${KG}devfreq/max_freq",
    "/sys/class/kgsl/kgsl/page_alloc",
    "/sys/kernel/gpu/gpu_busy", "/sys/kernel/gpu/gpu_clock", "/sys/kernel/gpu/gpu_model", "/sys/kernel/gpu/gpu_max_clock",
    "/sys/kernel/ged/hal/gpu_utilization", "/sys/kernel/ged/hal/current_freqency",
    "/sys/class/devfreq/*/cur_freq",
    "/sys/class/devfreq/*/max_freq",
    "/sys/class/misc/mali0/device/gpuinfo",
)

// Una sola llamada a la shell root por actualización.
private val SCRIPT = """
    echo @@STAT; grep '^cpu' /proc/stat
    echo @@UP; cat /proc/uptime
    echo @@PSTAT; cat /proc/[0-9]*/stat
    echo @@PS; ps -A -o PID,USER,RSS,NAME
    echo @@IO; grep -H -e '^read_bytes' -e '^write_bytes' /proc/[0-9]*/io
    echo @@MEM; cat /proc/meminfo
    echo @@DISK; cat /proc/diskstats
    echo @@FILE; cat /proc/sys/fs/file-nr
    echo @@SYS; grep -H . ${SYS_FILES.joinToString(" ")}
    echo @@NET; cat /proc/net/dev
    echo @@IP; ip -o addr show wlan0
""".trimIndent()

// Datos que cambian poco: se leen cada 5 actualizaciones.
private val SLOW_SCRIPT = """
    echo @@SSID; cmd wifi status
    echo @@MAC; cat /sys/class/net/wlan0/address
    echo @@GPUOPP; cat /proc/gpufreqv2/gpu_working_opp_table /proc/gpufreqv2/gpu_signed_opp_table /proc/gpufreq/gpufreq_opp_dump
""".trimIndent()

private class DiskPrev(val name: String, val rd: Long, val wr: Long, val ios: Long, val msRw: Long, val msIo: Long)

class SystemReader(context: Context) {
    private val appCtx = context.applicationContext
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var prevCpu = HashMap<String, Pair<Long, Long>>()
    private var prevProc = HashMap<Int, Long>()
    private var prevIo = HashMap<Int, Long>()
    private var prevNet = HashMap<String, Pair<Long, Long>>()
    private var prevDisk: DiskPrev? = null
    private var prevTime = 0L
    private var counter = 0
    private var ssid: String? = null
    private var mac: String? = null
    private var gpuOppMaxMhz: Int? = null
    private val lastTemp = HashMap<String, Float>()
    private val stuckTemp = HashMap<String, Int>()

    private fun sections(lines: List<String>): Map<String, List<String>> {
        val sec = HashMap<String, MutableList<String>>()
        var cur: MutableList<String>? = null
        for (line in lines) {
            if (line.startsWith("@@")) {
                val l = ArrayList<String>()
                sec[line.removePrefix("@@").trim()] = l
                cur = l
            } else {
                cur?.add(line)
            }
        }
        return sec
    }

    fun sample(): Snapshot {
        if (counter % 5 == 0) {
            val slow = sections(RootShell.run(SLOW_SCRIPT))
            ssid = slow["SSID"]?.firstNotNullOfOrNull {
                (SSID_RE.find(it) ?: SSID_RE2.find(it))?.groupValues?.get(1)
            }?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            mac = slow["MAC"]?.firstOrNull()?.trim()?.takeIf { it.contains(':') }
            gpuOppMaxMhz = slow["GPUOPP"].orEmpty()
                .mapNotNull { OPP_FREQ.find(it)?.groupValues?.get(1)?.toLongOrNull() }
                .maxOrNull()?.let(::toMhz) ?: gpuOppMaxMhz
            GlInfo.load()
        }
        counter++

        val sec = sections(RootShell.run(SCRIPT))
        val now = SystemClock.elapsedRealtime()
        val dt = if (prevTime > 0) (now - prevTime) / 1000f else 0f
        prevTime = now

        // ---- CPU (total y por núcleo) ----
        var cpuTotal = 0f
        var dTotalAll = 0L
        val hadPrev = prevCpu.containsKey("cpu")
        val coreUsage = HashMap<Int, Float>()
        val newCpu = HashMap<String, Pair<Long, Long>>()
        sec["STAT"]?.forEach { l ->
            val f = l.trim().split(WS)
            val n = f.drop(1).mapNotNull { it.toLongOrNull() }
            if (n.size < 5) return@forEach
            val total = n.take(8).sum()
            val idle = n[3] + n[4]
            val key = f[0]
            val prev = prevCpu[key]
            if (prev != null) {
                val dT = total - prev.first
                val dI = idle - prev.second
                val pct = if (dT > 0) ((dT - dI) * 100f / dT).coerceIn(0f, 100f) else 0f
                if (key == "cpu") {
                    cpuTotal = pct
                    dTotalAll = dT
                } else {
                    key.removePrefix("cpu").toIntOrNull()?.let { coreUsage[it] = pct }
                }
            }
            newCpu[key] = total to idle
        }
        prevCpu = newCpu

        val uptime = sec["UP"]?.firstOrNull()?.substringBefore(' ')?.toDoubleOrNull()?.toLong() ?: 0L

        // ---- E/S de disco por proceso ----
        val ioNow = HashMap<Int, Long>()
        sec["IO"]?.forEach { l ->
            val b = l.indexOf("/io:")
            if (!l.startsWith("/proc/") || b < 0) return@forEach
            val pid = l.substring(6, b).toIntOrNull() ?: return@forEach
            val v = l.substring(b + 4).substringAfter(':').trim().toLongOrNull() ?: return@forEach
            ioNow[pid] = (ioNow[pid] ?: 0L) + v
        }

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
            if (f.size < 20) return@forEach
            val ticks = f.long(11) + f.long(12)
            val threads = f.long(17).toInt()
            threadsTotal += threads
            newProc[pid] = ticks
            val prev = prevProc[pid]
            val cpu = if (hadPrev && prev != null && dTotalAll > 0) (ticks - prev) * 100f / dTotalAll else 0f
            val io = ioNow[pid]
            val pio = prevIo[pid]
            val diskBps = if (io != null && pio != null && dt > 0) ((io - pio) / dt).toLong().coerceAtLeast(0) else 0L
            val (user, rawName) = names[pid] ?: ("?" to comm)
            val pkgCandidate = rawName.substringBefore(':')
            val meta = if (pkgCandidate.contains('.') && !pkgCandidate.startsWith("/")) Apps.meta(pkgCandidate) else null
            procs.add(
                ProcInfo(
                    pid = pid,
                    ppid = f.long(1).toInt(),
                    user = user,
                    androidUser = USER_RE.find(user)?.groupValues?.get(1)?.toIntOrNull(),
                    name = if (meta != null) rawName else rawName.substringAfterLast('/'),
                    state = f[0].firstOrNull() ?: '?',
                    nice = f.long(16).toInt(),
                    cpu = cpu.coerceIn(0f, 100f),
                    cpuTicks = ticks,
                    startTicks = f.long(19),
                    rssKb = rss[pid] ?: 0L,
                    threads = threads,
                    diskBps = diskBps,
                    pkg = if (meta != null) pkgCandidate else null,
                    label = meta?.label,
                    icon = meta?.icon,
                    launchable = meta?.launchable ?: false,
                    bits = when (names[f.long(1).toInt()]?.second) {
                        "zygote64" -> 64
                        "zygote" -> 32
                        else -> null
                    },
                )
            )
        }
        prevProc = newProc
        prevIo = ioNow

        // ---- Archivos de /sys (ruta -> valor) ----
        val sys = LinkedHashMap<String, String>()
        sec["SYS"]?.forEach { l ->
            val i = l.indexOf(':')
            if (i > 0) sys.putIfAbsent(l.substring(0, i), l.substring(i + 1).trim())
        }

        // ---- Memoria ----
        val memLines = sec["MEM"].orEmpty()
        fun memKb(key: String) =
            memLines.firstOrNull { it.startsWith("$key:") }?.split(WS)?.getOrNull(1)?.toLongOrNull() ?: 0L
        val zram = nums(sys["/sys/block/zram0/mm_stat"])
        val mem = MemInfo(
            totalKb = memKb("MemTotal"),
            availKb = memKb("MemAvailable"),
            cachedKb = memKb("Cached"),
            buffersKb = memKb("Buffers"),
            slabKb = memKb("Slab"),
            sunreclaimKb = memKb("SUnreclaim"),
            committedKb = memKb("Committed_AS"),
            commitLimitKb = memKb("CommitLimit"),
            swapTotalKb = memKb("SwapTotal"),
            swapFreeKb = memKb("SwapFree"),
            zramOrigBytes = zram.getOrNull(0) ?: 0L,
            zramUsedBytes = zram.getOrNull(2) ?: 0L,
        )

        // ---- Núcleos ----
        val curF = HashMap<Int, Int>()
        val maxF = HashMap<Int, Int>()
        sys.forEach { (k, v) ->
            val m = CORE.find(k) ?: return@forEach
            val idx = m.groupValues[1].toInt()
            val mhz = ((v.toLongOrNull() ?: 0L) / 1000).toInt()
            if (m.groupValues[2] == "scaling_cur_freq") curF[idx] = mhz else maxF[idx] = mhz
        }
        val cores = (curF.keys + maxF.keys + coreUsage.keys).sorted().distinct()
            .map { CoreInfo(it, curF[it] ?: 0, maxF[it] ?: 0, coreUsage[it] ?: 0f) }

        // ---- Temperaturas ----
        // Se descartan sensores apagados y los que dan valores imposibles o fijos
        // (p. ej. "ti-cp-standalone", que siempre marca 100 °C).
        val thermals = sys.keys
            .filter { it.contains("thermal_zone") && it.endsWith("/temp") }
            .mapNotNull { k ->
                val base = k.removeSuffix("/temp")
                if (sys["$base/mode"] == "disabled") return@mapNotNull null
                val raw = sys[k]?.toFloatOrNull() ?: return@mapNotNull null
                val c = if (abs(raw) >= 1000f) raw / 1000f else raw
                val name = sys["$base/type"] ?: base.substringAfterLast('/')
                val isCpu = CPU_TEMP.containsMatchIn(name)
                val same = lastTemp[name] == c
                lastTemp[name] = c
                val stuck = if (same && c >= 90f) (stuckTemp[name] ?: 0) + 1 else 0
                stuckTemp[name] = stuck
                val valid = c > 0f && c < 125f && (c < 95f || (isCpu && stuck < 3))
                if (valid) ThermalInfo(name, c) else null
            }
            .sortedByDescending { it.celsius }
            .ifEmpty {
                AndroidStats.thermalSensors().map { s ->
                    val n = if (s.type == 0 && !CPU_TEMP.containsMatchIn(s.name)) "CPU ${s.name}" else s.name
                    ThermalInfo(n, s.celsius)
                }.sortedByDescending { it.celsius }
            }
        val mainTemp = thermals.filter { CPU_TEMP.containsMatchIn(it.name) }.maxByOrNull { it.celsius }
            ?: thermals.firstOrNull()

        // ---- Batería ----
        val battSys = BattInfo(
            level = sys[BAT + "capacity"]?.toIntOrNull(),
            tempC = sys[BAT + "temp"]?.toFloatOrNull()?.div(10f),
            // Cada fabricante usa un signo distinto: se normaliza según el estado
            // (+ cargando, − descargando).
            currentMa = sys[BAT + "current_now"]?.toLongOrNull()
                ?.let { if (abs(it) > 10_000) (it / 1000).toInt() else it.toInt() }
                ?.let {
                    when (sys[BAT + "status"]) {
                        "Charging" -> abs(it)
                        "Discharging" -> -abs(it)
                        else -> it
                    }
                },
            status = when (sys[BAT + "status"]) {
                "Charging" -> "Cargando"
                "Discharging" -> "Descargando"
                "Full" -> "Completa"
                "Not charging" -> "No se está cargando"
                null -> null
                else -> sys[BAT + "status"]
            },
            health = when (sys[BAT + "health"]) {
                "Good" -> "Buena"
                "Overheat" -> "Sobrecalentada"
                "Dead" -> "Agotada"
                "Cold" -> "Fría"
                null -> null
                else -> sys[BAT + "health"]
            },
            voltageMv = sys[BAT + "voltage_now"]?.toLongOrNull()
                ?.let { if (it > 100_000) (it / 1000).toInt() else it.toInt() },
        )
        // Lo que /sys no da (pasa con Shizuku) se completa con la API de batería de Android
        val batt = if (battSys.level != null && battSys.tempC != null && battSys.voltageMv != null) battSys else {
            val api = AndroidStats.battery(appCtx)
            BattInfo(
                level = battSys.level ?: api.level,
                tempC = battSys.tempC ?: api.tempC,
                currentMa = battSys.currentMa ?: api.currentMa,
                status = battSys.status ?: api.status,
                health = battSys.health ?: api.health,
                voltageMv = battSys.voltageMv ?: api.voltageMv,
            )
        }

        // ---- GPU ----
        var gpuSrc = "No detectada"
        var gpuModel: String? = null
        var gpuLoad: Int? = null
        var gpuFreqRaw: String? = null
        var gpuMaxRaw: String? = null
        var gpuMem: Long? = null
        when {
            sys.keys.any { it.startsWith(KG) } -> {
                gpuSrc = "Adreno"
                gpuModel = sys[KG + "gpu_model"]
                gpuLoad = nums(sys[KG + "gpu_busy_percentage"]).firstOrNull()?.toInt()
                    ?: nums(sys[KG + "gpubusy"]).let { n ->
                        if (n.size >= 2 && n[1] > 0) (n[0] * 100 / n[1]).toInt() else null
                    }
                gpuFreqRaw = sys[KG + "devfreq/cur_freq"] ?: sys[KG + "gpuclk"]
                gpuMaxRaw = sys[KG + "max_gpuclk"] ?: sys[KG + "devfreq/max_freq"]
                gpuMem = sys["/sys/class/kgsl/kgsl/page_alloc"]?.toLongOrNull()
            }
            "/sys/kernel/gpu/gpu_busy" in sys || "/sys/kernel/gpu/gpu_clock" in sys -> {
                gpuSrc = "Mali"
                gpuModel = sys["/sys/kernel/gpu/gpu_model"]
                gpuLoad = nums(sys["/sys/kernel/gpu/gpu_busy"]).firstOrNull()?.toInt()
                gpuFreqRaw = sys["/sys/kernel/gpu/gpu_clock"]
                gpuMaxRaw = sys["/sys/kernel/gpu/gpu_max_clock"]
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
        // devfreq de la GPU (Mali en MediaTek/Exynos y otros)
        val gpuDevfreq = sys.keys
            .filter { it.startsWith("/sys/class/devfreq/") && GPU_NAME.containsMatchIn(it) }
            .map { it.substringBeforeLast('/') }
            .firstOrNull()
        if (gpuFreqRaw == null && gpuDevfreq != null) gpuFreqRaw = sys["$gpuDevfreq/cur_freq"]
        val maxMhz = nums(gpuMaxRaw).maxOrNull()?.let(::toMhz)
            ?: gpuDevfreq?.let { nums(sys["$it/max_freq"]).maxOrNull()?.let(::toMhz) }
            ?: gpuOppMaxMhz
        // Nombre: el del driver gráfico, si no el de sysfs
        val maliInfo = sys["/sys/class/misc/mali0/device/gpuinfo"]?.substringBefore(" r")?.trim()
        val model = GlInfo.renderer ?: gpuModel?.takeIf { it.isNotBlank() } ?: maliInfo
        // Temperatura: sensor de GPU; si el chip no tiene, la del SoC
        val gpuTemp = thermals.filter { it.name.contains("gpu", ignoreCase = true) }.maxOfOrNull { it.celsius }
        val socTemp = if (gpuTemp == null) thermals.filter { SOC_TEMP.containsMatchIn(it.name) }.maxOfOrNull { it.celsius } else null
        val gpu = GpuInfo(
            source = gpuSrc,
            model = model,
            load = gpuLoad?.coerceIn(0, 100),
            freqMhz = nums(gpuFreqRaw).maxOrNull()?.let(::toMhz),
            maxMhz = maxMhz,
            memBytes = gpuMem,
            tempC = gpuTemp ?: socTemp,
            tempIsSoc = gpuTemp == null && socTemp != null,
        )

        // ---- Disco ----
        var disk: DiskInfo? = null
        val best = sec["DISK"].orEmpty()
            .map { it.trim().split(WS) }
            .filter { it.size >= 13 && DISK_RE.matches(it[2]) }
            .maxByOrNull { it.long(5) + it.long(9) }
        if (best != null) {
            val name = best[2]
            val rd = best.long(5) * 512
            val wr = best.long(9) * 512
            val ios = best.long(3) + best.long(7)
            val msRw = best.long(6) + best.long(10)
            val msIo = best.long(12)
            val p = prevDisk
            var act = 0f
            var rBps = 0L
            var wBps = 0L
            var resp = 0f
            if (p != null && p.name == name && dt > 0) {
                act = ((msIo - p.msIo) / (dt * 1000f) * 100f).coerceIn(0f, 100f)
                rBps = ((rd - p.rd) / dt).toLong().coerceAtLeast(0)
                wBps = ((wr - p.wr) / dt).toLong().coerceAtLeast(0)
                val dIos = ios - p.ios
                resp = if (dIos > 0) (msRw - p.msRw).toFloat() / dIos else 0f
            }
            prevDisk = DiskPrev(name, rd, wr, ios, msRw, msIo)
            val st = try {
                StatFs(Environment.getDataDirectory().path)
            } catch (e: Exception) {
                null
            }
            val type = when {
                name.startsWith("sd") -> "UFS"
                name.startsWith("mmcblk") -> "eMMC"
                name.startsWith("nvme") -> "NVMe"
                else -> "Virtual"
            }
            disk = DiskInfo(name, type, act, rBps, wBps, resp.coerceAtLeast(0f), st?.totalBytes ?: 0L, st?.availableBytes ?: 0L)
        }
        if (disk == null) {
            // Sin acceso a /proc/diskstats (Shizuku): se muestra la capacidad, sin actividad
            val st = try { StatFs(Environment.getDataDirectory().path) } catch (e: Exception) { null }
            if (st != null) disk = DiskInfo("data", "—", 0f, 0L, 0L, 0f, st.totalBytes, st.availableBytes, ioAvailable = false)
        }

        // ---- Red ----
        val ifaces = HashMap<String, Pair<Long, Long>>()
        sec["NET"]?.forEach { l ->
            val i = l.indexOf(':')
            if (i <= 0) return@forEach
            val f = l.substring(i + 1).trim().split(WS)
            if (f.size >= 9) ifaces[l.substring(0, i).trim()] = f.long(0) to f.long(8)
        }
        val newNet = HashMap<String, Pair<Long, Long>>()
        fun rate(key: String, rx: Long, tx: Long): NetStat {
            val p = prevNet[key]
            newNet[key] = rx to tx
            if (p == null || dt <= 0f) return NetStat(0, 0, rx, tx)
            return NetStat(
                ((rx - p.first) / dt).toLong().coerceAtLeast(0),
                ((tx - p.second) / dt).toLong().coerceAtLeast(0),
                rx, tx,
            )
        }
        val w = ifaces["wlan0"]
        val wifiNet = rate("wlan0", w?.first ?: 0L, w?.second ?: 0L)
        val mobileNames = ifaces.keys.filter { MOBILE_RE.matches(it) }.sorted()
        val mobile = if (mobileNames.isEmpty()) null else rate(
            "mobile",
            mobileNames.sumOf { ifaces[it]?.first ?: 0L },
            mobileNames.sumOf { ifaces[it]?.second ?: 0L },
        )
        prevNet = newNet

        val ipLines = sec["IP"].orEmpty()
        @Suppress("DEPRECATION")
        val info = wifiManager.connectionInfo
        val wifi = WifiData(
            net = wifiNet,
            ip = ipLines.firstNotNullOfOrNull { IPV4.find(it)?.groupValues?.get(1) },
            ip6 = ipLines.firstNotNullOfOrNull { IPV6.find(it)?.groupValues?.get(1) },
            rssi = info?.rssi?.takeIf { it > -127 && it < 0 },
            linkMbps = info?.linkSpeed?.takeIf { it > 0 },
            freqMhz = info?.frequency?.takeIf { it > 0 },
            ssid = ssid,
            mac = mac,
            standard = if (android.os.Build.VERSION.SDK_INT >= 30) info?.wifiStandard?.takeIf { it > 0 } else null,
        )

        return Snapshot(
            cpuTotal = cpuTotal,
            cores = cores,
            governor = sys["/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"],
            threads = threadsTotal,
            handles = sec["FILE"]?.firstOrNull()?.trim()?.split(WS)?.long(0) ?: 0L,
            uptimeSec = uptime,
            mem = mem,
            disk = disk,
            gpu = gpu,
            thermals = thermals,
            mainTempC = mainTemp?.celsius,
            mainTempName = mainTemp?.name,
            batt = batt,
            wifi = wifi,
            mobile = mobile,
            mobileIfaces = mobileNames,
            processes = procs,
        )
    }
}
