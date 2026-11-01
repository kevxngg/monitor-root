package com.kevxngg.monitorroot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val CPU_W = 66.dp
private val MEM_W = 86.dp

enum class SortBy { NAME, CPU, MEM, PID }

/** Una fila de la pestaña Procesos (una app puede juntar varios procesos). */
data class TaskRow(
    val key: String,
    val name: String,
    val icon: ImageBitmap?,
    val count: Int,
    val cpu: Float,
    val rssKb: Long,
    val pkg: String?,
    val pids: List<Int>,
    val critical: Boolean,
)

private fun visible(s: Snapshot) = s.processes.filter { it.rssKb > 0 || it.cpu >= 0.1f }

private fun buildGroups(s: Snapshot, sort: SortBy): List<List<TaskRow>> {
    val vis = visible(s)
    val apps = ArrayList<TaskRow>()
    val background = ArrayList<TaskRow>()
    vis.filter { it.pkg != null }.groupBy { it.pkg!! }.forEach { (pkg, list) ->
        val first = list.first()
        val row = TaskRow(
            key = "app:$pkg",
            name = first.label ?: pkg,
            icon = first.icon,
            count = list.size,
            cpu = list.sumOf { it.cpu.toDouble() }.toFloat(),
            rssKb = list.sumOf { it.rssKb },
            pkg = pkg,
            pids = list.map { it.pid },
            critical = !first.launchable,
        )
        if (first.launchable) apps.add(row) else background.add(row)
    }
    val system = vis.filter { it.pkg == null }.map {
        TaskRow("pid:${it.pid}", it.name, null, 1, it.cpu, it.rssKb, null, listOf(it.pid), true)
    }
    return listOf(apps, background, system).map { rows ->
        when (sort) {
            SortBy.NAME, SortBy.PID -> rows.sortedBy { it.name.lowercase() }
            SortBy.CPU -> rows.sortedByDescending { it.cpu }
            SortBy.MEM -> rows.sortedByDescending { it.rssKb }
        }
    }
}

private fun endTask(r: TaskRow) {
    if (r.pkg != null) RootShell.run("am force-stop ${r.pkg}")
    else r.pids.forEach { RootShell.run("kill -9 $it") }
}

// ===================== PROCESOS =====================

