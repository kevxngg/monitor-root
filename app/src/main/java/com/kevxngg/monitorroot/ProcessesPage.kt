package com.kevxngg.monitorroot

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Fila de la pestaña Procesos: una app (con todos sus procesos) o un proceso suelto. */
class TaskRow(
    val key: String,
    val name: String,
    val icon: ImageBitmap?,
    val pkg: String?,
    val procs: List<ProcInfo>,
    val launchable: Boolean,
) {
    val cpu = procs.sumOf { it.cpu.toDouble() }.toFloat()
    val rssKb = procs.sumOf { it.rssKb }
    val diskBps = procs.sumOf { it.diskBps }
    val suspended = procs.isNotEmpty() && procs.all { it.state == 'T' || it.state == 't' }
    val critical get() = pkg == null || !launchable
}

fun visibleProcs(s: Snapshot) = s.processes.filter { it.rssKb > 0 || it.cpu >= 0.1f }

fun matches(q: String, vararg fields: String?) =
    q.isBlank() || fields.any { it != null && it.contains(q.trim(), ignoreCase = true) }

fun appRows(procs: List<ProcInfo>): Pair<List<TaskRow>, List<TaskRow>> {
    val apps = ArrayList<TaskRow>()
    val bg = ArrayList<TaskRow>()
    procs.filter { it.pkg != null }.groupBy { it.pkg!! }.forEach { (pkg, list) ->
        val first = list.first()
        val row = TaskRow(
            key = "app:$pkg",
            name = if (Prefs.showPackageNames) pkg else first.label ?: pkg,
            icon = first.icon,
            pkg = pkg,
            procs = list,
            launchable = first.launchable,
        )
        if (first.launchable) apps.add(row) else bg.add(row)
    }
    return apps to bg
}

fun nativeRows(procs: List<ProcInfo>) = procs.filter { it.pkg == null }
    .map { TaskRow("pid:${it.pid}", it.name, null, null, listOf(it), false) }

fun childRow(p: ProcInfo) = TaskRow("child:${p.pid}", p.name, null, null, listOf(p), false)

fun sortRows(rows: List<TaskRow>, id: String, asc: Boolean): List<TaskRow> {
    val sorted = when (id) {
        "state" -> rows.sortedBy { if (it.suspended) 1 else 0 }
        "cpu" -> rows.sortedBy { it.cpu }
        "mem" -> rows.sortedBy { it.rssKb }
        "disk" -> rows.sortedBy { it.diskBps }
        else -> rows.sortedBy { it.name.lowercase() }
    }
    return if (asc) sorted else sorted.reversed()
}

