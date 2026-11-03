package com.kevxngg.monitorroot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ajustes avanzados con root: gobernador y frecuencia máxima de cada clúster de la
 * CPU y de la GPU. Los cambios duran hasta reiniciar el teléfono.
 */
object Tuning {
    class Cluster(
        val path: String,
        val cpus: String,
        val governor: String?,
        val governors: List<String>,
        val maxKhz: Long?,
        val hwMaxKhz: Long?,
        val freqsKhz: List<Long>,
    )

    private fun parseBlocks(lines: List<String>): List<Pair<String, Map<String, String>>> {
        val out = ArrayList<Pair<String, MutableMap<String, String>>>()
        for (l in lines) {
            if (l.startsWith("@@")) out.add(l.removePrefix("@@").trim() to mutableMapOf())
            else {
                val i = l.indexOf('=')
                if (i > 0 && out.isNotEmpty()) out.last().second[l.substring(0, i)] = l.substring(i + 1).trim()
            }
        }
        return out
    }

    private fun nums(s: String?) = s?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }.orEmpty()
    private fun words(s: String?) = s?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty()

    fun cpuClusters(): List<Cluster> {
        val lines = RootShell.run(
            "for p in /sys/devices/system/cpu/cpufreq/policy*; do " +
                "echo \"@@\$p\"; " +
                "echo \"cpus=\$(cat \$p/related_cpus)\"; " +
                "echo \"gov=\$(cat \$p/scaling_governor)\"; " +
                "echo \"govs=\$(cat \$p/scaling_available_governors)\"; " +
                "echo \"max=\$(cat \$p/scaling_max_freq)\"; " +
                "echo \"hwmax=\$(cat \$p/cpuinfo_max_freq)\"; " +
                "echo \"freqs=\$(cat \$p/scaling_available_frequencies)\"; " +
                "done"
        )
        return parseBlocks(lines).map { (path, m) ->
            val cpus = words(m["cpus"]).mapNotNull { it.toIntOrNull() }
            Cluster(
                path = path,
                cpus = if (cpus.isEmpty()) "?" else if (cpus.size == 1) "${cpus[0]}" else "${cpus.first()}–${cpus.last()}",
                governor = m["gov"]?.takeIf { it.isNotBlank() },
                governors = words(m["govs"]),
                maxKhz = nums(m["max"]).firstOrNull(),
                hwMaxKhz = nums(m["hwmax"]).firstOrNull(),
                freqsKhz = nums(m["freqs"]).distinct().sorted(),
            )
        }
    }

    fun write(file: String, value: String) {
        RootShell.run("echo ${Actions.q(value)} > ${Actions.q(file)}")
    }

    fun setCpuGovernor(c: Cluster, gov: String) {
        val cur = c.governor
        if (Prefs.defaultGovernor.isEmpty() && cur != null && cur !in listOf("performance", "powersave", "conservative")) {
            Prefs.defaultGovernor = cur
            Prefs.save()
        }
        write("${c.path}/scaling_governor", gov)
    }
    fun setCpuMax(c: Cluster, khz: Long) = write("${c.path}/scaling_max_freq", khz.toString())

    /** Vuelve a los valores de fábrica (gobernador original y frecuencias máximas). */
    fun resetCpu(clusters: List<Cluster>) {
        val balanced = Prefs.defaultGovernor
        clusters.forEach { c ->
            if (balanced.isNotEmpty() && balanced in c.governors) setCpuGovernor(c, balanced)
            (c.hwMaxKhz ?: c.freqsKhz.maxOrNull())?.let { setCpuMax(c, it) }
        }
    }
}

fun mhz(khz: Long) = "${khz / 1000} MHz"

@Composable
fun TuneDropdown(value: String, options: List<String>, enabled: Boolean, onSelect: (Int) -> Unit) {
    val win = LocalWin.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .widthIn(min = 120.dp, max = 190.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(win.content)
                .border(1.dp, win.border, RoundedCornerShape(4.dp))
                .clickable(enabled = enabled && options.isNotEmpty()) { open = true }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                value, fontSize = 13.sp, color = if (enabled) win.text else win.sub,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Outlined.KeyboardArrowDown, null, tint = win.sub, modifier = Modifier.size(16.dp))
        }
        WinMenu(open, { open = false }, options.mapIndexed { i, o -> MenuEntry(o, checked = o == value) { onSelect(i) } })
    }
}

@Composable
fun TuneRow(title: String, content: @Composable () -> Unit) {
    val win = LocalWin.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 13.sp, color = win.sub, modifier = Modifier.weight(1f))
        content()
    }
}

@Composable
fun TuneCard(title: String, onReset: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    val win = LocalWin.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(win.card)
            .border(1.dp, win.border, RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = win.text, modifier = Modifier.weight(1f))
            if (onReset != null) TextButton(onClick = onReset) { Text(tr("Restablecer")) }
        }
        content()
        Text(
            tr("El cambio dura hasta que reinicies el teléfono.") + " " + tr("Algunos fabricantes restablecen estos valores automáticamente."),
            fontSize = 11.sp, color = win.sub,
        )
    }
}

/** Gobernador y frecuencia máxima de cada clúster de la CPU. */
@Composable
fun CpuTuningCard() {
    val win = LocalWin.current
    val scope = rememberCoroutineScope()
    var clusters by remember { mutableStateOf<List<Tuning.Cluster>?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { clusters = withContext(Dispatchers.IO) { Tuning.cpuClusters() } }

    fun run(action: () -> Unit) {
        busy = true
        scope.launch {
            clusters = withContext(Dispatchers.IO) {
                action()
                Tuning.cpuClusters()
            }
            busy = false
        }
    }

    val list = clusters
    val resetCpu: (() -> Unit)? = if (list.isNullOrEmpty()) null else {
        val all: List<Tuning.Cluster> = list
        ({ run { Tuning.resetCpu(all) } })
    }
    TuneCard(tr("Ajustes avanzados de la CPU"), resetCpu) {
        when {
            list == null -> Text(tr("Cargando…"), fontSize = 12.sp, color = win.sub)
            list.isEmpty() -> Text(tr("No se pueden cambiar estos ajustes en este teléfono."), fontSize = 12.sp, color = win.sub)
            else -> list.forEachIndexed { i, c ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(win.divider))
                Text(tr("Clúster %d (CPU %s)", i, c.cpus), fontSize = 13.sp, color = win.text)
                TuneRow(tr("Gobernador")) {
                    TuneDropdown(c.governor ?: "—", c.governors, !busy) { idx -> run { Tuning.setCpuGovernor(c, c.governors[idx]) } }
                }
                if (c.freqsKhz.isNotEmpty()) {
                    TuneRow(tr("Frecuencia máxima")) {
                        TuneDropdown(c.maxKhz?.let(::mhz) ?: "—", c.freqsKhz.map(::mhz), !busy) { idx ->
                            run { Tuning.setCpuMax(c, c.freqsKhz[idx]) }
                        }
                    }
                }
            }
        }
    }
}

