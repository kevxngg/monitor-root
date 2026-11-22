package com.kevxngg.monitorroot

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
    var confirmDiablo by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(GameModeState.playing) }
    var charging by remember { mutableStateOf(DiabloMode.isCharging(ctx)) }

    // New sessions and the current game update on their own
    RepeatWhileVisible(3000) {
        playing = GameModeState.playing
        charging = DiabloMode.isCharging(ctx)
        sessions = withContext(Dispatchers.IO) { GameSessions.read(ctx) }
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
            // ---- Game mode status ----
            GameStatusCard(playing) { on ->
                Prefs.gameMode = on
                Prefs.save()
                val app = ctx.applicationContext
                scope.launch(Dispatchers.IO) { if (on) GameMode.start(app) else GameMode.stop(app) }
            }

            // ---- Performance profile (Diablo Mode is just one of them) ----
            Spacer(Modifier.height(8.dp))
            SectionLabel(tr("Perfil de rendimiento"))
            Text(tr("Se aplica al abrir uno de tus juegos y se deshace al cerrarlo."), fontSize = 12.sp, color = win.sub, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
            ProfilePicker(Prefs.gamePower) { chosen ->
                when {
                    chosen == Prefs.gamePower -> Unit                             // already selected: nothing to do
                    chosen == 4 && !Prefs.diabloAccepted -> confirmDiablo = true  // the warning is only shown the first time
                    else -> { Prefs.gamePower = chosen; Prefs.save() }
                }
            }
            if (Prefs.gamePower == 4) {
                DiabloWarning(compact = true)
                if (charging) Text(
                    tr("Estás cargando: usa el cargador original para que el teléfono reciba toda la potencia. Jugar cargando calienta más la batería."),
                    fontSize = 12.sp, color = win.warn, modifier = Modifier.padding(start = 4.dp),
                )
            }

            // ---- During the session ----
            Spacer(Modifier.height(8.dp))
            SectionLabel(tr("Durante la partida"))
            GameCard {
                GameToggle(Icons.Outlined.PictureInPicture, tr("Mostrar el monitor flotante"), tr("Se oculta al salir del juego."), Prefs.gameOverlay) { Prefs.gameOverlay = it; Prefs.save() }
                ToggleDivider()
                RefreshRateRow()
                ToggleDivider()
                GameToggle(Icons.Outlined.CleaningServices, tr("Cerrar apps en segundo plano"), tr("Cierra tus apps y no las deja abrirse hasta que salgas del juego. Sus notificaciones pueden llegar con retraso."), Prefs.gameKill) { Prefs.gameKill = it; Prefs.save() }
                ToggleDivider()
                GameToggle(Icons.Outlined.DoNotDisturbOn, tr("No molestar"), tr("Silencia las notificaciones mientras juegas."), Prefs.gameDnd) { Prefs.gameDnd = it; Prefs.save() }
                ToggleDivider()
                GameToggle(Icons.Outlined.Assessment, tr("Guardar resumen de partidas"), tr("FPS, temperatura y batería de cada partida."), Prefs.gameRecord) { Prefs.gameRecord = it; Prefs.save() }
            }

            // ---- Your games in a grid ----
            Spacer(Modifier.height(8.dp))
            SectionLabel(tr("Tus juegos (%d)", Prefs.games.size))
            val myGames = remember(Prefs.games) { Prefs.games.mapNotNull { Apps.meta(it) }.sortedBy { it.label.lowercase() } }
            GameGrid(myGames, onAdd = { picking = true }, onLaunch = { pkg ->
                // Game mode on (if it was off) and the game opens: the profile is applied as soon as it is on screen
                if (!Prefs.gameMode) {
                    Prefs.gameMode = true
                    Prefs.save()
                }
                val app = ctx.applicationContext
                scope.launch(Dispatchers.IO) { GameMode.start(app) }
                ctx.packageManager.getLaunchIntentForPackage(pkg)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?.let { i -> try { ctx.startActivity(i) } catch (_: Exception) {} }
            }) { pkg ->
                Prefs.games = Prefs.games - pkg
                Prefs.save()
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

    if (confirmDiablo) DiabloDialog(
        onConfirm = { Prefs.diabloAccepted = true; Prefs.gamePower = 4; Prefs.save(); confirmDiablo = false },
        onDismiss = { confirmDiablo = false },
    )
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

/** List of apps to choose which ones are games (detected ones appear first). */
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

/** Details of a session with the FPS graph. */
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

private val DiabloRed = androidx.compose.ui.graphics.Color(0xFFE5484D)

/** Red Diablo Mode warning (short version under the card or full one in the dialog). */
@Composable
private fun DiabloWarning(compact: Boolean) {
    val win = LocalWin.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(corner(8))
            .background(DiabloRed.copy(alpha = if (win.dark) 0.16f else 0.09f))
            .border(1.dp, DiabloRed.copy(alpha = 0.6f), corner(8))
            .padding(12.dp),
    ) {
        Icon(Icons.Outlined.WarningAmber, null, tint = DiabloRed, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("Puede sobrecalentar y dañar el teléfono"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DiabloRed)
            if (!compact) {
                Text(tr("El teléfono se calentará mucho más de lo normal. El calor sostenido degrada la batería, puede hacer que se hinche y, en casos extremos, dañar componentes."), fontSize = 12.sp, color = win.text)
                Text(tr("Úsalo con un cooler para teléfono y sin funda. Si juegas cargando, usa el cargador original."), fontSize = 12.sp, color = win.text)
            }
            Text(
                tr("Monitor lo desactiva solo si la batería llega a %d °C o la CPU a %d °C, o al salir del juego. La protección crítica del sistema no se toca.",
                    DiabloMode.BATT_LIMIT.toInt(), DiabloMode.CPU_LIMIT.toInt()),
                fontSize = 12.sp, color = win.sub,
            )
        }
    }
}

@Composable
private fun DiabloDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var accepted by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.LocalFireDepartment, null, tint = DiabloRed) },
        title = { Text(tr("Activar Modo Diablo"), fontSize = 18.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Mientras juegas, la CPU y la GPU quedarán fijas en su frecuencia máxima y se quitarán los límites que bajan la velocidad con el calor: los servicios del fabricante y los límites pasivos del kernel."), fontSize = 13.sp)
                DiabloWarning(compact = false)
                Row(
                    Modifier.fillMaxWidth().clickable { accepted = !accepted },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = accepted, onCheckedChange = { accepted = it })
                    Text(tr("Entiendo los riesgos y lo uso bajo mi responsabilidad"), fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = accepted) {
                Text(tr("Activar"), color = if (accepted) DiabloRed else LocalWin.current.sub)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } },
    )
}

