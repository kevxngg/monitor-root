package com.kevxngg.monitorroot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Interfaz al estilo del Monitor de Actividad de macOS: barra de título con los
 * tres círculos y las pestañas, el contenido de la página, y una barra de totales
 * abajo. Usa las mismas páginas que el estilo Windows.
 */
@Composable
fun MacShell(
    snap: Snapshot?,
    hist: History,
    query: String,
    onQuery: (String) -> Unit,
    page: Page,
    onPage: (Page) -> Unit,
    focusPid: Int?,
    onFocusPid: (Int?) -> Unit,
    onLatest: (UpdateInfo?) -> Unit,
    onRun: () -> Unit,
) {
    val win = LocalWin.current
    Column(Modifier.fillMaxSize().background(win.content)) {
        MacTitleBar(query, onQuery, page, onPage)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (snap == null) {
                CenterText(tr("Leyendo datos del sistema…"))
            } else {
                when (page) {
                    Page.PROCESSES -> ProcessesPage(snap, query, onRun) { onFocusPid(it); onPage(Page.DETAILS) }
                    Page.PERFORMANCE -> PerformancePage(snap, hist, onRun)
                    Page.HISTORY -> HistoryPage(snap, query, onRun)
                    Page.STARTUP -> StartupPage(snap, query, onRun)
                    Page.USERS -> UsersPage(snap, query, onRun)
                    Page.DETAILS -> DetailsPage(snap, query, focusPid, { onFocusPid(null) }, onRun)
                    Page.SERVICES -> ServicesPage(snap, query, onRun) { onFocusPid(it); onPage(Page.DETAILS) }
                    Page.GAMES -> GamesPage(onRun)
                    Page.SETTINGS -> SettingsPage(onLatest)
                }
            }
        }
        if (snap != null) MacStatusBar(snap, page)
    }
}

/** Barra superior: tres círculos de colores, pestañas y buscador. */
@Composable
private fun MacTitleBar(query: String, onQuery: (String) -> Unit, page: Page, onPage: (Page) -> Unit) {
    val win = LocalWin.current
    val compact = LocalCompact.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(win.mica)
            .border(width = 0.dp, color = Color.Transparent),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Los tres círculos del Monitor de Actividad (decorativos)
            listOf(Color(0xFFFF5F57), Color(0xFFFEBC2E), Color(0xFF28C840)).forEach { c ->
                Box(Modifier.padding(end = 8.dp).size(12.dp).clip(CircleShape).background(c))
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("Monitor de Actividad", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = win.text)
                Text(tr(page.title), fontSize = 11.sp, color = win.sub)
            }
            MacSearch(query, onQuery, Modifier.width(if (compact) 150.dp else 240.dp))
        }
        MacTabs(page, onPage)
    }
}

/** Pestañas de las páginas, con scroll horizontal si no caben. */
@Composable
private fun MacTabs(page: Page, onPage: (Page) -> Unit) {
    val win = LocalWin.current
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Page.entries.forEach { p ->
            val selected = p == page
            Text(
                tr(p.title),
                fontSize = 13.sp,
                color = if (selected) win.text else win.sub,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (selected) win.hover else Color.Transparent)
                    .clickable { onPage(p) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun MacSearch(query: String, onQuery: (String) -> Unit, modifier: Modifier) {
    val win = LocalWin.current
    Row(
        modifier
            .height(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(win.content)
            .border(1.dp, win.border, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = win.sub, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(tr("Buscar"), fontSize = 13.sp, color = win.sub, maxLines = 1)
            BasicTextField(
                value = query, onValueChange = onQuery, singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp, color = win.text),
                cursorBrush = SolidColor(win.accent), modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                Icons.Outlined.Close, tr("Borrar"), tint = win.sub,
                modifier = Modifier.size(15.dp).clickable { onQuery("") },
            )
        }
    }
}

/** Barra de totales inferior, como la franja gris del Monitor de Actividad. */
@Composable
private fun MacStatusBar(s: Snapshot, page: Page) {
    val win = LocalWin.current
    val items: List<Pair<String, String>> = when (page) {
        Page.PERFORMANCE, Page.PROCESSES, Page.DETAILS -> listOf(
            tr("CPU") to "%.0f%%".format(s.cpuTotal),
            tr("Memoria") to "${s.mem.usedPct.toInt()}%",
            tr("Procesos") to "${s.processes.size}",
            tr("Subprocesos") to "${s.threads}",
        )
        Page.SERVICES -> listOf(tr("Procesos") to "${s.processes.size}")
        Page.USERS -> listOf(tr("Procesos") to "${s.processes.size}")
        else -> listOf(
            tr("CPU") to "%.0f%%".format(s.cpuTotal),
            tr("Memoria") to "${s.mem.usedPct.toInt()}%",
        )
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(win.mica)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { (k, v) ->
            Row {
                Text("$k: ", fontSize = 12.sp, color = win.sub, maxLines = 1)
                Text(v, fontSize = 12.sp, color = win.text, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

/** Diálogos (nueva tarea y actualización) para el estilo macOS. */
@Composable
fun MacDialogs(runOpen: Boolean, onRunClose: () -> Unit, latest: UpdateInfo?, required: UpdateInfo?, onUpdateClose: () -> Unit) {
    if (runOpen) RunTaskDialog(onRunClose)
    if (required != null) UpdateDialog(required, required = true) {}
    else latest?.let { UpdateDialog(it, onDismiss = onUpdateClose) }
}
