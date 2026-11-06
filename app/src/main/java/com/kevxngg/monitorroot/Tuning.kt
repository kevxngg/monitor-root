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
 * Piezas compartidas por los ajustes de la CPU (CpuPower.kt) y de la GPU
 * (GpuControl.kt). Los cambios duran hasta reiniciar el teléfono.
 */
object Tuning {
    /** Escribe un valor en un archivo del sistema (sysfs/procfs) con root. */
    fun write(file: String, value: String) {
        RootShell.run("echo ${Actions.q(value)} > ${Actions.q(file)}")
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
                .clip(corner(4))
                .background(win.content)
                .border(1.dp, win.border, corner(4))
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
            .clip(corner(8))
            .background(win.card)
            .border(1.dp, win.border, corner(8))
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
