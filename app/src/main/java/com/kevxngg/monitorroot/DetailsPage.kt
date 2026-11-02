package com.kevxngg.monitorroot

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun DetailsPage(
    s: Snapshot,
    query: String,
    focusPid: Int?,
    onFocusConsumed: () -> Unit,
    onRun: () -> Unit,
) {
    val win = LocalWin.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sortId by remember { mutableStateOf("cpu") }
    var asc by remember { mutableStateOf(false) }
    var selPid by remember { mutableStateOf<Int?>(null) }
    var menuPid by remember { mutableStateOf<Int?>(null) }
    var confirmEnd by remember { mutableStateOf<Pair<ProcInfo, Boolean>?>(null) } // (proceso, ¿árbol?)
    var props by remember { mutableStateOf<ProcInfo?>(null) }
    var prio by remember { mutableStateOf<ProcInfo?>(null) }
    var affinity by remember { mutableStateOf<ProcInfo?>(null) }
    val h = rememberScrollState()
    val listState = rememberLazyListState()

    val rows = remember(s, query, sortId, asc) {
        val l = visibleProcs(s).filter { matches(query, it.name, it.label, it.pkg, it.user, it.pid.toString()) }
        val sorted = when (sortId) {
            "pid" -> l.sortedBy { it.pid }
            "state" -> l.sortedBy { stateText(it.state) }
            "user" -> l.sortedBy { it.user }
            "cpu" -> l.sortedBy { it.cpu }
            "mem" -> l.sortedBy { it.rssKb }
            "threads" -> l.sortedBy { it.threads }
            "prio" -> l.sortedBy { it.nice }
            "disk" -> l.sortedBy { it.diskBps }
            else -> l.sortedBy { it.name.lowercase() }
        }
        if (asc) sorted else sorted.reversed()
    }
    val selected = rows.firstOrNull { it.pid == selPid }

    // Llegada desde tr("Ir a detalles")
    LaunchedEffect(focusPid, rows.isNotEmpty()) {
        val pid = focusPid ?: return@LaunchedEffect
        val idx = rows.indexOfFirst { it.pid == pid }
        if (idx >= 0) {
            selPid = pid
            listState.scrollToItem(idx)
            onFocusConsumed()
        }
    }

    fun menuFor(p: ProcInfo): List<MenuEntry> {
        val suspended = p.state == 'T' || p.state == 't'
        return listOf(
            MenuEntry(tr("Finalizar tarea"), Icons.Outlined.Block) { confirmEnd = p to false },
            MenuEntry(tr("Finalizar árbol de procesos"), Icons.Outlined.AccountTree) { confirmEnd = p to true },
            MenuEntry(if (suspended) tr("Reanudar proceso") else tr("Suspender proceso"), if (suspended) Icons.Outlined.PlayArrow else Icons.Outlined.Pause) {
                scope.launch(Dispatchers.IO) { Actions.kill(listOf(p.pid), if (suspended) "CONT" else "STOP") }
            },
            MenuEntry(tr("Establecer prioridad"), Icons.Outlined.LowPriority) { prio = p },
            MenuEntry(tr("Establecer afinidad"), Icons.Outlined.Memory) { affinity = p },
            MenuEntry(tr("Buscar en línea"), Icons.Outlined.Search) { searchOnline(ctx, p.pkg ?: p.name) },
            MenuEntry(tr("Propiedades"), Icons.Outlined.Description) { props = p },
        )
    }

    val cols = listOf(
        Col("pid", "PID", 64.dp),
        Col("state", tr("Estado"), 104.dp, end = false),
        Col("user", tr("Nombre de usuario"), 130.dp, end = false),
        Col("cpu", "CPU", 52.dp),
        Col("mem", tr("Memoria"), 86.dp),
        Col("threads", tr("Subprocesos"), 96.dp),
        Col("prio", tr("Prioridad"), 170.dp, end = false),
        Col("disk", tr("Disco"), 84.dp),
    )

    fun cells(p: ProcInfo) = listOf(
        Cell(p.pid.toString()),
        Cell(stateText(p.state)),
        Cell(p.user),
        Cell("%.0f".format(p.cpu)),
        Cell(formatKb(p.rssKb)),
        Cell(p.threads.toString()),
        Cell(priorityText(p.nice)),
        Cell(formatMBs(p.diskBps)),
    )

    Column(Modifier.fillMaxSize()) {
        PageHeader(tr("Detalles")) {
            CmdButton(Icons.Outlined.AddBox, tr("Ejecutar nueva tarea"), onClick = onRun)
            CmdButton(Icons.Outlined.Block, tr("Finalizar tarea"), enabled = selected != null) { selected?.let { confirmEnd = it to false } }
            CmdMore(selected?.let { menuFor(it).drop(1) } ?: listOf(MenuEntry(tr("Selecciona un proceso"), enabled = false) {}))
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val nameW = nameWidth(maxWidth, cols, 160.dp)
            Column(Modifier.fillMaxSize()) {
                TableHeader(tr("Nombre"), nameW, cols, h, sortId, asc) { id ->
                    if (sortId == id) asc = !asc else {
                        sortId = id; asc = id == "name" || id == "pid" || id == "user"
                    }
                }
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(rows, key = { it.pid }) { p ->
                        TableRow(
                            nameW, cols, cells(p), h,
                            selected = selPid == p.pid,
                            onClick = { selPid = p.pid },
                            onLongClick = { selPid = p.pid; menuPid = p.pid },
                            menu = { WinMenu(menuPid == p.pid, { menuPid = null }, menuFor(p)) },
                            height = 30.dp,
                        ) {
                            AppIcon(p.icon, p.name, 16.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (Prefs.showPackageNames) p.name else p.label ?: p.name,
                                fontSize = 12.5.sp, color = win.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }

    confirmEnd?.let { (p, tree) ->
        ConfirmDialog(
            title = if (tree) tr("¿Finalizar el árbol de procesos de %s?", p.name) else tr("¿Finalizar %s?", p.name),
            text = if (tree) tr("Se finalizará este proceso y todos los que inició. Si son procesos del sistema, el teléfono puede reiniciarse.")
            else tr("Si finalizas un proceso del sistema, el teléfono puede volverse inestable o reiniciarse."),
            confirm = if (tree) tr("Finalizar árbol de procesos") else tr("Finalizar proceso"),
            onConfirm = {
                val pids = if (tree) Actions.tree(p.pid, s.processes) else listOf(p.pid)
                scope.launch(Dispatchers.IO) { Actions.kill(pids) }
                selPid = null
            },
            onDismiss = { confirmEnd = null },
        )
    }
    props?.let { p -> PropertiesDialog(p, s.uptimeSec) { props = null } }
    prio?.let { p ->
        PriorityDialog(p.nice, onPick = { v -> scope.launch(Dispatchers.IO) { Actions.setNice(listOf(p), v) } }, onDismiss = { prio = null })
    }
    affinity?.let { p ->
        AffinityDialog(p.pid, s.cores.size.coerceAtLeast(Runtime.getRuntime().availableProcessors())) { affinity = null }
    }
}
