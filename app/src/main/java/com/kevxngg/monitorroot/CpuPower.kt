package com.kevxngg.monitorroot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Energía de la CPU: una sola fuente de verdad para el modo de energía y los
 * ajustes avanzados. Todo se lee de cada clúster (cpufreq/policyN).
 *
 * Cómo se comporta cada gobernador:
 *  - performance: la CPU queda fija en la frecuencia máxima.
 *  - powersave:   la CPU queda fija en la frecuencia mínima.
 *  - userspace:   la CPU queda fija en la frecuencia que elijas (scaling_setspeed).
 *  - el resto (schedutil, walt, interactive…): la frecuencia sube y baja sola
 *    según la carga, entre la mínima y la máxima que elijas.
 */
object CpuPower {
    enum class Kind { FIXED_MAX, FIXED_MIN, MANUAL, DYNAMIC }
    enum class Mode { SAVER, BALANCED, PERFORMANCE }

    class Cluster(
        val path: String,
        val cpus: String,
        val governor: String?,
        val governors: List<String>,
        val minKhz: Long?,
        val maxKhz: Long?,
        val hwMinKhz: Long?,
        val hwMaxKhz: Long?,
        val setspeedKhz: Long?,
        val freqsKhz: List<Long>,
        /** Frecuencia a la que va el clúster en este momento. */
        val curKhz: Long? = null,
    ) {
        val kind: Kind get() = kindOf(governor)
        val atHwMax: Boolean get() = hwMaxKhz == null || maxKhz == hwMaxKhz
        val atHwMin: Boolean get() = hwMinKhz == null || minKhz == hwMinKhz
    }

    /** Lo que había antes de un cambio temporal (por ejemplo, el modo juego). */
    class Snapshot(val clusters: List<Cluster>, val customLimits: Boolean = Prefs.cpuCustomLimits)

    private val SAVER_GOVS = listOf("powersave", "conservative")
    private val DYNAMIC_PREFERRED = listOf("walt", "sched_pixel", "schedutil", "interactive", "ondemand")

    fun kindOf(gov: String?): Kind = when (gov) {
        "performance" -> Kind.FIXED_MAX
        "powersave" -> Kind.FIXED_MIN
        "userspace" -> Kind.MANUAL
        else -> Kind.DYNAMIC
    }

    /** Gobernador "de fábrica" del clúster: el que se usa en el modo equilibrado. */
    fun balancedGov(c: Cluster, saved: String = Prefs.defaultGovernor): String? =
        saved.takeIf { it.isNotEmpty() && it in c.governors }
            ?: c.governors.firstOrNull { it in DYNAMIC_PREFERRED }
            ?: c.governor?.takeIf { kindOf(it) == Kind.DYNAMIC }

    fun saverGov(c: Cluster): String? = SAVER_GOVS.firstOrNull { it in c.governors }

    /**
     * Modo actual. Se reconoce por los gobernadores de todos los clústeres y por
     * si el usuario cambió límites a mano ([customLimits]). No se miran los valores
     * de frecuencia del momento: el sistema los mueve solo (touch boost, control
     * térmico) y el modo sigue siendo el mismo.
     * null = personalizado.
     */
    fun modeOf(
        clusters: List<Cluster>,
        saved: String = Prefs.defaultGovernor,
        customLimits: Boolean = Prefs.cpuCustomLimits,
    ): Mode? = when {
        clusters.isEmpty() || customLimits -> null
        clusters.all { it.governor == "performance" } -> Mode.PERFORMANCE
        clusters.all { it.governor != null && it.governor == saverGov(it) } -> Mode.SAVER
        clusters.all { it.governor != null && it.governor == balancedGov(it, saved) } -> Mode.BALANCED
        else -> null
    }