@Composable
fun ProcessesPage(
    s: Snapshot,
    query: String,
    onRun: () -> Unit,
    onGoDetails: (Int) -> Unit,
) {
    val win = LocalWin.current
    val ctx = LocalContext.current
    val compact = LocalCompact.current
    val scope = rememberCoroutineScope()
    var sortId by remember { mutableStateOf("cpu") }
    var asc by remember { mutableStateOf(false) }
    var selKey by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var menuKey by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<TaskRow?>(null) }
    var props by remember { mutableStateOf<ProcInfo?>(null) }
    val h = rememberScrollState()

    val groups: List<Pair<String, List<TaskRow>>> = remember(s, query, sortId, asc, Prefs.groupByType, Prefs.showPackageNames) {
        val vis = visibleProcs(s).filter { matches(query, it.name, it.label, it.pkg, it.pid.toString()) }
        val (apps, bg) = appRows(vis)
        val native = nativeRows(vis)
        if (Prefs.groupByType) {
            listOf(
                tr("Aplicaciones") to sortRows(apps, sortId, asc),
                tr("Procesos en segundo plano") to sortRows(bg, sortId, asc),
                tr("Procesos de Android") to sortRows(native, sortId, asc),
            )
        } else {
            listOf("" to sortRows(apps + bg + native, sortId, asc))
        }
    }
    val all = groups.flatMap { it.second }
    val selected: TaskRow? = selKey?.let { k ->
        all.firstOrNull { it.key == k }
            ?: if (k.startsWith("child:")) s.processes.firstOrNull { "child:${it.pid}" == k }?.let(::childRow) else null
    }

    fun endTask(r: TaskRow) {
        scope.launch(Dispatchers.IO) {
            if (r.pkg != null) Actions.forceStop(r.pkg) else Actions.kill(r.procs.map { it.pid })
        }
        selKey = null
    }

    fun requestEnd(r: TaskRow) {
        if (r.critical) confirm = r else endTask(r)
    }

    fun toggleEfficiency(r: TaskRow) {
        val pkg = r.pkg ?: return
        val on = pkg !in Prefs.efficiency
        Prefs.efficiency = if (on) Prefs.efficiency + pkg else Prefs.efficiency - pkg
        Prefs.save()
        scope.launch(Dispatchers.IO) { Actions.setEfficiency(pkg, r.procs, on) }
    }

    fun menuFor(r: TaskRow): List<MenuEntry> = buildList {
        if (r.procs.size > 1) {
            add(MenuEntry(if (r.key in expanded) tr("Contraer") else tr("Expandir")) {
                expanded = if (r.key in expanded) expanded - r.key else expanded + r.key
            })
        }
        add(MenuEntry(tr("Finalizar tarea"), Icons.Outlined.Block) { requestEnd(r) })
        if (r.pkg != null) {
            val on = r.pkg in Prefs.efficiency
            add(MenuEntry(if (on) tr("Desactivar modo de eficiencia") else tr("Modo de eficiencia"), Icons.Outlined.Eco) { toggleEfficiency(r) })
            add(MenuEntry(tr("Información de la app"), Icons.Outlined.Info) { openAppInfo(ctx, r.pkg) })
        }
        r.procs.firstOrNull()?.let { p -> add(MenuEntry(tr("Ir a detalles"), Icons.Outlined.FormatListBulleted) { onGoDetails(p.pid) }) }
        add(MenuEntry(tr("Buscar en línea"), Icons.Outlined.Search) { searchOnline(ctx, r.pkg ?: r.name) })
        r.procs.firstOrNull()?.let { p -> add(MenuEntry(tr("Propiedades"), Icons.Outlined.Description) { props = p }) }
    }

    val diskPct = s.disk?.activePct ?: 0f
    val stateCol = Col("state", tr("Estado"), if (compact) 70.dp else 104.dp, end = false)
    val cpuCol = Col("cpu", "CPU", 64.dp, "%.0f%%".format(s.cpuTotal))
    val memCol = Col("mem", tr("Memoria"), 88.dp, "%.0f%%".format(s.mem.usedPct))
    val diskCol = Col("disk", tr("Disco"), 84.dp, "%.0f%%".format(diskPct))
    // En el teléfono, CPU y Memoria primero para que se vean sin desplazar
    val cols = if (compact) listOf(cpuCol, memCol, diskCol, stateCol) else listOf(stateCol, cpuCol, memCol, diskCol)

    fun cells(r: TaskRow): List<Cell> = cols.map { c ->
        when (c.id) {
            "state" -> {
                val eco = r.pkg != null && r.pkg in Prefs.efficiency
                when {
                    r.suspended -> Cell(if (compact) "" else tr("Suspendido"), icon = Icons.Outlined.PauseCircle, iconTint = win.warn)
                    eco -> Cell(if (compact) "" else tr("Eficiencia"), icon = Icons.Outlined.Eco, iconTint = win.eco)
                    else -> Cell("")
                }
            }
            "cpu" -> Cell("%.1f %%".format(r.cpu), heat = r.cpu / 50f)
            "mem" -> Cell(formatKb(r.rssKb), heat = if (s.mem.totalKb > 0) r.rssKb * 6f / s.mem.totalKb else 0f)
            else -> Cell(formatMBs(r.diskBps), heat = r.diskBps / 20_971_520f)
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(tr("Procesos")) {
            CmdButton(Icons.Outlined.AddBox, tr("Ejecutar nueva tarea"), onClick = onRun)
            CmdButton(Icons.Outlined.Block, tr("Finalizar tarea"), enabled = selected != null) { selected?.let(::requestEnd) }
            CmdButton(Icons.Outlined.Eco, tr("Modo de eficiencia"), enabled = selected?.pkg != null) { selected?.let(::toggleEfficiency) }
            CmdMore(
                listOf(
                    MenuEntry(tr("Agrupar por tipo"), checked = Prefs.groupByType) {
                        Prefs.groupByType = !Prefs.groupByType; Prefs.save()
                    },
                    MenuEntry(tr("Mostrar nombres de paquete"), checked = Prefs.showPackageNames) {
                        Prefs.showPackageNames = !Prefs.showPackageNames; Prefs.save()
                    },
                    MenuEntry(tr("Expandir todo"), Icons.Outlined.UnfoldMore) {
                        expanded = all.filter { it.procs.size > 1 }.map { it.key }.toSet()
                    },
                    MenuEntry(tr("Contraer todo"), Icons.Outlined.UnfoldLess) { expanded = emptySet() },
                )
            )
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val nameW = nameWidth(maxWidth, cols)
            Column(Modifier.fillMaxSize()) {
                TableHeader(tr("Nombre"), nameW, cols, h, sortId, asc) { id ->
                    if (sortId == id) asc = !asc else {
                        sortId = id; asc = id == "name"
                    }
                }
                if (all.isEmpty()) {
                    EmptyText(if (query.isBlank()) tr("Cargando procesos…") else tr("Ningún proceso coincide con “%s”.", query))
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    var rowIndex = 0
                    groups.forEach { (title, rows) ->
                        if (rows.isEmpty()) return@forEach
                        if (title.isNotEmpty()) item(key = "g:$title") { GroupTitle("${tr(title)} (${rows.size})") }
                        rows.forEach { r ->
                            val ri = rowIndex++
                            item(key = r.key) {
                                TableRow(
                                    nameW, cols, cells(r), h, row = ri,
                                    selected = selKey == r.key,
                                    onClick = { selKey = r.key },
                                    onLongClick = { selKey = r.key; menuKey = r.key },
                                    menu = { WinMenu(menuKey == r.key, { menuKey = null }, menuFor(r)) },
                                ) {
                                    val isOpen = r.key in expanded
                                    Chevron(isOpen, r.procs.size > 1) {
                                        expanded = if (isOpen) expanded - r.key else expanded + r.key
                                    }
                                    Spacer(Modifier.width(2.dp))
                                    AppIcon(r.icon, r.name)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (r.procs.size > 1) "${r.name} (${r.procs.size})" else r.name,
                                        fontSize = 13.sp, color = win.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (r.procs.size > 1 && r.key in expanded) {
                                itemsIndexed(r.procs.sortedByDescending { it.cpu }, key = { _, p -> "child:${p.pid}" }) { ci, p ->
                                    val cr = childRow(p)
                                    TableRow(
                                        nameW, cols, cells(cr), h, row = ci,
                                        selected = selKey == cr.key,
                                        onClick = { selKey = cr.key },
                                        onLongClick = { selKey = cr.key; menuKey = cr.key },
                                        menu = { WinMenu(menuKey == cr.key, { menuKey = null }, menuFor(cr)) },
                                    ) {
                                        Spacer(Modifier.width(34.dp))
                                        AppIcon(null, p.name, 16.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "${p.name} (${p.pid})", fontSize = 12.5.sp, color = win.text,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    confirm?.let { r ->
        ConfirmDialog(
            title = tr("¿Finalizar %s?", r.name),
            text = tr("Es un proceso del sistema. Si lo finalizas, el teléfono puede volverse inestable o reiniciarse."),
            confirm = tr("Finalizar proceso"),
            onConfirm = { endTask(r) },
            onDismiss = { confirm = null },
        )
    }
    props?.let { p -> PropertiesDialog(p, s.uptimeSec) { props = null } }
}