@Composable
fun ProcessesScreen(s: Snapshot) {
    val win = LocalWin.current
    var sort by remember { mutableStateOf(SortBy.CPU) }
    var selKey by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<TaskRow?>(null) }
    val scope = rememberCoroutineScope()

    val groups = remember(s, sort) { buildGroups(s, sort) }
    val selected = groups.flatten().firstOrNull { it.key == selKey }
    val memPct = if (s.memTotalKb > 0) (s.memTotalKb - s.memAvailKb) * 100f / s.memTotalKb else 0f
    val titles = listOf("Aplicaciones", "Procesos en segundo plano", "Procesos del sistema")

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(win.bg).bottomBorder(win.border)) {
            HeaderCell("Nombre", null, sort == SortBy.NAME, Modifier.weight(1f), Alignment.Start) { sort = SortBy.NAME }
            HeaderCell("CPU", "%.0f%%".format(s.cpuTotal), sort == SortBy.CPU, Modifier.width(CPU_W), Alignment.End) { sort = SortBy.CPU }
            HeaderCell("Memoria", "%.0f%%".format(memPct), sort == SortBy.MEM, Modifier.width(MEM_W), Alignment.End) { sort = SortBy.MEM }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            groups.forEachIndexed { gi, rows ->
                if (rows.isNotEmpty()) {
                    item(key = "group$gi") {
                        Text(
                            "${titles[gi]} (${rows.size})",
                            color = win.group,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth().background(win.bg).padding(start = 12.dp, top = 14.dp, bottom = 4.dp),
                        )
                    }
                    items(rows, key = { it.key }) { r ->
                        TaskRowView(r, s.memTotalKb, r.key == selKey) { selKey = r.key }
                    }
                }
            }
        }
        BottomBar(selected != null) {
            val r = selected ?: return@BottomBar
            if (r.critical) {
                confirm = r
            } else {
                scope.launch(Dispatchers.IO) { endTask(r) }
                selKey = null
            }
        }
    }

    confirm?.let { r ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("¿Finalizar ${r.name}?") },
            text = { Text("Es un proceso del sistema. Si lo finalizas, el teléfono puede volverse inestable o reiniciarse.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) { endTask(r) }
                    confirm = null
                    selKey = null
                }) { Text("Finalizar proceso") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun HeaderCell(
    title: String,
    pct: String?,
    active: Boolean,
    modifier: Modifier,
    align: Alignment.Horizontal,
    onClick: () -> Unit,
) {
    val win = LocalWin.current
    Column(
        modifier
            .fillMaxHeight()
            .background(if (active) win.header else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = align,
        verticalArrangement = Arrangement.Bottom,
    ) {
        if (pct != null) Text(pct, fontSize = 17.sp, color = win.text)
        Text(if (active) "$title ˅" else title, fontSize = 12.sp, color = win.sub, maxLines = 1)
    }
}

@Composable
private fun TaskRowView(r: TaskRow, memTotalKb: Long, selected: Boolean, onClick: () -> Unit) {
    val win = LocalWin.current
    val memFrac = if (memTotalKb > 0) r.rssKb.toFloat() / memTotalKb * 8f else 0f
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(if (selected) win.select else win.bg)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(r.icon, r.name)
            Spacer(Modifier.width(10.dp))
            Text(
                if (r.count > 1) "${r.name} (${r.count})" else r.name,
                fontSize = 13.sp,
                color = win.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HeatCell("%.1f %%".format(r.cpu), if (selected) win.select else win.heat(r.cpu / 25f), CPU_W)
        HeatCell(formatKb(r.rssKb), if (selected) win.select else win.heat(memFrac), MEM_W)
    }
}

@Composable
private fun HeatCell(text: String, color: Color, width: Dp) {
    val win = LocalWin.current
    Box(
        Modifier.width(width).fillMaxHeight().padding(start = 1.dp).background(color).padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(text, fontSize = 13.sp, color = win.text, maxLines = 1)
    }
}

// ===================== DETALLES =====================

@Composable
fun DetailsScreen(s: Snapshot) {
    val win = LocalWin.current
    var sort by remember { mutableStateOf(SortBy.CPU) }
    var selPid by remember { mutableStateOf<Int?>(null) }
    var confirm by remember { mutableStateOf<ProcInfo?>(null) }
    val scope = rememberCoroutineScope()

    val rows = remember(s, sort) {
        visible(s).let { l ->
            when (sort) {
                SortBy.NAME -> l.sortedBy { it.name.lowercase() }
                SortBy.PID -> l.sortedBy { it.pid }
                SortBy.CPU -> l.sortedByDescending { it.cpu }
                SortBy.MEM -> l.sortedByDescending { it.rssKb }
            }
        }
    }
    val selected = rows.firstOrNull { it.pid == selPid }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(win.bg).bottomBorder(win.border)) {
            DHead("Nombre", sort == SortBy.NAME, Modifier.weight(1f), Alignment.CenterStart) { sort = SortBy.NAME }
            DHead("PID", sort == SortBy.PID, Modifier.width(58.dp), Alignment.CenterEnd) { sort = SortBy.PID }
            DHead("CPU", sort == SortBy.CPU, Modifier.width(50.dp), Alignment.CenterEnd) { sort = SortBy.CPU }
            DHead("Memoria", sort == SortBy.MEM, Modifier.width(80.dp), Alignment.CenterEnd) { sort = SortBy.MEM }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(rows, key = { it.pid }) { p ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .background(if (p.pid == selPid) win.select else win.bg)
                        .clickable { selPid = p.pid }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(p.icon, p.name)
                        Spacer(Modifier.width(8.dp))
                        Text(p.name, fontSize = 12.sp, color = win.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DCell(p.pid.toString(), 58.dp)
                    DCell("%.0f".format(p.cpu), 50.dp)
                    DCell(formatKb(p.rssKb), 80.dp)
                }
            }
        }
        BottomBar(selected != null) { confirm = selected }
    }

    confirm?.let { p ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("¿Finalizar ${p.name}?") },
            text = { Text("PID ${p.pid} · usuario ${p.user}\n\nSi finalizas un proceso del sistema, el teléfono puede reiniciarse.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) { RootShell.run("kill -9 ${p.pid}") }
                    confirm = null
                    selPid = null
                }) { Text("Finalizar proceso") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun DHead(title: String, active: Boolean, modifier: Modifier, align: Alignment, onClick: () -> Unit) {
    val win = LocalWin.current
    Box(
        modifier
            .background(if (active) win.header else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = align,
    ) {
        Text(if (active) "$title ˅" else title, fontSize = 12.sp, color = win.sub, maxLines = 1)
    }
}

@Composable
private fun DCell(text: String, width: Dp) {
    val win = LocalWin.current
    Text(
        text,
        fontSize = 12.sp,
        color = win.text,
        maxLines = 1,
        modifier = Modifier.width(width).padding(start = 4.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.End,
    )
}
