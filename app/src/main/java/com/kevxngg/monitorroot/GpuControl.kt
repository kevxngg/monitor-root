package com.kevxngg.monitorroot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Control de la GPU para cualquier procesador. Se detecta el "controlador" que
 * expone el kernel del teléfono:
 *  - DEVFREQ: estándar de Linux (Qualcomm Adreno, Google Tensor, Unisoc, muchos Mali)
 *  - EXYNOS:  /sys/kernel/gpu de Samsung
 *  - MTK_V2 / MTK_V1: gpufreq de MediaTek (frecuencia fija o automática)
 */
object GpuControl {
    enum class Kind(val label: String) {
        DEVFREQ("devfreq"),
        EXYNOS("Samsung Exynos"),
        MTK_V2("MediaTek gpufreq v2"),
        MTK_V1("MediaTek gpufreq"),
    }

    class State(
        val kind: Kind,
        val path: String,
        val governor: String?,
        val governors: List<String>,
        /** true = la frecuencia se fija; false = se limita la máxima. */
        val fixed: Boolean,
        /** Ajuste actual en kHz; null = automático / sin límite. */
        val currentKhz: Long?,
        val freqsKhz: List<Long>,
        /** MediaTek v2: kHz → índice de la tabla de frecuencias. */
        val oppIndex: Map<Long, Int> = emptyMap(),
    )

    private val INT = Regex("-?\\d+")
    private val OPP_V2 = Regex("\\[(\\d+)]\\s*freq:\\s*(\\d+)")
    private val OPP_V1 = Regex("\\[(\\d+)]\\s*freq\\s*=\\s*(\\d+)")

    // "§" se cambia por "$" para que el script sea legible dentro de Kotlin
    private val SCRIPT = """
        echo @@DEVFREQ
        for d in /sys/class/kgsl/kgsl-3d0/devfreq /sys/class/devfreq/*; do
          case "§d" in *kgsl*|*mali*|*gpu*|*GPU*|*g3d*)
            if [ -e "§d/governor" ]; then
              echo "path=§d"; echo "gov=§(cat §d/governor)"; echo "govs=§(cat §d/available_governors)"
              echo "max=§(cat §d/max_freq)"; echo "freqs=§(cat §d/available_frequencies)"; break
            fi;;
          esac
        done
        echo @@EXYNOS
        if [ -e /sys/kernel/gpu/gpu_max_clock ]; then
          echo "gov=§(cat /sys/kernel/gpu/gpu_governor)"; echo "govs=§(cat /sys/kernel/gpu/gpu_available_governor)"
          echo "max=§(cat /sys/kernel/gpu/gpu_max_clock)"
          echo "freqs=§(cat /sys/kernel/gpu/gpu_freq_table /sys/kernel/gpu/gpu_available_frequencies)"
        fi
        echo @@MTK2
        if [ -e /proc/gpufreqv2/fix_target_opp_index ]; then
          echo "fix=§(cat /proc/gpufreqv2/fix_target_opp_index)"; cat /proc/gpufreqv2/gpu_working_opp_table
        fi
        echo @@MTK1
        if [ -e /proc/gpufreq/gpufreq_opp_freq ]; then
          echo "fix=§(cat /proc/gpufreq/gpufreq_opp_freq)"; cat /proc/gpufreq/gpufreq_opp_dump
        fi
    """.trimIndent().replace("§", "$")

    private fun sections(lines: List<String>): Map<String, List<String>> {
        val out = HashMap<String, MutableList<String>>()
        var cur: MutableList<String>? = null
        for (l in lines) {
            if (l.startsWith("@@")) {
                val list = ArrayList<String>()
                out[l.removePrefix("@@").trim()] = list
                cur = list
            } else cur?.add(l)
        }
        return out
    }

    private fun kv(lines: List<String>?): Map<String, String> =
        lines.orEmpty().mapNotNull { l ->
            val i = l.indexOf('=')
            if (i > 0 && l.substring(0, i).all { it.isLetter() }) l.substring(0, i) to l.substring(i + 1).trim() else null
        }.toMap()

