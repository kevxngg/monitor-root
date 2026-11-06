package com.kevxngg.monitorroot

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Service(
    val key: String,
    val name: String,
    val pid: Int?,
    val description: String,
    val running: Boolean,
    val group: String,
    val native: Boolean,       // servicio de init (nativo) o de una app
    val component: String?,
)

private val PROP = Regex("^\\[(init\\.svc\\.|init\\.svc_debug_pid\\.)([^\\]]+)\\]: \\[([^\\]]*)\\]")
private val SVC_RECORD = Regex("\\* ServiceRecord\\{\\S+ u(\\d+) ([^ }]+)")
private val PROC_RECORD = Regex("app=ProcessRecord\\{\\S+ (\\d+):")

private fun loadNative(): List<Service> {
    val state = HashMap<String, String>()
    val pids = HashMap<String, Int>()
    RootShell.run("getprop | grep -E '^\\[init\\.svc'").forEach { l ->
        val m = PROP.find(l) ?: return@forEach
        val name = m.groupValues[2]
        if (m.groupValues[1] == "init.svc.") state[name] = m.groupValues[3]
        else m.groupValues[3].toIntOrNull()?.let { pids[name] = it }
    }
    return state.map { (name, st) ->
        val running = st == "running"
        Service(
            key = "init:$name", name = name, pid = if (running) pids[name] else null,
            description = tr("Servicio nativo de Android"),
            running = running,
            group = "init",
            native = true,
            component = null,
        )
    }
}

private fun loadAppServices(): List<Service> {
    val out = ArrayList<Service>()
    var comp: String? = null
    var user = 0
    var pid: Int? = null
    fun flush() {
        val c = comp ?: return
        val pkg = c.substringBefore('/')
        val cls = c.substringAfter('/')
        val short = cls.substringAfterLast('.')
        out.add(
            Service(
                key = "app:u$user:$c", name = short, pid = pid,
                description = Apps.meta(pkg)?.label ?: pkg,
                running = true,
                group = if (user == 0) pkg else "$pkg (${tr("Usuario")} $user)",
                native = false,
                component = c,
            )
        )
    }
    RootShell.run("dumpsys activity services").forEach { l ->
        val m = SVC_RECORD.find(l)
        if (m != null) {
            flush()
            user = m.groupValues[1].toInt()
            comp = m.groupValues[2]
            pid = null
        } else if (comp != null && pid == null) {
            PROC_RECORD.find(l)?.let { pid = it.groupValues[1].toIntOrNull() }
        }
    }
    flush()
    return out.distinctBy { it.key }
}

