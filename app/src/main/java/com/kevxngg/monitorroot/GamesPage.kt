package com.kevxngg.monitorroot

import androidx.compose.foundation.selection.toggleable
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
    var tick by remember { mutableIntStateOf(0) }
    var diagOpen by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<GameSession?>(null) }
    // What game mode needs and is missing (checked every few seconds: the user may grant it meanwhile)
    var rootOk by remember { mutableStateOf(true) }
    var usageOk by remember { mutableStateOf(true) }
    var overlayOk by remember { mutableStateOf(true) }
    var notifOk by remember { mutableStateOf(true) }

    // New sessions and the current game update on their own
    RepeatWhileVisible(3000) {
        playing = GameModeState.playing
        charging = DiabloMode.isCharging(ctx)
        tick++
        sessions = withContext(Dispatchers.IO) { GameSessions.read(ctx) }
        withContext(Dispatchers.IO) {
            rootOk = RootShell.open()
            usageOk = GameMode.hasUsageAccess(ctx)
            overlayOk = android.provider.Settings.canDrawOverlays(ctx)
            notifOk = androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        }
    }
    // Opened from a session summary notification: that session's details
    LaunchedEffect(sessions) {
        val want = GameModeState.openSession
        if (want != 0L) {
            sessions.firstOrNull { it.start == want }?.let { detail = it }
            GameModeState.openSession = 0L
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(tr("Juegos")) {
            CmdButton(Icons.Outlined.AddBox, tr("Ejecutar nueva tarea"), onClick = onRun)
            CmdButton(Icons.Outlined.SportsEsports, tr("Elegir juegos")) { picking = true }
            CmdButton(Icons.Outlined.BugReport, tr("Informe de diagnóstico")) { diagOpen = true }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ---- Game mode status ----
            GameStatusCard(playing, tick, onEnd = {
                // Ends this session; the game is not started again until it leaves the screen
                try {
                    ctx.startService(Intent(ctx, GameModeService::class.java).setAction(GameModeService.ACTION_END))
                    Feedback.show(tr("Partida terminada"))
                } catch (_: Exception) {
                }
            }) { on ->
                Prefs.gameMode = on
                Prefs.save()
                val app = ctx.applicationContext
                scope.launch(Dispatchers.IO) { if (on) GameMode.start(app) else GameMode.stop(app) }
            }
            // ---- What is missing for game mode to work fully ----
            if (Prefs.gameMode) {
                if (!rootOk) SetupWarning(tr("Sin root ni Shizuku el modo juego solo guarda el resumen: no cambia el perfil ni las demás opciones."))
                if (!usageOk) SetupWarning(tr("Falta el acceso de uso: el modo juego no puede saber qué juego está abierto."), tr("Conceder")) {
                    try {
                        ctx.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {
                    }
                }
                if (Prefs.gameOverlay && !overlayOk) SetupWarning(tr("Falta el permiso para mostrar el monitor flotante sobre otras apps."), tr("Conceder")) {
                    OverlayControl.openPermissionSettings(ctx)
                }
                if (!notifOk && (Prefs.gameRecord || Prefs.gamePower == 4)) SetupWarning(tr("Las notificaciones de Monitor están desactivadas: no verás el resumen ni los avisos de temperatura."), tr("Activar")) {
                    try {
                        ctx.startActivity(
                            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {
                    }
                }
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
            // Uninstalled games are not counted (they stay saved in case they are installed again)
            val myGames = remember(Prefs.games) { Prefs.games.mapNotNull { Apps.meta(it) }.sortedBy { it.label.lowercase() } }
            SectionLabel(tr("Tus juegos (%d)", myGames.size))
            GameGrid(myGames, onAdd = { picking = true }, onLaunch = { pkg ->
                // Game mode on (if it was off) and the game opens: the profile is applied as soon as it is on screen
                if (!Prefs.gameMode) {
                    Prefs.gameMode = true
                    Prefs.save()
                }
                val app = ctx.applicationContext
                scope.launch(Dispatchers.IO) { GameMode.start(app) }
                val label = Apps.meta(pkg)?.label ?: pkg
                val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val opened = launch != null && try { ctx.startActivity(launch); true } catch (_: Exception) { false }
                if (opened) Feedback.show(tr("Abriendo %s con el modo juego", label))
                else Feedback.show(tr("No se puede abrir %s", label), ok = false)
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
                SessionRow(s, onLongClick = { deleting = s }) { detail = s }
            }
            if (sessions.isNotEmpty()) Text(tr("Mantén pulsada una partida para borrarla."), fontSize = 11.sp, color = win.sub, modifier = Modifier.padding(start = 4.dp))
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmDiablo) DiabloDialog(
        onConfirm = { Prefs.diabloAccepted = true; Prefs.gamePower = 4; Prefs.save(); confirmDiablo = false },
        onDismiss = { confirmDiablo = false },
    )
    if (picking) GamePickerDialog { picking = false }
    if (diagOpen) DiagDialog { diagOpen = false }
    deleting?.let { s ->
        ConfirmDialog(s.label, tr("¿Borrar esta partida?"), tr("Borrar"), onConfirm = {
            GameSessions.remove(ctx, s.start)
            sessions = sessions.filter { it.start != s.start }
            Feedback.show(tr("Partida borrada"))
        }, onDismiss = { deleting = null })
    }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(s: GameSession, onLongClick: () -> Unit, onClick: () -> Unit) {
    val win = LocalWin.current
    val meta = remember(s.pkg) { Apps.meta(s.pkg) }
    val date = remember(s.start) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, I18n.locale()).format(Date(s.start)) }
    GameCard {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(meta?.icon, s.label, 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(s.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = win.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$date · ${tr("%d min", s.minutes)}", fontSize = 12.sp, color = win.sub)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.avgFps?.let { Chip(tr("%d FPS", it)) }
                    s.cpuTempMax?.let { Chip("%.0f °C".format(it)) }
                    s.battUsed?.takeIf { it > 0 }?.let { Chip("−$it %") }
                    if (s.charging) Chip(tr("Cargando"))
                    if (s.throttleSec >= 60) Chip(tr("Calor %d min", s.throttleSec / 60))
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
                val detected = list.orEmpty().filter { it.second }.map { it.first.pkg }
                if (detected.any { it !in selected }) {
                    TextButton(onClick = { selected = selected + detected }) { Text(tr("Añadir los %d juegos detectados", detected.size)) }
                }
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
                // Apps that are no longer installed are dropped from the list
                val installed = apps?.map { it.first.pkg }?.toSet()
                Prefs.games = if (installed == null) selected else selected.filter { it in installed }.toSet()
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
    val date = remember(s.start) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, I18n.locale()).format(Date(s.start)) }
    val rows = listOfNotNull(
        tr("Fecha") to date,
        tr("Duración") to tr("%d min", s.minutes),
        s.onScreenSec.takeIf { it > 0 }?.let { tr("En pantalla") to tr("%d min", it / 60) },
        s.avgFps?.let { tr("FPS promedio") to "$it" },
        s.lowFps?.let { tr("FPS bajo (5 %)") to "$it" },
        s.low1Fps?.let { tr("FPS bajo (1 %)") to "$it" },
        s.maxFps?.let { tr("FPS máximo") to "$it" },
        s.stability?.let { tr("Estabilidad") to "$it %" },
        s.cpuTempMax?.let { tr("Temperatura máxima de la CPU") to "%.1f °C".format(it) },
        s.battTempMax?.let { tr("Temperatura máxima de la batería") to "%.1f °C".format(it) },
        s.throttleSec.takeIf { it > 0 }?.let { tr("Tiempo con calor (el sistema baja la velocidad)") to tr("%d min", (it + 59) / 60) },
        when {
            s.charging -> tr("Batería usada") to tr("Cargando")
            else -> s.battUsed?.let { tr("Batería usada") to "$it %" }
        },
        s.drainPerHour?.let { tr("Batería por hora") to "$it %" },
        s.profile.takeIf { it >= 0 }?.let { tr("Perfil") to profileName(it) },
        s.hz.takeIf { it > 0 }?.let { tr("Frecuencia de pantalla") to "$it Hz" },
        GameMode.endReasonText(s.endReason)?.let { tr("Cómo terminó") to it },
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
                        // Dashed line at the average
                        s.avgFps?.let { avg ->
                            val y = h * (1f - avg / top)
                            drawLine(color.copy(alpha = 0.8f), Offset(0f, y), Offset(w, y), 1.dp.toPx(),
                                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
                        }
                        drawRect(color, style = Stroke(width = 1.dp.toPx()))
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Text("0", fontSize = 10.sp, color = win.sub, modifier = Modifier.weight(1f))
                        s.avgFps?.let { Text(tr("- - promedio %d", it), fontSize = 10.sp, color = win.sub, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                        Text(tr("%d min", s.minutes), fontSize = 10.sp, color = win.sub, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
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

private val DiabloRed: androidx.compose.ui.graphics.Color
    @Composable @androidx.compose.runtime.ReadOnlyComposable
    get() = if (LocalWin.current.dark) androidx.compose.ui.graphics.Color(0xFFFF7A7A) else androidx.compose.ui.graphics.Color(0xFFC62828)

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
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun GameStatusCard(playing: String?, tick: Int, onEnd: () -> Unit, onToggle: (Boolean) -> Unit) {
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
    // Live: what is really applied to the game and the last readings
    if (on && playing != null) {
        // Read again on every refresh of the page
        key(tick) {
        val st = GameModeState
        val min = if (st.sessionStart > 0) ((System.currentTimeMillis() - st.sessionStart) / 60_000).toInt() else 0
        Column(
            Modifier.fillMaxWidth().padding(top = 6.dp).clip(corner(8)).background(win.card).border(1.dp, win.border, corner(8)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(tr("%d min", min))
                st.fps?.let { Chip(tr("%d FPS", it)) }
                st.cpuTemp?.let { Chip("CPU %.0f °C".format(it)) }
                st.battTemp?.let { Chip(tr("batería %d °C", it.toInt())) }
                if (st.profile > 0) Chip(profileName(st.profile))
                if (st.hz > 0) Chip("${st.hz} Hz")
                if (st.dnd) Chip(tr("No molestar"))
                if (st.appsClosed > 0) Chip(tr("%d apps cerradas", st.appsClosed))
                if (st.overlay) Chip(tr("Monitor flotante"))
            }
            if (st.diabloFailed) Text(tr("El Modo Diablo no se pudo activar en este teléfono: se usa Rendimiento."), fontSize = 12.sp, color = win.warn)
            if (st.diabloCut) Text(tr("El Modo Diablo se desactivó para proteger el teléfono: sigues jugando con el perfil de fábrica."), fontSize = 12.sp, color = win.warn)
            if (st.throttling) Text(tr("El teléfono está caliente y el sistema está bajando la velocidad."), fontSize = 12.sp, color = win.warn)
            if (Prefs.gamePower != 0 && st.profile == 0 && !st.diabloCut) Text(tr("El perfil elegido no se puede aplicar en este teléfono."), fontSize = 12.sp, color = win.warn)
            TextButton(onClick = onEnd, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(tr("Terminar partida")) }
        }
        }
    }
}

/** Something game mode needs and is missing, with a button to fix it. */
@Composable
private fun SetupWarning(text: String, action: String? = null, onAction: () -> Unit = {}) {
    val win = LocalWin.current
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp).clip(corner(8)).background(win.warn.copy(alpha = if (win.dark) 0.14f else 0.08f))
            .border(1.dp, win.warn.copy(alpha = 0.5f), corner(8)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.WarningAmber, null, tint = win.warn, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 12.sp, color = win.text, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action) }
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
        Modifier.fillMaxWidth()
            .toggleable(value = checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = win.sub, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = win.text)
            Text(desc, fontSize = 12.sp, color = win.sub)
        }
        Spacer(Modifier.width(8.dp))
        WinSwitch(checked, null)   // the whole row toggles it
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
    if (rates.size < 2) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Speed, null, tint = win.sub, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("Frecuencia de pantalla"), fontSize = 14.sp, color = win.sub)
                Text(tr("Tu pantalla tiene una sola frecuencia: no hay nada que ajustar."), fontSize = 12.sp, color = win.sub)
            }
        }
        return
    }
    val options = listOf(tr("Sin cambios")) + rates.map { "$it Hz" }
    val selected = if (Prefs.gameHz == 0) 0 else rates.indexOf(Prefs.gameHz).let { if (it < 0) 0 else it + 1 }
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Speed, null, tint = win.sub, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(tr("Frecuencia de pantalla"), fontSize = 14.sp, color = win.text)
            Text(tr("Si el juego no llega a los FPS de la pantalla, una frecuencia más baja los mantiene estables."), fontSize = 12.sp, color = win.sub)
            if (RootShell.viaShizuku) Text(tr("Solo con root") + ": " + tr("con Shizuku no se puede cambiar."), fontSize = 12.sp, color = win.warn)
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