    /**
     * Máxima que el sistema impone ahora por su cuenta (por ejemplo por calor),
     * o null si no está limitando. Solo se informa si el usuario no puso un límite.
     */
    fun systemCapKhz(c: Cluster, customLimits: Boolean = Prefs.cpuCustomLimits): Long? {
        if (customLimits || c.kind == Kind.FIXED_MIN) return null
        val hw = c.hwMaxKhz ?: return null
        val max = c.maxKhz ?: return null
        return if (max < hw) max else null
    }

    fun isAvailable(mode: Mode, clusters: List<Cluster>): Boolean = clusters.isNotEmpty() && when (mode) {
        Mode.PERFORMANCE -> clusters.all { "performance" in it.governors }
        Mode.SAVER -> clusters.all { saverGov(it) != null }
        Mode.BALANCED -> clusters.all { balancedGov(it) != null }
    }

    // ---------------- Lectura ----------------

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

    private fun longs(s: String?) = s?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }.orEmpty()
    private fun words(s: String?) = s?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty()

    // "§" se cambia por "$" para que el script sea legible dentro de Kotlin
    private val READ = """
        for p in /sys/devices/system/cpu/cpufreq/policy*; do
          echo "@@§p"
          echo "cpus=§(cat §p/related_cpus)"
          echo "gov=§(cat §p/scaling_governor)"
          echo "govs=§(cat §p/scaling_available_governors)"
          echo "min=§(cat §p/scaling_min_freq)"
          echo "max=§(cat §p/scaling_max_freq)"
          echo "hwmin=§(cat §p/cpuinfo_min_freq)"
          echo "hwmax=§(cat §p/cpuinfo_max_freq)"
          echo "setspeed=§(cat §p/scaling_setspeed)"
          echo "cur=§(cat §p/scaling_cur_freq)"
          echo "freqs=§(cat §p/scaling_available_frequencies)"
        done
    """.trimIndent().replace("§", "$")

    fun read(): List<Cluster> {
        val clusters = parseBlocks(RootShell.run(READ)).map { (path, m) ->
            val cpus = words(m["cpus"]).mapNotNull { it.toIntOrNull() }
            Cluster(
                path = path,
                cpus = when {
                    cpus.isEmpty() -> "?"
                    cpus.size == 1 -> "${cpus[0]}"
                    else -> "${cpus.first()}–${cpus.last()}"
                },
                governor = m["gov"]?.takeIf { it.isNotBlank() },
                governors = words(m["govs"]),
                minKhz = longs(m["min"]).firstOrNull(),
                maxKhz = longs(m["max"]).firstOrNull(),
                hwMinKhz = longs(m["hwmin"]).firstOrNull(),
                hwMaxKhz = longs(m["hwmax"]).firstOrNull(),
                setspeedKhz = longs(m["setspeed"]).firstOrNull(),
                freqsKhz = longs(m["freqs"]).distinct().sorted(),
                curKhz = longs(m["cur"]).firstOrNull(),
            )
        }
        rememberFactoryGovernor(clusters)
        return clusters
    }

    /** La primera vez se guarda el gobernador dinámico de fábrica (modo equilibrado). */
    private fun rememberFactoryGovernor(clusters: List<Cluster>) {
        if (Prefs.defaultGovernor.isNotEmpty()) return
        val factory = clusters.firstNotNullOfOrNull { c ->
            c.governor?.takeIf { kindOf(it) == Kind.DYNAMIC && it != "conservative" }
        } ?: return
        Prefs.defaultGovernor = factory
        Prefs.save()
    }

    // ---------------- Escritura ----------------

    private fun write(file: String, value: Any) = Tuning.write(file, value.toString())

    private fun hwMax(c: Cluster) = c.hwMaxKhz ?: c.freqsKhz.maxOrNull()
    private fun hwMin(c: Cluster) = c.hwMinKhz ?: c.freqsKhz.minOrNull()

    /**
     * Cambia el gobernador y deja la frecuencia coherente con él:
     *  - performance: el tope máximo vuelve a la máxima real del chip.
     *  - powersave: la mínima y también la máxima quedan en la mínima real.
     *    Sin el tope, servicios del fabricante (como el "touch boost" de
     *    MediaTek) suben la mínima al tocar la pantalla y powersave la sigue.
     *    El kernel nunca deja que la mínima supere a la máxima.
     *  - userspace: arranca en la frecuencia máxima actual.
     */
    fun setGovernor(c: Cluster, gov: String) {
        val newKind = kindOf(gov)
        // Al salir de powersave se quita el tope que puso Monitor
        if (c.kind == Kind.FIXED_MIN && newKind != Kind.FIXED_MIN) {
            hwMax(c)?.let { write("${c.path}/scaling_max_freq", it) }
        }
        write("${c.path}/scaling_governor", gov)
        when (newKind) {
            Kind.FIXED_MAX -> hwMax(c)?.let { write("${c.path}/scaling_max_freq", it) }
            Kind.FIXED_MIN -> hwMin(c)?.let {
                write("${c.path}/scaling_min_freq", it)
                write("${c.path}/scaling_max_freq", it)
            }
            Kind.MANUAL -> (if (c.kind == Kind.FIXED_MIN) hwMax(c) else c.maxKhz ?: hwMax(c))
                ?.let { write("${c.path}/scaling_setspeed", it) }
            Kind.DYNAMIC -> Unit
        }
    }

    /** Límite máximo; si queda por debajo del mínimo, el mínimo baja con él. */
    private fun markCustom() {
        if (!Prefs.cpuCustomLimits) {
            Prefs.cpuCustomLimits = true
            Prefs.save()
        }
    }

    fun setMax(c: Cluster, khz: Long) {
        markCustom()
        if (c.minKhz != null && khz < c.minKhz) write("${c.path}/scaling_min_freq", khz)
        write("${c.path}/scaling_max_freq", khz)
    }

    /** Límite mínimo; si queda por encima del máximo, el máximo sube con él. */
    fun setMin(c: Cluster, khz: Long) {
        markCustom()
        if (c.maxKhz != null && khz > c.maxKhz) write("${c.path}/scaling_max_freq", khz)
        write("${c.path}/scaling_min_freq", khz)
    }

    /** Frecuencia exacta (solo con el gobernador userspace). */
    fun setSpeed(c: Cluster, khz: Long) {
        markCustom()
        write("${c.path}/scaling_setspeed", khz)
    }

    /** Deja el clúster como viene de fábrica: gobernador dinámico y límites completos. */
    private fun resetCluster(c: Cluster) {
        balancedGov(c)?.let { write("${c.path}/scaling_governor", it) }
        hwMin(c)?.let { write("${c.path}/scaling_min_freq", it) }
        hwMax(c)?.let { write("${c.path}/scaling_max_freq", it) }
    }

    fun apply(mode: Mode, clusters: List<Cluster>) {
        Prefs.cpuCustomLimits = false
        Prefs.save()
        clusters.forEach { c ->
            when (mode) {
                Mode.BALANCED -> resetCluster(c)
                Mode.PERFORMANCE -> if ("performance" in c.governors) {
                    hwMin(c)?.let { write("${c.path}/scaling_min_freq", it) }
                    setGovernor(c, "performance")
                }
                Mode.SAVER -> saverGov(c)?.let { gov ->
                    hwMax(c)?.let { write("${c.path}/scaling_max_freq", it) }
                    setGovernor(c, gov)
                }
            }
        }
    }

    fun snapshot(): Snapshot = Snapshot(read())

    /** Vuelve exactamente a como estaba cada clúster. */
    fun restore(s: Snapshot) {
        Prefs.cpuCustomLimits = s.customLimits
        Prefs.save()
        s.clusters.forEach { c ->
            c.governor?.let { write("${c.path}/scaling_governor", it) }
            // Se ensancha primero el rango para que el kernel acepte los dos límites
            hwMax(c)?.let { write("${c.path}/scaling_max_freq", it) }
            c.minKhz?.let { write("${c.path}/scaling_min_freq", it) }
            c.maxKhz?.let { write("${c.path}/scaling_max_freq", it) }
            if (c.kind == Kind.MANUAL) c.setspeedKhz?.let { write("${c.path}/scaling_setspeed", it) }
        }
    }
}

