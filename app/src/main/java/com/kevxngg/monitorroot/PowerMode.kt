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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Modo de energía (como en Windows 11): cambia el gobernador de la CPU con root.
 * El cambio dura hasta que se reinicia el teléfono.
 */
object PowerMode {
    enum class Mode { SAVER, BALANCED, PERFORMANCE }

    class State(val available: List<String>, val current: String?, val balanced: String?)

    private const val CPU = "/sys/devices/system/cpu"
    private val EFFICIENT = listOf("powersave", "conservative")

    fun read(): State {
        val out = RootShell.run(
            "cat $CPU/cpu0/cpufreq/scaling_available_governors; echo @@; cat $CPU/cpu0/cpufreq/scaling_governor"
        )
        val sep = out.indexOf("@@")
        val available = out.take(if (sep < 0) out.size else sep).joinToString(" ").split(Regex("\\s+")).filter { it.isNotBlank() }
        val current = if (sep >= 0) out.drop(sep + 1).firstOrNull()?.trim() else null
        // El gobernador "equilibrado" es el que trae el teléfono; se recuerda la primera vez
        if (Prefs.defaultGovernor.isEmpty() && current != null && current != "performance" && current !in EFFICIENT) {
            Prefs.defaultGovernor = current
            Prefs.save()
        }
        val balanced = Prefs.defaultGovernor.ifEmpty { current }
        return State(available, current, balanced)
    }

    fun governorFor(mode: Mode, s: State): String? = when (mode) {
        Mode.SAVER -> EFFICIENT.firstOrNull { it in s.available }
        Mode.BALANCED -> s.balanced?.takeIf { it in s.available }
        Mode.PERFORMANCE -> "performance".takeIf { it in s.available }
    }

    fun modeOf(s: State): Mode = when (s.current) {
        "performance" -> Mode.PERFORMANCE
        in EFFICIENT -> Mode.SAVER
        else -> Mode.BALANCED
    }

    fun setGovernor(gov: String) {
        // Se escribe en cada clúster (policy*) y en cada núcleo, según lo que exista
        RootShell.run(
            "for f in $CPU/cpufreq/policy*/scaling_governor $CPU/cpu*/cpufreq/scaling_governor; " +
                "do echo ${Actions.q(gov)} > \"\$f\"; done"
        )
    }
}

@Composable
fun PowerModeCard() {
    val win = LocalWin.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<PowerMode.State?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { state = withContext(Dispatchers.IO) { PowerMode.read() } }

    fun apply(gov: String) {
        busy = true
        scope.launch {
            state = withContext(Dispatchers.IO) {
                PowerMode.setGovernor(gov)
                PowerMode.read()
            }
            busy = false
        }
    }

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
        val s = state
        if (s == null) {
            Text(tr("Cargando…"), fontSize = 12.sp, color = win.sub)
            return@Column
        }
        val current = PowerMode.modeOf(s)
        val options = listOf(
            PowerMode.Mode.SAVER to tr("Mejor eficiencia energética"),
            PowerMode.Mode.BALANCED to tr("Equilibrado"),
            PowerMode.Mode.PERFORMANCE to tr("Mejor rendimiento"),
        )
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).border(1.dp, win.border, RoundedCornerShape(6.dp)),
        ) {
            options.forEachIndexed { i, (mode, label) ->
                val gov = PowerMode.governorFor(mode, s)
                val selected = mode == current
                val enabled = gov != null && !busy
                if (i > 0) Box(Modifier.width(1.dp).height(52.dp).background(win.border))
                Box(
                    Modifier
                        .weight(1f)
                        .height(52.dp)
                        .background(if (selected) win.accent else androidx.compose.ui.graphics.Color.Transparent)
                        .then(if (enabled && !selected) Modifier.clickable { apply(gov!!) } else Modifier)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2,
                        color = when {
                            selected -> if (win.dark) win.mica else androidx.compose.ui.graphics.Color.White
                            gov == null -> win.sub.copy(alpha = 0.5f)
                            else -> win.text
                        },
                    )
                }
            }
        }
        Text(
            tr("Gobernador actual: %s", s.current ?: "—") + "\n" + tr("El cambio dura hasta que reinicies el teléfono."),
            fontSize = 11.sp, color = win.sub,
        )
    }
}
