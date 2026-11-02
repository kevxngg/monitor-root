package com.kevxngg.monitorroot

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class StartRow(
    val pkg: String,
    val label: String,
    val icon: ImageBitmap?,
    val publisher: String,
    val comps: List<ComponentName>,
    val enabledCount: Int,
    val system: Boolean,
) {
    val enabled get() = enabledCount > 0
}

private val BOOT_ACTIONS = listOf(
    Intent.ACTION_BOOT_COMPLETED,
    Intent.ACTION_LOCKED_BOOT_COMPLETED,
    "android.intent.action.QUICKBOOT_POWERON",
)

private fun loadStartup(): List<StartRow> {
    val pm = Apps.pm() ?: return emptyList()
    val byPkg = LinkedHashMap<String, MutableMap<ComponentName, Boolean>>()
    BOOT_ACTIONS.forEach { action ->
        @Suppress("DEPRECATION")
        val list = pm.queryBroadcastReceivers(Intent(action), PackageManager.MATCH_DISABLED_COMPONENTS)
        list.forEach { ri ->
            val ai = ri.activityInfo ?: return@forEach
            val cn = ComponentName(ai.packageName, ai.name)
            val enabled = when (pm.getComponentEnabledSetting(cn)) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> ai.enabled
                else -> false
            }
            byPkg.getOrPut(ai.packageName) { LinkedHashMap() }[cn] = enabled
        }
    }
    return byPkg.mapNotNull { (pkg, comps) ->
        val m = Apps.meta(pkg) ?: return@mapNotNull null
        StartRow(
            pkg = pkg,
            label = m.label,
            icon = m.icon,
            publisher = if (m.system) "Sistema" else Apps.installerLabel(pkg) ?: "Desconocido",
            comps = comps.keys.toList(),
            enabledCount = comps.values.count { it },
            system = m.system,
        )
    }
}

@Composable
fun StartupPage(s: Snapshot, query: String, onRun: () -> Unit) {
    val win = LocalWin.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<List<StartRow>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var showSystem by remember { mutableStateOf(false) }
    var sortId by remember { mutableStateOf("name") }
    var asc by remember { mutableStateOf(true) }
    var selPkg by remember { mutableStateOf<String?>(null) }
    var menuPkg by remember { mutableStateOf<String?>(null) }
    val h = rememberScrollState()

    LaunchedEffect(reload) { data = withContext(Dispatchers.IO) { loadStartup() } }

    val rows = remember(data, query, showSystem, sortId, asc) {
        val l = (data ?: emptyList()).filter { (showSystem || !it.system) && matches(query, it.label, it.pkg, it.publisher) }
        val sorted = when (sortId) {
            "pub" -> l.sortedBy { it.publisher.lowercase() }
            "state" -> l.sortedBy { if (it.enabled) 0 else 1 }
            "count" -> l.sortedBy { it.comps.size }
            else -> l.sortedBy { it.label.lowercase() }
        }
        if (asc) sorted else sorted.reversed()
    }
    val selected = rows.firstOrNull { it.pkg == selPkg }

    fun setEnabled(r: StartRow, on: Boolean) {
        scope.launch(Dispatchers.IO) {
            val verb = if (on) "enable" else "disable"
            RootShell.run(r.comps.joinToString("; ") { "pm $verb ${Actions.q("${it.packageName}/${it.className}")}" })
            data = loadStartup()
        }
    }

    val cols = listOf(
        Col("pub", "Publicador", 130.dp, end = false),
        Col("state", "Estado", 110.dp, end = false),
        Col("count", "Receptores de inicio", 150.dp),
    )

    Column(Modifier.fillMaxSize()) {
        PageHeader("Aplicaciones de arranque") {
            CmdButton(Icons.Outlined.AddBox, "Ejecutar nueva tarea", onClick = onRun)
            CmdButton(Icons.Outlined.CheckCircle, "Habilitar", enabled = selected != null && selected.enabledCount < selected.comps.size) {
                selected?.let { setEnabled(it, true) }
            }
            CmdButton(Icons.Outlined.DoNotDisturbOn, "Deshabilitar", enabled = selected?.enabled == true) {
                selected?.let { setEnabled(it, false) }
            }
            CmdMore(
                listOf(
                    MenuEntry("Mostrar apps del sistema", checked = showSystem) { showSystem = !showSystem },
                    MenuEntry("Actualizar", Icons.Outlined.Refresh) { reload++ },
                )
            )
        }
        val bootAt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(System.currentTimeMillis() - s.uptimeSec * 1000))
        Text(
            "Último arranque: $bootAt",
            fontSize = 12.sp, color = win.sub, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val nameW = nameWidth(maxWidth, cols)
            Column(Modifier.fillMaxSize()) {
                TableHeader("Nombre", nameW, cols, h, sortId, asc) { id ->
                    if (sortId == id) asc = !asc else {
                        sortId = id; asc = true
                    }
                }
                when {
                    data == null -> EmptyText("Buscando apps que se inician con el teléfono…")
                    rows.isEmpty() -> EmptyText("No hay aplicaciones de arranque para mostrar.")
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(rows, key = { it.pkg }) { r ->
                            val state = when {
                                r.enabledCount == 0 -> "Deshabilitado"
                                r.enabledCount < r.comps.size -> "Parcial"
                                else -> "Habilitado"
                            }
                            TableRow(
                                nameW, cols,
                                listOf(Cell(r.publisher), Cell(state), Cell(r.comps.size.toString())),
                                h,
                                selected = selPkg == r.pkg,
                                onClick = { selPkg = r.pkg },
                                onLongClick = { selPkg = r.pkg; menuPkg = r.pkg },
                                menu = {
                                    WinMenu(
                                        menuPkg == r.pkg, { menuPkg = null },
                                        listOf(
                                            MenuEntry(if (r.enabled) "Deshabilitar" else "Habilitar", if (r.enabled) Icons.Outlined.DoNotDisturbOn else Icons.Outlined.CheckCircle) {
                                                setEnabled(r, !r.enabled)
                                            },
                                            MenuEntry("Información de la app", Icons.Outlined.Info) { openAppInfo(ctx, r.pkg) },
                                            MenuEntry("Buscar en línea", Icons.Outlined.Search) { searchOnline(ctx, r.label) },
                                        ),
                                    )
                                },
                            ) {
                                AppIcon(r.icon, r.label)
                                Spacer(Modifier.width(8.dp))
                                Text(r.label, fontSize = 13.sp, color = win.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
