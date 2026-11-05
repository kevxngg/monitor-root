package com.kevxngg.monitorroot

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun GamesPage(onRun: () -> Unit) {
    val win = LocalWin.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf(GameSessions.read(ctx)) }
    var picking by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<GameSession?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    // Las partidas nuevas aparecen solas
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            sessions = withContext(Dispatchers.IO) { GameSessions.read(ctx) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(tr("Juegos")) {
            CmdButton(Icons.Outlined.AddBox, tr("Ejecutar nueva tarea"), onClick = onRun)
            CmdButton(Icons.Outlined.SportsEsports, tr("Elegir juegos")) { picking = true }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SectionLabel(tr("Modo juego"))
            SettingCard(Icons.Outlined.SportsEsports, tr("Modo juego"), tr("Al abrir uno de tus juegos se aplican estos ajustes y se guarda el resumen de la partida.")) {
                WinSwitch(Prefs.gameMode) { on ->
                    Prefs.gameMode = on
                    Prefs.save()
                    val app = ctx.applicationContext
                    scope.launch(Dispatchers.IO) { if (on) GameMode.start(app) else GameMode.stop(app) }
                }
            }
            SettingCard(Icons.Outlined.Bolt, tr("Modo de energía al jugar"), tr("Se vuelve al modo anterior al salir del juego.")) {
                WinDropdown(
                    listOf(tr("Sin cambios"), tr("Mejor rendimiento"), tr("Equilibrado"), tr("Mejor eficiencia energética")),
                    Prefs.gamePower,
                ) { Prefs.gamePower = it; Prefs.save() }
            }
            SettingCard(Icons.Outlined.PictureInPicture, tr("Mostrar el monitor flotante"), tr("Se oculta al salir del juego.")) {
                WinSwitch(Prefs.gameOverlay) { Prefs.gameOverlay = it; Prefs.save() }
            }
            SettingCard(Icons.Outlined.CleaningServices, tr("Cerrar apps en segundo plano"), tr("Libera memoria al abrir el juego.")) {
                WinSwitch(Prefs.gameKill) { Prefs.gameKill = it; Prefs.save() }
            }
            SettingCard(Icons.Outlined.DoNotDisturbOn, tr("No molestar"), tr("Silencia las notificaciones mientras juegas.")) {
                WinSwitch(Prefs.gameDnd) { Prefs.gameDnd = it; Prefs.save() }
            }
            SettingCard(Icons.Outlined.Assessment, tr("Guardar resumen de partidas"), tr("FPS, temperatura y batería de cada partida.")) {
                WinSwitch(Prefs.gameRecord) { Prefs.gameRecord = it; Prefs.save() }
            }

            Spacer(Modifier.height(8.dp))
            SectionLabel(tr("Tus juegos (%d)", Prefs.games.size))
            val myGames = remember(Prefs.games) { Prefs.games.mapNotNull { Apps.meta(it) }.sortedBy { it.label.lowercase() } }
            GameCard {
                if (myGames.isEmpty()) {
                    Text(tr("Todavía no elegiste juegos."), fontSize = 13.sp, color = win.sub, modifier = Modifier.padding(14.dp))
                } else {
                    myGames.forEachIndexed { i, a ->
                        if (i > 0) Box(Modifier.padding(start = 50.dp).fillMaxWidth().height(1.dp).background(win.divider))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(a.icon, a.label, 24.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(a.label, fontSize = 14.sp, color = win.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(
                                Icons.Outlined.Close, tr("Quitar"), tint = win.sub,
                                modifier = Modifier.size(20.dp).clip(corner(4)).clickable {
                                    Prefs.games = Prefs.games - a.pkg
                                    Prefs.save()
                                },
                            )
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(win.divider))
                TextButton(onClick = { picking = true }, modifier = Modifier.padding(horizontal = 6.dp)) { Text(tr("Elegir juegos")) }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionLabel(tr("Partidas recientes")) }
                if (sessions.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text(tr("Borrar")) }
            }
            if (sessions.isEmpty()) {
                Text(
                    tr("Activa el modo juego y juega al menos un minuto: aquí verás el resumen de cada partida."),
                    fontSize = 13.sp, color = win.sub, modifier = Modifier.padding(4.dp),
                )
            }
            sessions.forEach { s ->
                SessionRow(s) { detail = s }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (picking) GamePickerDialog { picking = false }
    detail?.let { SessionDialog(it) { detail = null } }
    if (confirmClear) {
        ConfirmDialog(tr("Partidas recientes"), tr("¿Borrar el historial de partidas?"), tr("Borrar"), onConfirm = {
            GameSessions.clear(ctx)
            sessions = emptyList()
        }, onDismiss = { confirmClear = false })
    }
}

@Composable
private fun GameCard(content: @Composable ColumnScope.() -> Unit) {
    val win = LocalWin.current
    Column(
        Modifier.fillMaxWidth().clip(corner(6)).background(win.card).border(1.dp, win.border, corner(6)),
        content = content,
    )
}

@Composable
private fun Chip(text: String) {
    val win = LocalWin.current
    Text(
        text, fontSize = 11.sp, color = win.text,
        modifier = Modifier.clip(corner(4)).background(win.hover).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun SessionRow(s: GameSession, onClick: () -> Unit) {
    val win = LocalWin.current
    val meta = remember(s.pkg) { Apps.meta(s.pkg) }
    val date = remember(s.start) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, I18n.locale()).format(Date(s.start)) }
    GameCard {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(meta?.icon, s.label, 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(s.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = win.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$date · ${tr("%d min", s.minutes)}", fontSize = 12.sp, color = win.sub)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.avgFps?.let { Chip(tr("%d FPS", it)) }
                    s.cpuTempMax?.let { Chip("%.0f °C".format(it)) }
                    s.battUsed?.takeIf { it > 0 }?.let { Chip("−$it %") }
                }
            }
        }
    }
}

/** Lista de apps para elegir cuáles son juegos (los detectados aparecen primero). */
@Composable
private fun GamePickerDialog(onDismiss: () -> Unit) {
    val win = LocalWin.current
    var apps by remember { mutableStateOf<List<Pair<AppMeta, Boolean>>?>(null) }
    var selected by remember { mutableStateOf(Prefs.games) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = Apps.pm()
            Apps.launchablePackages().mapNotNull { pkg ->
                val m = Apps.meta(pkg) ?: return@mapNotNull null
                val game = try { pm?.getApplicationInfo(pkg, 0)?.let(GameMode::isGame) ?: false } catch (_: Exception) { false }
                m to game
            }.sortedWith(compareBy({ !it.second }, { it.first.label.lowercase() }))
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Elegir juegos"), fontSize = 18.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text(tr("Buscar")) }, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                val list = apps
                if (list == null) {
                    Text(tr("Cargando…"), fontSize = 13.sp, color = win.sub)
                } else {
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(list.filter { matches(query, it.first.label, it.first.pkg) }, key = { it.first.pkg }) { (a, game) ->
                            val on = a.pkg in selected
                            Row(
                                Modifier.fillMaxWidth().clickable { selected = if (on) selected - a.pkg else selected + a.pkg }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = on, onCheckedChange = { selected = if (it) selected + a.pkg else selected - a.pkg })
                                AppIcon(a.icon, a.label, 24.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(a.label, fontSize = 14.sp, color = win.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (game) Text(tr("Juego"), fontSize = 11.sp, color = win.accent)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Prefs.games = selected
                Prefs.save()
                onDismiss()
            }) { Text(tr("Aceptar")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } },
    )
}

/** Detalle de una partida con la gráfica de FPS. */
@Composable
private fun SessionDialog(s: GameSession, onDismiss: () -> Unit) {
    val win = LocalWin.current
    val ctx = LocalContext.current
    val color = Res.GPU.color(win)
    val rows = listOfNotNull(
        tr("Duración") to tr("%d min", s.minutes),
        s.avgFps?.let { tr("FPS promedio") to "$it" },
        s.lowFps?.let { tr("FPS bajo (5 %)") to "$it" },
        s.maxFps?.let { tr("FPS máximo") to "$it" },
        s.cpuTempMax?.let { tr("Temperatura máxima") to "%.1f °C".format(it) },
        s.battUsed?.let { tr("Batería usada") to "$it %" },
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.label, fontSize = 18.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("FPS durante la partida"), fontSize = 12.sp, color = win.sub)
                val values = s.fps
                if (values.size >= 2) {
                    val top = maxOf(60, values.maxOrNull() ?: 60).toFloat()
                    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                        val w = size.width
                        val h = size.height
                        val g = color.copy(alpha = 0.15f)
                        for (i in 1 until 4) drawLine(g, Offset(0f, h * i / 4f), Offset(w, h * i / 4f), 1f)
                        val step = w / (values.size - 1)
                        val p = Path()
                        val area = Path()
                        values.forEachIndexed { i, v ->
                            val x = i * step
                            val y = h * (1f - v / top)
                            if (i == 0) {
                                p.moveTo(x, y); area.moveTo(x, h); area.lineTo(x, y)
                            } else {
                                p.lineTo(x, y); area.lineTo(x, y)
                            }
                        }
                        area.lineTo(w, h)
                        area.close()
                        drawPath(area, color.copy(alpha = 0.2f))
                        drawPath(p, color, style = Stroke(width = 1.5.dp.toPx()))
                        drawRect(color, style = Stroke(width = 1.dp.toPx()))
                    }
                } else {
                    Text(tr("No hay datos de FPS de esta partida."), fontSize = 12.sp, color = win.sub)
                }
                rows.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(k, fontSize = 13.sp, color = win.sub, modifier = Modifier.weight(1f))
                        Text(v, fontSize = 13.sp, color = win.text)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val text = "${s.label}\n" + rows.joinToString("\n") { "${it.first}: ${it.second}" } + "\n\nMonitor"
                try {
                    ctx.startActivity(
                        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) {
                }
            }) { Text(tr("Compartir")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cerrar")) } },
    )
}
