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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val USER_INFO = Regex("UserInfo\\{(\\d+):([^:]*):")

private class UserGroup(val id: Int, val name: String, val rows: List<TaskRow>) {
    val cpu = rows.sumOf { it.cpu.toDouble() }.toFloat()
    val rssKb = rows.sumOf { it.rssKb }
    val diskBps = rows.sumOf { it.diskBps }
}

@Composable
fun UsersPage(s: Snapshot, query: String, onRun: () -> Unit) {
    val win = LocalWin.current
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var names by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var expanded by remember { mutableStateOf(setOf(0)) }
    var selKey by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val h = rememberScrollState()

    LaunchedEffect(Unit) {
        names = withContext(Dispatchers.IO) {
            RootShell.run("pm list users").mapNotNull { l ->
                USER_INFO.find(l)?.let { it.groupValues[1].toInt() to it.groupValues[2] }
            }.toMap()
        }
    }

    val groups = remember(s, names, query) {
        val vis = visibleProcs(s).filter { matches(query, it.name, it.label, it.pkg) }
        vis.groupBy { it.androidUser ?: -1 }.map { (uid, procs) ->
            val (apps, bg) = appRows(procs)
            val rows = (apps + bg + nativeRows(procs)).sortedByDescending { it.cpu }
            val name = when {
                uid == -1 -> tr("Sistema (root y servicios)")
                names[uid] != null -> "${names[uid]} (${tr("Usuario")} $uid)"
                else -> tr("Usuario %d", uid)
            }
            UserGroup(uid, name, rows)
        }.sortedBy { if (it.id == -1) Int.MAX_VALUE else it.id }
    }
    val selUser = selKey?.removePrefix("user:")?.toIntOrNull()

    val cols = listOf(
        Col("state", tr("Estado"), 90.dp, end = false),
        Col("cpu", "CPU", 64.dp, "%.0f%%".format(s.cpuTotal)),
        Col("mem", tr("Memoria"), 88.dp, "%.0f%%".format(s.mem.usedPct)),
        Col("disk", tr("Disco"), 84.dp, "%.0f%%".format(s.disk?.activePct ?: 0f)),
    )

    Column(Modifier.fillMaxSize()) {
        PageHeader(tr("Usuarios")) {
            CmdButton(Icons.Outlined.AddBox, tr("Ejecutar nueva tarea"), onClick = onRun)
            CmdButton(Icons.Outlined.SwitchAccount, tr("Cambiar a"), enabled = selUser != null && selUser >= 0) {
                val u = selUser ?: return@CmdButton
                val action: () -> Unit = { scope.launch(Dispatchers.IO) { RootShell.run("am switch-user $u") } }
                confirm = tr("¿Cambiar al usuario %d?", u) to action
            }
            CmdButton(Icons.Outlined.ManageAccounts, tr("Administrar cuentas de usuario")) {
                try {
                    ctx.startActivity(android.content.Intent("android.settings.USER_SETTINGS").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            CmdButton(Icons.Outlined.Logout, tr("Detener usuario"), enabled = selUser != null && selUser > 0) {
                val u = selUser ?: return@CmdButton
                val action: () -> Unit = { scope.launch(Dispatchers.IO) { RootShell.run("am stop-user -f $u") } }
                confirm = tr("¿Detener el usuario %d? Se cerrarán todas sus apps.", u) to action
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val nameW = nameWidth(maxWidth, cols)
            Column(Modifier.fillMaxSize()) {
                TableHeader(tr("Usuario"), nameW, cols, h, "cpu", false) {}
                LazyColumn(Modifier.fillMaxSize()) {
                    groups.forEach { g ->
                        item(key = "user:${g.id}") {
                            val open = g.id in expanded
                            TableRow(
                                nameW, cols,
                                listOf(
                                    Cell(tr("Activo")),
                                    Cell("%.1f %%".format(g.cpu), heat = g.cpu / 50f),
                                    Cell(formatKb(g.rssKb), heat = if (s.mem.totalKb > 0) g.rssKb * 2f / s.mem.totalKb else 0f),
                                    Cell(formatMBs(g.diskBps), heat = g.diskBps / 20_971_520f),
                                ),
                                h,
                                selected = selKey == "user:${g.id}",
                                onClick = { selKey = "user:${g.id}" },
                                onLongClick = { expanded = if (open) expanded - g.id else expanded + g.id },
                                height = 40.dp,
                            ) {
                                Chevron(open, true) { expanded = if (open) expanded - g.id else expanded + g.id }
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Outlined.AccountCircle, null, tint = win.accent, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${g.name} (${g.rows.size})", fontSize = 13.sp, color = win.text,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (g.id in expanded) {
                            items(g.rows, key = { "u${g.id}:${it.key}" }) { r ->
                                TableRow(
                                    nameW, cols,
                                    listOf(
                                        Cell(if (r.suspended) tr("Suspendido") else ""),
                                        Cell("%.1f %%".format(r.cpu), heat = r.cpu / 50f),
                                        Cell(formatKb(r.rssKb), heat = if (s.mem.totalKb > 0) r.rssKb * 6f / s.mem.totalKb else 0f),
                                        Cell(formatMBs(r.diskBps), heat = r.diskBps / 20_971_520f),
                                    ),
                                    h,
                                    selected = selKey == "u${g.id}:${r.key}",
                                    onClick = { selKey = "u${g.id}:${r.key}" },
                                    onLongClick = {},
                                ) {
                                    Spacer(Modifier.width(30.dp))
                                    AppIcon(r.icon, r.name, 16.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (r.procs.size > 1) "${r.name} (${r.procs.size})" else r.name,
                                        fontSize = 12.5.sp, color = win.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    confirm?.let { (text, action) ->
        ConfirmDialog(tr("Usuarios"), text, tr("Aceptar"), onConfirm = action, onDismiss = { confirm = null })
    }
}