// =====================================================================
// Interfaz: modo de energía + ajustes avanzados, con el mismo estado
// =====================================================================

@Composable
fun CpuPowerSection() {
    val scope = rememberCoroutineScope()
    var clusters by remember { mutableStateOf<List<CpuPower.Cluster>?>(null) }
    var busy by remember { mutableStateOf(false) }

    // Se vuelve a leer cada pocos segundos: algunos fabricantes cambian los valores solos
    LaunchedEffect(Unit) {
        while (true) {
            if (!busy) clusters = withContext(Dispatchers.IO) { CpuPower.read() }
            delay(5000)
        }
    }

    fun run(action: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            clusters = withContext(Dispatchers.IO) {
                action()
                CpuPower.read()
            }
            busy = false
        }
    }

    val list = clusters
    PowerModeCard(list, busy) { mode -> run { CpuPower.apply(mode, list.orEmpty()) } }
    CpuAdvancedCard(list, busy, ::run)
}

@Composable
private fun PowerModeCard(clusters: List<CpuPower.Cluster>?, busy: Boolean, onSelect: (CpuPower.Mode) -> Unit) {
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
        Text(tr("Modo de energía"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = win.text)
        Text(tr("Cambia cómo la CPU equilibra rendimiento y batería."), fontSize = 12.sp, color = win.sub)
        if (clusters == null) {
            Text(tr("Cargando…"), fontSize = 12.sp, color = win.sub)
            return@Column
        }
        if (clusters.isEmpty()) {
            Text(tr("No se pueden cambiar estos ajustes en este teléfono."), fontSize = 12.sp, color = win.sub)
            return@Column
        }
        val current = CpuPower.modeOf(clusters)
        val options = listOf(
            CpuPower.Mode.SAVER to tr("Mejor eficiencia energética"),
            CpuPower.Mode.BALANCED to tr("Equilibrado"),
            CpuPower.Mode.PERFORMANCE to tr("Mejor rendimiento"),
        )
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).border(1.dp, win.border, RoundedCornerShape(6.dp))) {
            options.forEachIndexed { i, (mode, label) ->
                val selected = mode == current
                val enabled = !busy && !selected && CpuPower.isAvailable(mode, clusters)
                if (i > 0) Box(Modifier.width(1.dp).height(52.dp).background(win.border))
                Box(
                    Modifier
                        .weight(1f)
                        .height(52.dp)
                        .background(if (selected) win.accent else Color.Transparent)
                        .then(if (enabled) Modifier.clickable { onSelect(mode) } else Modifier)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2,
                        color = when {
                            selected -> if (win.dark) win.mica else Color.White
                            !CpuPower.isAvailable(mode, clusters) -> win.sub.copy(alpha = 0.5f)
                            else -> win.text
                        },
                    )
                }
            }
        }
        Text(
            when (current) {
                CpuPower.Mode.SAVER -> tr("La CPU trabaja a la mínima frecuencia para ahorrar batería.")
                CpuPower.Mode.BALANCED -> tr("La frecuencia sube y baja sola según la carga, como viene de fábrica.")
                CpuPower.Mode.PERFORMANCE -> tr("La CPU trabaja siempre a la máxima frecuencia.")
                null -> tr("Personalizado: cambiaste los ajustes avanzados de la CPU.")
            },
            fontSize = 12.sp, color = if (current == null) win.accent else win.sub,
        )
        if (current == CpuPower.Mode.PERFORMANCE && clusters.any { CpuPower.systemCapKhz(it) != null }) {
            Text(
                tr("El sistema está limitando la frecuencia ahora, normalmente por temperatura. Vuelve a la máxima cuando se enfría."),
                fontSize = 11.sp, color = win.warn,
            )
        }
    }
}