// ===================== Games redesign components =====================

/** Status card: game mode on or off, current game and profile. */
@Composable
private fun GameStatusCard(playing: String?, onToggle: (Boolean) -> Unit) {
    val win = LocalWin.current
    val on = Prefs.gameMode
    val diablo = Prefs.gamePower == 4
    val accent = if (diablo && on) DiabloRed else win.accent
    val label = playing?.let { Apps.meta(it)?.label ?: it }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(corner(12))
            .background(if (on) accent.copy(alpha = if (win.dark) 0.18f else 0.10f) else win.card)
            .border(1.dp, if (on) accent.copy(alpha = 0.55f) else win.border, corner(12))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(46.dp).clip(corner(12)).background(if (on) accent else win.hover),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (diablo && on) Icons.Outlined.LocalFireDepartment else Icons.Outlined.SportsEsports, null,
                tint = if (on) androidx.compose.ui.graphics.Color.White else win.sub, modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(tr("Modo juego"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = win.text)
            Text(
                when {
                    !on -> tr("Desactivado")
                    label != null -> tr("Jugando: %s", label)
                    else -> tr("Esperando a que abras uno de tus juegos.")
                },
                fontSize = 13.sp, color = if (on && label != null) accent else win.sub,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (on) Text(tr("Perfil: %s", profileName(Prefs.gamePower)), fontSize = 12.sp, color = win.sub)
        }
        WinSwitch(on, onToggle)
    }
}

private fun profileName(i: Int) = when (i) {
    0 -> tr("Sin cambios")
    1 -> tr("Rendimiento")
    2 -> tr("Equilibrado")
    3 -> tr("Eficiencia")
    else -> tr("Modo Diablo")
}