    private fun words(s: String?) = s?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty()
    private fun longs(s: String?) = s?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }.orEmpty()

    fun read(): State? {
        val sec = sections(RootShell.run(SCRIPT))

        kv(sec["DEVFREQ"]).takeIf { it.containsKey("path") }?.let { m ->
            val freqs = longs(m["freqs"]).map { it / 1000 }.filter { it > 0 }.distinct().sortedDescending()
            return State(
                Kind.DEVFREQ, m["path"]!!, m["gov"]?.ifBlank { null }, words(m["govs"]), fixed = false,
                currentKhz = longs(m["max"]).firstOrNull()?.div(1000), freqsKhz = freqs,
            )
        }
        kv(sec["EXYNOS"]).takeIf { it.containsKey("max") }?.let { m ->
            // Exynos usa MHz
            val freqs = longs(m["freqs"]).map { it * 1000 }.filter { it > 0 }.distinct().sortedDescending()
            return State(
                Kind.EXYNOS, "/sys/kernel/gpu", m["gov"]?.ifBlank { null }, words(m["govs"]), fixed = false,
                currentKhz = longs(m["max"]).firstOrNull()?.times(1000), freqsKhz = freqs,
            )
        }
        sec["MTK2"]?.takeIf { it.isNotEmpty() }?.let { lines ->
            val table = lines.mapNotNull { OPP_V2.find(it) }.associate { it.groupValues[2].toLong() to it.groupValues[1].toInt() }
            if (table.isNotEmpty()) {
                val fixIdx = INT.findAll(kv(lines)["fix"] ?: "").lastOrNull()?.value?.toIntOrNull() ?: -1
                return State(
                    Kind.MTK_V2, "/proc/gpufreqv2", null, emptyList(), fixed = true,
                    currentKhz = if (fixIdx < 0) null else table.entries.firstOrNull { it.value == fixIdx }?.key,
                    freqsKhz = table.keys.distinct().sortedDescending(), oppIndex = table,
                )
            }
        }
        sec["MTK1"]?.takeIf { it.isNotEmpty() }?.let { lines ->
            val freqs = lines.mapNotNull { OPP_V1.find(it)?.groupValues?.get(2)?.toLongOrNull() }.distinct().sortedDescending()
            if (freqs.isNotEmpty()) {
                val fix = INT.findAll(kv(lines)["fix"] ?: "").lastOrNull()?.value?.toLongOrNull() ?: 0L
                return State(
                    Kind.MTK_V1, "/proc/gpufreq", null, emptyList(), fixed = true,
                    currentKhz = fix.takeIf { it > 0 }, freqsKhz = freqs,
                )
            }
        }
        return null
    }

    fun setGovernor(s: State, gov: String) {
        if (Prefs.defaultGpuGovernor.isEmpty() && s.governor != null) {
            Prefs.defaultGpuGovernor = s.governor
            Prefs.save()
        }
        when (s.kind) {
            Kind.DEVFREQ -> Tuning.write("${s.path}/governor", gov)
            Kind.EXYNOS -> Tuning.write("/sys/kernel/gpu/gpu_governor", gov)
            else -> Unit
        }
        // Con performance la GPU va a la máxima real: se quita cualquier límite
        if (gov == "performance") setFreq(s, null)
    }

    /** Texto de la frecuencia cuando el gobernador la deja fija (null = se puede limitar). */
    fun pinnedLabel(s: State): String? = when (s.governor) {
        "performance" -> s.freqsKhz.maxOrNull()?.let { tr("Fija en la máxima") + ": " + mhz(it) }
        "powersave" -> s.freqsKhz.minOrNull()?.let { tr("Fija en la mínima") + ": " + mhz(it) }
        else -> null
    }

    /** [khz] = null vuelve a automático (o quita el límite). */
    fun setFreq(s: State, khz: Long?) {
        when (s.kind) {
            Kind.DEVFREQ -> {
                val hz = ((khz ?: s.freqsKhz.maxOrNull() ?: return) * 1000).toString()
                Tuning.write("${s.path}/max_freq", hz)
                if (s.path.contains("kgsl")) Tuning.write("/sys/class/kgsl/kgsl-3d0/max_gpuclk", hz)
            }
            Kind.EXYNOS -> {
                val mhz = (khz ?: s.freqsKhz.maxOrNull() ?: return) / 1000
                Tuning.write("/sys/kernel/gpu/gpu_max_clock", mhz.toString())
            }
            Kind.MTK_V2 -> {
                val idx = if (khz == null) -1 else s.oppIndex[khz] ?: return
                Tuning.write("/proc/gpufreqv2/fix_target_opp_index", idx.toString())
            }
            Kind.MTK_V1 -> Tuning.write("/proc/gpufreq/gpufreq_opp_freq", (khz ?: 0L).toString())
        }
    }

    fun reset(s: State) {
        Prefs.defaultGpuGovernor.takeIf { it.isNotEmpty() && it in s.governors }?.let { setGovernor(s, it) }
        setFreq(s, null)
    }
}

