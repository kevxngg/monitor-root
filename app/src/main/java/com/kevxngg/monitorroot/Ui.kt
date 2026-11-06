package com.kevxngg.monitorroot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ===================== MODIFICADORES =====================

fun Modifier.bottomLine(color: Color) = drawBehind {
    drawLine(color, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
}

// ===================== GRÁFICA =====================

/** Gráfica con cuadrícula y relleno; el valor más nuevo queda a la derecha. */
@Composable
fun TaskGraph(
    values: List<Float>,
    max: Float,
    color: Color,
    modifier: Modifier,
    second: List<Float>? = null,
    grid: Boolean = true,
) {
    val win = LocalWin.current
    val fillAlpha = if (win.dark) 0.35f else 0.18f
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val step = w / (HISTORY - 1)
        val top = if (max <= 0f) 1f else max
        if (grid) {
            val g = color.copy(alpha = if (win.dark) 0.22f else 0.14f)
            for (i in 1 until 10) {
                val y = h * i / 10f
                drawLine(g, Offset(0f, y), Offset(w, y), 1f)
            }
            for (i in 1 until 20) {
                val x = w * i / 20f
                drawLine(g, Offset(x, 0f), Offset(x, h), 1f)
            }
        }
        fun pathFor(v: List<Float>, asFill: Boolean): Path? {
            if (v.size < 2) return null
            val p = Path()
            v.forEachIndexed { i, value ->
                val x = w - (v.size - 1 - i) * step
                val y = h * (1f - (value / top).coerceIn(0f, 1f))
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            if (asFill) {
                p.lineTo(w, h)
                p.lineTo(w - (v.size - 1) * step, h)
                p.close()
            }
            return p
        }
        pathFor(values, true)?.let { drawPath(it, color.copy(alpha = fillAlpha)) }
        pathFor(values, false)?.let { drawPath(it, color, style = Stroke(width = 1.2.dp.toPx())) }
        if (second != null) {
            pathFor(second, false)?.let {
                drawPath(
                    it, color,
                    style = Stroke(width = 1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 6f))),
                )
            }
        }
        drawRect(color.copy(alpha = 0.9f), style = Stroke(width = 1.dp.toPx()))
    }
}

// ===================== BARRA DE COMANDOS =====================

@Composable
fun PageHeader(title: String, actions: @Composable RowScope.() -> Unit) {
    val win = LocalWin.current
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = win.text,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        actions()
    }
}

@Composable
fun CmdButton(icon: ImageVector, text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val win = LocalWin.current
    val compact = LocalCompact.current
    val tint = if (enabled) win.text else win.sub.copy(alpha = 0.5f)
    Row(
        Modifier
            .clip(corner(4))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = text, tint = tint, modifier = Modifier.size(18.dp))
        if (!compact) {
            Spacer(Modifier.width(6.dp))
            Text(text, fontSize = 13.sp, color = tint)
        }
    }
}

class MenuEntry(
    val text: String,
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
    val checked: Boolean? = null,
    val onClick: () -> Unit,
)

@Composable
fun WinMenu(expanded: Boolean, onDismiss: () -> Unit, entries: List<MenuEntry>) {
    val win = LocalWin.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        entries.forEach { e ->
            val chk = e.checked
            val ic = e.icon
            val lead: (@Composable () -> Unit)? = when {
                chk != null -> {
                    {
                        if (chk) Icon(Icons.Outlined.Check, null, tint = win.accent, modifier = Modifier.size(18.dp))
                        else Spacer(Modifier.size(18.dp))
                    }
                }
                ic != null -> {
                    { Icon(ic, null, tint = if (e.enabled) win.text else win.sub, modifier = Modifier.size(18.dp)) }
                }
                else -> null
            }
            DropdownMenuItem(
                text = { Text(e.text, fontSize = 13.sp, color = if (e.enabled) win.text else win.sub) },
                leadingIcon = lead,
                enabled = e.enabled,
                onClick = {
                    onDismiss()
                    e.onClick()
                },
            )
        }
    }
}

@Composable
fun CmdMore(entries: List<MenuEntry>) {
    val win = LocalWin.current
    var open by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.clip(corner(4)).clickable { open = true }.padding(7.dp)) {
            Icon(Icons.Outlined.MoreHoriz, tr("Más"), tint = win.text, modifier = Modifier.size(18.dp))
        }
        WinMenu(open, { open = false }, entries)
    }
}