@Composable
fun ServicesPage(s: Snapshot, query: String, onRun: () -> Unit, onGoDetails: (Int) -> Unit) {
    val win = LocalWin.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var native by remember { mutableStateOf<List<Service>?>(null) }
    var apps by remember { mutableStateOf<List<Service>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var onlyRunning by remember { mutableStateOf(false) }
    var sortId by remember { mutableStateOf("name") }
    var asc by remember { mutableStateOf(true) }
    var selKey by remember { mutableStateOf<String?>(null) }
    var menuKey by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val h = rememberScrollState()

    RepeatWhileVisible(5000, key = reload) {
        native = withContext(Dispatchers.IO) { loadNative() }
        apps = withContext(Dispatchers.IO) { loadAppServices() }
    }

    val rows = remember(native, apps, query, onlyRunning, sortId, asc) {
        val l = ((native ?: emptyList()) + apps)
            .filter { (!onlyRunning || it.running) && matches(query, it.name, it.description, it.group, it.pid?.toString()) }
        val sorted = when (sortId) {
            "pid" -> l.sortedBy { it.pid ?: Int.MAX_VALUE }
            "desc" -> l.sortedBy { it.description.lowercase() }
            "state" -> l.sortedBy { if (it.running) 0 else 1 }
            "group" -> l.sortedBy { it.group.lowercase() }
            else -> l.sortedBy { it.name.lowercase() }
        }
        if (asc) sorted else sorted.reversed()
    }
    val selected = rows.firstOrNull { it.key == selKey }

    fun act(sv: Service, verb: String) {
        val title = when (verb) {
            "start" -> tr("¿Iniciar %s?", sv.name)
            "stop" -> tr("¿Detener %s?", sv.name)
            else -> tr("¿Reiniciar %s?", sv.name)
        }
        val warn = if (sv.native) tr("\n\nEs un servicio del sistema: detenerlo puede dejar el teléfono inestable o hacer que se reinicie.") else ""
        val action: () -> Unit = {
            scope.launch(Dispatchers.IO) {
                val n = Actions.q(sv.name)
                when {
                    sv.native && verb == "start" -> RootShell.run("start $n")
                    sv.native && verb == "stop" -> RootShell.run("stop $n")
                    sv.native -> RootShell.run("stop $n; start $n")
                    else -> RootShell.run("am stopservice -n ${Actions.q(sv.component ?: "")}")
                }
                delay(800)
                native = loadNative()
                apps = loadAppServices()
            }
        }
        confirm = (title + warn) to action
    }

    fun menuFor(sv: Service) = listOfNotNull(
        if (sv.native) MenuEntry(tr("Iniciar"), Icons.Outlined.PlayArrow, enabled = !sv.running) { act(sv, "start") } else null,
        MenuEntry(tr("Detener"), Icons.Outlined.Stop, enabled = sv.running) { act(sv, "stop") },
        if (sv.native) MenuEntry(tr("Reiniciar"), Icons.Outlined.RestartAlt, enabled = sv.running) { act(sv, "restart") } else null,
        sv.pid?.let { p -> MenuEntry(tr("Ir a detalles"), Icons.Outlined.FormatListBulleted) { onGoDetails(p) } },
        MenuEntry(tr("Buscar en línea"), Icons.Outlined.Search) { searchOnline(ctx, "Android ${sv.name}") },
    )

    val cols = listOf(
        Col("pid", "PID", 64.dp),
        Col("desc", tr("Descripción"), 190.dp, end = false),
        Col("state", tr("Estado"), 110.dp, end = false),
        Col("group", tr("Grupo"), 200.dp, end = false),
    )

    Column(Modifier.fillMaxSize()) {
        PageHeader(tr("Servicios")) {
            CmdButton(Icons.Outlined.AddBox, tr("Ejecutar nueva tarea"), onClick = onRun)
            CmdButton(Icons.Outlined.PlayArrow, tr("Iniciar"), enabled = selected != null && selected.native && !selected.running) {
                selected?.let { act(it, "start") }
            }
            CmdButton(Icons.Outlined.Stop, tr("Detener"), enabled = selected?.running == true) { selected?.let { act(it, "stop") } }
            CmdButton(Icons.Outlined.RestartAlt, tr("Reiniciar"), enabled = selected != null && selected.native && selected.running) {
                selected?.let { act(it, "restart") }
            }
            CmdMore(
                listOf(
                    MenuEntry(tr("Mostrar solo en ejecución"), checked = onlyRunning) { onlyRunning = !onlyRunning },
                    MenuEntry(tr("Actualizar"), Icons.Outlined.Refresh) { reload++ },
                )
            )
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val nameW = nameWidth(maxWidth, cols, 160.dp)
            Column(Modifier.fillMaxSize()) {
                TableHeader(tr("Nombre"), nameW, cols, h, sortId, asc) { id ->
                    if (sortId == id) asc = !asc else {
                        sortId = id; asc = true
                    }
                }
                if (native == null) {
                    EmptyText(tr("Leyendo servicios…"))
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(rows, key = { it.key }) { sv ->
                            TableRow(
                                nameW, cols,
                                listOf(
                                    Cell(sv.pid?.toString() ?: ""),
                                    Cell(sv.description),
                                    Cell(if (sv.running) tr("En ejecución") else tr("Detenido")),
                                    Cell(sv.group),
                                ),
                                h,
                                selected = selKey == sv.key,
                                onClick = { selKey = sv.key },
                                onLongClick = { selKey = sv.key; menuKey = sv.key },
                                menu = { WinMenu(menuKey == sv.key, { menuKey = null }, menuFor(sv)) },
                                height = 30.dp,
                            ) {
                                Icon(
                                    if (sv.native) Icons.Outlined.Settings else Icons.Outlined.Extension,
                                    null, tint = win.sub, modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(sv.name, fontSize = 12.5.sp, color = win.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }

    confirm?.let { (text, action) ->
        ConfirmDialog(tr("Servicios"), text, tr("Aceptar"), onConfirm = action, onDismiss = { confirm = null })
    }
}