/** Ajustes de la GPU (gobernador y frecuencia) para cualquier procesador. */
@Composable
fun GpuTuningCard() {
    val win = LocalWin.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<GpuControl.State?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        state = withContext(Dispatchers.IO) { GpuControl.read() }
        loaded = true
    }

    fun run(action: () -> Unit) {
        busy = true
        scope.launch {
            state = withContext(Dispatchers.IO) {
                action()
                GpuControl.read()
            }
            busy = false
        }
    }

    val s = state
    val reset: (() -> Unit)? = if (s == null) null else {
        val target: GpuControl.State = s
        ({ run { GpuControl.reset(target) } })
    }
    TuneCard(tr("Ajustes de la GPU"), reset) {
        when {
            !loaded -> Text(tr("Cargando…"), fontSize = 12.sp, color = win.sub)
            s == null -> Text(tr("No se pueden cambiar estos ajustes en este teléfono."), fontSize = 12.sp, color = win.sub)
            else -> {
                Text(tr("Controlador: %s", s.kind.label), fontSize = 12.sp, color = win.sub)
                if (s.governors.isNotEmpty()) {
                    TuneRow(tr("Gobernador")) {
                        TuneDropdown(s.governor ?: "—", s.governors, !busy) { idx -> run { GpuControl.setGovernor(s, s.governors[idx]) } }
                    }
                }
                val pinned = if (s.fixed) null else GpuControl.pinnedLabel(s)
                if (pinned != null) {
                    TuneRow(tr("Frecuencia")) { Text(pinned, fontSize = 13.sp, color = win.text) }
                    Text(
                        if (s.governor == "performance") tr("Con este gobernador la GPU trabaja siempre a la máxima frecuencia.")
                        else tr("Con este gobernador la GPU trabaja siempre a la mínima frecuencia."),
                        fontSize = 11.sp, color = win.sub,
                    )
                } else if (s.freqsKhz.isNotEmpty()) {
                    val auto = if (s.fixed) tr("Automática") else tr("Sin límite")
                    val options = listOf(auto) + s.freqsKhz.map(::mhz)
                    val current = s.currentKhz
                    val label = when {
                        current == null -> auto
                        !s.fixed && current == s.freqsKhz.maxOrNull() -> auto
                        else -> mhz(current)
                    }
                    TuneRow(if (s.fixed) tr("Frecuencia fija") else tr("Frecuencia máxima")) {
                        TuneDropdown(label, options, !busy) { idx ->
                            run { GpuControl.setFreq(s, if (idx == 0) null else s.freqsKhz[idx - 1]) }
                        }
                    }
                    if (s.fixed) {
                        Text(
                            tr("En este procesador la GPU se fija a una frecuencia. Elige Automática para volver al control normal."),
                            fontSize = 11.sp, color = win.sub,
                        )
                    }
                }
            }
        }
    }
}