/** The five profiles as cards; Diablo Mode takes a whole row and is shown in red. */
@Composable
private fun ProfilePicker(selected: Int, onPick: (Int) -> Unit) {
    val rootOnly = RootShell.viaShizuku
    data class P(val id: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector, val title: String, val desc: String)
    val items = listOf(
        P(0, Icons.Outlined.Smartphone, tr("Sin cambios"), tr("El teléfono decide")),
        P(3, Icons.Outlined.EnergySavingsLeaf, tr("Eficiencia"), tr("Menos calor y batería")),
        P(2, Icons.Outlined.Balance, tr("Equilibrado"), tr("Como viene de fábrica")),
        P(1, Icons.Outlined.Bolt, tr("Rendimiento"), tr("CPU al máximo")),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { p ->
                    ProfileTile(
                        p.icon, p.title, p.desc, selected == p.id,
                        enabled = p.id == 0 || !rootOnly, red = false, modifier = Modifier.weight(1f),
                    ) { onPick(p.id) }
                }
            }
        }
        ProfileTile(
            Icons.Outlined.LocalFireDepartment, tr("Modo Diablo"),
            tr("CPU y GPU fijas en su frecuencia máxima y sin control térmico del fabricante mientras juegas."),
            selected == 4, enabled = !rootOnly, red = true, modifier = Modifier.fillMaxWidth(),
        ) { onPick(4) }
        if (rootOnly) Text(tr("Solo con root") + ": " + tr("con Shizuku no se puede cambiar."), fontSize = 12.sp, color = LocalWin.current.warn, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun ProfileTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, desc: String,
    selected: Boolean, enabled: Boolean, red: Boolean, modifier: Modifier, onClick: () -> Unit,
) {
    val win = LocalWin.current
    val tint = if (red) DiabloRed else win.accent
    Column(
        modifier
            .clip(corner(10))
            .background(
                when {
                    selected -> tint.copy(alpha = if (win.dark) 0.20f else 0.11f)
                    red -> DiabloRed.copy(alpha = if (win.dark) 0.07f else 0.04f)
                    else -> win.card
                }
            )
            .border(if (selected) 2.dp else 1.dp, if (selected) tint else if (red) DiabloRed.copy(alpha = 0.45f) else win.border, corner(10))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(12.dp)
            .then(if (enabled) Modifier else Modifier.background(androidx.compose.ui.graphics.Color.Transparent)),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (enabled) tint else win.sub, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = if (!enabled) win.sub else if (red) DiabloRed else win.text,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (selected) Icon(Icons.Outlined.CheckCircle, null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Text(desc, fontSize = 11.5.sp, color = win.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GameToggle(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, desc: String,
    checked: Boolean, onChange: (Boolean) -> Unit,
) {
    val win = LocalWin.current
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = win.sub, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = win.text)
            Text(desc, fontSize = 12.sp, color = win.sub)
        }
        Spacer(Modifier.width(8.dp))
        WinSwitch(checked, onChange)
    }
}

/** Screen refresh rate while playing: a rate the game can sustain gives the most even frames. */
@Composable
private fun RefreshRateRow() {
    val win = LocalWin.current
    val ctx = LocalContext.current
    val rates = remember {
        try {
            @Suppress("DEPRECATION")
            (ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
                .supportedModes.map { Math.round(it.refreshRate) }.filter { it >= 30 }.distinct().sortedDescending()
        } catch (_: Exception) {
            emptyList()
        }
    }
    if (rates.size < 2) return
    val options = listOf(tr("Sin cambios")) + rates.map { "$it Hz" }
    val selected = if (Prefs.gameHz == 0) 0 else rates.indexOf(Prefs.gameHz).let { if (it < 0) 0 else it + 1 }
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Speed, null, tint = win.sub, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(tr("Frecuencia de pantalla"), fontSize = 14.sp, color = win.text)
            Text(tr("Si el juego no llega a los FPS de la pantalla, una frecuencia más baja los mantiene estables."), fontSize = 12.sp, color = win.sub)
        }
        Spacer(Modifier.width(8.dp))
        WinDropdown(options, selected, enabled = !RootShell.viaShizuku) { i ->
            Prefs.gameHz = if (i == 0) 0 else rates[i - 1]
            Prefs.save()
        }
    }
}

@Composable
private fun ToggleDivider() {
    Box(Modifier.padding(start = 50.dp).fillMaxWidth().height(1.dp).background(LocalWin.current.divider))
}

/** Your games in a 4-column grid, with an add button. Long-press a game to remove it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameGrid(games: List<AppMeta>, onAdd: () -> Unit, onLaunch: (String) -> Unit, onRemove: (String) -> Unit) {
    val win = LocalWin.current
    var removing by remember { mutableStateOf<AppMeta?>(null) }
    val cells: List<AppMeta?> = games + listOf(null)   // null = add button
    GameCard {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            cells.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { a ->
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(corner(8))
                                .combinedClickable(
                                    onClick = { if (a == null) onAdd() else onLaunch(a.pkg) },
                                    onLongClick = { if (a != null) removing = a },
                                )
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (a != null) {
                                AppIcon(a.icon, a.label, 44.dp)
                            } else {
                                Box(
                                    Modifier.size(44.dp).clip(corner(12)).border(1.5.dp, win.border, corner(12)),
                                    contentAlignment = Alignment.Center,
                                ) { Icon(Icons.Outlined.Add, tr("Elegir juegos"), tint = win.accent) }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                a?.label ?: tr("Añadir"), fontSize = 11.sp,
                                color = if (a == null) win.accent else win.text,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (games.isEmpty()) Text(tr("Todavía no elegiste juegos."), fontSize = 12.sp, color = win.sub, modifier = Modifier.padding(4.dp))
            else Text(tr("Toca un juego para abrirlo con el modo juego. Mantén pulsado para quitarlo."), fontSize = 11.sp, color = win.sub, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
    removing?.let { a ->
        ConfirmDialog(a.label, tr("¿Quitar este juego de la lista?"), tr("Quitar"), onConfirm = { onRemove(a.pkg) }, onDismiss = { removing = null })
    }
}