@Composable
private fun CpuAdvancedCard(clusters: List<CpuPower.Cluster>?, busy: Boolean, run: (() -> Unit) -> Unit) {
    val win = LocalWin.current
    val reset: (() -> Unit)? = if (clusters.isNullOrEmpty()) null else {
        val all: List<CpuPower.Cluster> = clusters
        ({ run { CpuPower.apply(CpuPower.Mode.BALANCED, all) } })
    }
    TuneCard(tr("Ajustes avanzados de la CPU"), reset) {
        when {
            clusters == null -> Text(tr("Cargando…"), fontSize = 12.sp, color = win.sub)
            clusters.isEmpty() -> Text(tr("No se pueden cambiar estos ajustes en este teléfono."), fontSize = 12.sp, color = win.sub)
            else -> clusters.forEachIndexed { i, c ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(win.divider))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tr("Clúster %d (CPU %s)", i, c.cpus), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = win.text, modifier = Modifier.weight(1f),
                    )
                    c.curKhz?.let { Text(tr("Ahora: %s", mhz(it)), fontSize = 12.sp, color = win.accent) }
                }
                TuneRow(tr("Gobernador")) {
                    TuneDropdown(c.governor ?: "—", c.governors, !busy) { idx -> run { CpuPower.setGovernor(c, c.governors[idx]) } }
                }
                val freqs = c.freqsKhz
                when (c.kind) {
                    CpuPower.Kind.FIXED_MAX -> FixedRow(tr("Fija en la máxima"), c.maxKhz)
                    CpuPower.Kind.FIXED_MIN -> FixedRow(tr("Fija en la mínima"), listOfNotNull(c.minKhz, c.maxKhz).minOrNull())
                    CpuPower.Kind.MANUAL -> if (freqs.isNotEmpty()) {
                        TuneRow(tr("Frecuencia fija")) {
                            TuneDropdown(c.setspeedKhz?.let(::mhz) ?: "—", freqs.map(::mhz), !busy) { idx ->
                                run { CpuPower.setSpeed(c, freqs[idx]) }
                            }
                        }
                    }
                    CpuPower.Kind.DYNAMIC -> if (freqs.isNotEmpty()) {
                        TuneRow(tr("Frecuencia mínima")) {
                            TuneDropdown(c.minKhz?.let(::mhz) ?: "—", freqs.map(::mhz), !busy) { idx ->
                                run { CpuPower.setMin(c, freqs[idx]) }
                            }
                        }
                        TuneRow(tr("Frecuencia máxima")) {
                            TuneDropdown(c.maxKhz?.let(::mhz) ?: "—", freqs.map(::mhz), !busy) { idx ->
                                run { CpuPower.setMax(c, freqs[idx]) }
                            }
                        }
                    }
                }
                CpuPower.systemCapKhz(c)?.let { cap ->
                    Text(
                        tr("Ahora el sistema limita la máxima a %s (por temperatura o ahorro de energía).", mhz(cap)),
                        fontSize = 11.sp, color = win.warn,
                    )
                }
                Text(
                    when (c.kind) {
                        CpuPower.Kind.FIXED_MAX -> tr("Con este gobernador la CPU trabaja siempre a la máxima frecuencia.")
                        CpuPower.Kind.FIXED_MIN -> tr("Con este gobernador la CPU trabaja siempre a la mínima frecuencia.")
                        CpuPower.Kind.MANUAL -> tr("Con este gobernador la CPU trabaja siempre a la frecuencia que elijas.")
                        CpuPower.Kind.DYNAMIC -> tr("La frecuencia sube y baja sola según la carga, dentro de estos límites.")
                    },
                    fontSize = 11.sp, color = win.sub,
                )
            }
        }
    }
}

@Composable
private fun FixedRow(label: String, khz: Long?) {
    val win = LocalWin.current
    TuneRow(tr("Frecuencia")) {
        Text("$label: ${khz?.let(::mhz) ?: "—"}", fontSize = 13.sp, color = win.text)
    }
}