// ===================== TABLA =====================

class Col(val id: String, val title: String, val width: Dp, val top: String? = null, val end: Boolean = true)

class Cell(
    val text: String,
    val heat: Float? = null,
    val icon: ImageVector? = null,
    val iconTint: Color? = null,
)

/** Ancho de la columna Nombre: llena la pantalla si sobra espacio. */
fun nameWidth(total: Dp, cols: List<Col>, min: Dp = 170.dp): Dp {
    val rest = cols.fold(0.dp) { acc, c -> acc + c.width }
    val w = total - rest
    return if (w < min) min else w
}

@Composable
fun TableHeader(
    nameTitle: String,
    nameW: Dp,
    cols: List<Col>,
    h: ScrollState,
    sortId: String,
    asc: Boolean,
    onSort: (String) -> Unit,
) {
    val win = LocalWin.current
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).bottomLine(win.border)) {
        HeaderCell(nameTitle, null, sortId == "name", asc, nameW, false) { onSort("name") }
        Row(Modifier.horizontalScroll(h)) {
            cols.forEach { c ->
                Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 6.dp).background(win.divider))
                HeaderCell(c.title, c.top, sortId == c.id, asc, c.width - 1.dp, c.end) { onSort(c.id) }
            }
        }
    }
}

@Composable
private fun HeaderCell(title: String, top: String?, active: Boolean, asc: Boolean, width: Dp, end: Boolean, onClick: () -> Unit) {
    val win = LocalWin.current
    val align = if (end) Alignment.End else Alignment.Start
    Column(
        Modifier.width(width).fillMaxHeight().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = align,
        verticalArrangement = Arrangement.Bottom,
    ) {
        Box(Modifier.fillMaxWidth().height(12.dp), contentAlignment = Alignment.Center) {
            if (active) {
                Icon(
                    if (asc) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    null, tint = win.sub, modifier = Modifier.size(14.dp),
                )
            }
        }
        if (top != null) Text(top, fontSize = 15.sp, color = win.text, maxLines = 1)
        Text(title, fontSize = 12.sp, color = win.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TableRow(
    nameW: Dp,
    cols: List<Col>,
    cells: List<Cell>,
    h: ScrollState,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    menu: @Composable () -> Unit = {},
    height: Dp = 34.dp,
    name: @Composable RowScope.() -> Unit,
) {
    val win = LocalWin.current
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(height)
                .background(if (selected) win.select else Color.Transparent)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.width(nameW).fillMaxHeight().padding(start = 8.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = name,
            )
            Row(Modifier.fillMaxHeight().horizontalScroll(h)) {
                cols.forEachIndexed { i, c ->
                    val cell = cells.getOrNull(i) ?: Cell("")
                    val bg = if (!selected && cell.heat != null) win.heat(cell.heat) else Color.Transparent
                    Row(
                        Modifier.width(c.width).fillMaxHeight().padding(start = 1.dp).background(bg).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = if (c.end) Arrangement.End else Arrangement.Start,
                    ) {
                        if (cell.icon != null) {
                            Icon(cell.icon, null, tint = cell.iconTint ?: win.sub, modifier = Modifier.size(16.dp))
                            if (cell.text.isNotEmpty()) Spacer(Modifier.width(4.dp))
                        }
                        Text(cell.text, fontSize = 12.5.sp, color = win.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        menu()
    }
}

@Composable
fun GroupTitle(text: String) {
    val win = LocalWin.current
    Text(
        text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = win.text,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 14.dp, bottom = 6.dp),
    )
}

@Composable
fun AppIcon(icon: ImageBitmap?, name: String, size: Dp = 18.dp) {
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(size))
    } else {
        val win = LocalWin.current
        Box(
            Modifier.size(size).clip(corner(3)).background(if (win.dark) Color(0xFF4A5A6A) else Color(0xFF8AA4BC)),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.take(1).uppercase(), fontSize = 10.sp, color = Color.White)
        }
    }
}

@Composable
fun Chevron(expanded: Boolean, visible: Boolean, onClick: () -> Unit) {
    val win = LocalWin.current
    Box(
        Modifier.size(22.dp).clip(corner(3)).then(if (visible) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (visible) {
            @Suppress("DEPRECATION")
            Icon(
                if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowRight,
                null, tint = win.sub, modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
fun EmptyText(text: String) {
    val win = LocalWin.current
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = win.sub, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

// ===================== DIÁLOGOS =====================

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 18.sp) },
        text = { Text(text, fontSize = 14.sp) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } },
    )
}

@Composable
fun PropertiesDialog(p: ProcInfo, uptimeSec: Long, onDismiss: () -> Unit) {
    val win = LocalWin.current
    var info by remember(p.pid) { mutableStateOf<List<Pair<String, String>>?>(null) }
    LaunchedEffect(p.pid) { info = withContext(Dispatchers.IO) { Actions.properties(p.pid) } }
    val started = (uptimeSec - p.startTicks / 100).coerceAtLeast(0)
    val base = listOf(
        tr("Nombre") to p.name,
        "PID" to p.pid.toString(),
        tr("Proceso padre (PPID)") to p.ppid.toString(),
        tr("Usuario") to p.user,
        tr("Estado") to stateText(p.state),
        tr("Prioridad") to "${priorityText(p.nice)} (nice ${p.nice})",
        tr("Subprocesos") to p.threads.toString(),
        tr("Memoria") to formatKb(p.rssKb),
        tr("Tiempo de CPU") to formatDuration(p.cpuTicks / 100),
        tr("En ejecución desde hace") to formatUptime(started),
    ) + listOfNotNull(p.pkg?.let { tr("Paquete") to it })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Propiedades"), fontSize = 18.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (base + (info ?: emptyList()).map { (k, v) -> tr(k) to v }).forEach { (k, v) ->
                    Column {
                        Text(k, fontSize = 11.sp, color = win.sub)
                        Text(v.ifBlank { "—" }, fontSize = 13.sp, color = win.text)
                    }
                }
                if (info == null) Text(tr("Cargando…"), fontSize = 12.sp, color = win.sub)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Cerrar")) } },
    )
}

@Composable
fun PriorityDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val win = LocalWin.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Establecer prioridad"), fontSize = 18.sp) },
        text = {
            Column {
                PRIORITIES.forEach { (label, value) ->
                    Row(
                        Modifier.fillMaxWidth().clip(corner(4)).clickable { onDismiss(); onPick(value) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = priorityText(current) == tr(label), onClick = { onDismiss(); onPick(value) })
                        Text(tr(label), fontSize = 14.sp, color = win.text)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } },
    )
}

@Composable
fun AffinityDialog(pid: Int, cores: Int, onDismiss: () -> Unit) {
    val win = LocalWin.current
    var mask by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(pid) { mask = withContext(Dispatchers.IO) { Actions.getAffinity(pid) } ?: ((1L shl cores) - 1) }
    val m = mask
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Afinidad del procesador"), fontSize = 18.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(tr("¿Qué núcleos pueden ejecutar este proceso?"), fontSize = 13.sp, color = win.sub)
                if (m != null) {
                    val all = (1L shl cores) - 1
                    Row(Modifier.fillMaxWidth().clickable { mask = if ((m and all) == all) 1L else all }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = (m and all) == all, onCheckedChange = { mask = if (it) all else 1L })
                        Text(tr("<Todos los procesadores>"), fontSize = 14.sp, color = win.text)
                    }
                    for (i in 0 until cores) {
                        val bit = 1L shl i
                        Row(Modifier.fillMaxWidth().clickable { mask = m xor bit }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = (m and bit) != 0L, onCheckedChange = { mask = if (it) m or bit else m and bit.inv() })
                            Text("CPU $i", fontSize = 14.sp, color = win.text)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = m != null && m != 0L,
                onClick = {
                    val v = m ?: return@TextButton
                    onDismiss()
                    Thread { Actions.setAffinity(pid, v) }.start()
                },
            ) { Text(tr("Aceptar")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } },
    )
}

/**
 * Repite [block] cada [intervalMs] solo mientras la app está en pantalla.
 * Al minimizarla se detiene y al volver sigue sola: no gasta batería por detrás.
 */
@Composable
fun RepeatWhileVisible(intervalMs: Long, key: Any? = Unit, block: suspend () -> Unit) {
    val owner = androidx.compose.ui.platform.LocalContext.current as? androidx.lifecycle.LifecycleOwner
    LaunchedEffect(owner, key) {
        val loop: suspend () -> Unit = {
            while (true) {
                block()
                kotlinx.coroutines.delay(intervalMs)
            }
        }
        if (owner != null) owner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { loop() } else loop()
    }
}
