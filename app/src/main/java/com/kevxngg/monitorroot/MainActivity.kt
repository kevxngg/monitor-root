package com.kevxngg.monitorroot

import android.graphics.Color as AColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class Page(val title: String, val icon: ImageVector) {
    PROCESSES("Procesos", Icons.Outlined.GridView),
    PERFORMANCE("Rendimiento", Icons.Outlined.MonitorHeart),
    HISTORY("Historial de aplicaciones", Icons.Outlined.History),
    STARTUP("Aplicaciones de arranque", Icons.Outlined.Speed),
    USERS("Usuarios", Icons.Outlined.People),
    DETAILS("Detalles", Icons.Outlined.FormatListBulleted),
    SERVICES("Servicios", Icons.Outlined.Extension),
    GAMES("Juegos", Icons.Outlined.SportsEsports),
    SETTINGS("Configuración", Icons.Outlined.Settings),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(applicationContext)
        Apps.init(applicationContext)
        I18n.init(applicationContext)
        UpdateWorker.schedule(applicationContext)
        setContent {
            val dark = when (Prefs.theme) {
                1 -> false
                2, 3 -> true
                else -> isSystemInDarkTheme()
            }
            val style = Prefs.uiStyle.coerceIn(0, UiStyles.NAMES.size - 1)
            val win = remember(dark, Prefs.theme, style) { UiStyles.palette(style, dark, pureBlack = Prefs.theme == 3) }
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(AColor.TRANSPARENT)
                    else SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(AColor.TRANSPARENT)
                    else SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT),
                )
            }
            val base = if (dark) darkColorScheme() else lightColorScheme()
            val scheme = base.copy(
                primary = win.accent,
                background = win.content,
                surface = win.card,
                onBackground = win.text,
                onSurface = win.text,
                onSurfaceVariant = win.sub,
                surfaceContainer = win.card,
                surfaceContainerHigh = win.card,
                surfaceContainerHighest = win.card,
            )
            val dir = if (I18n.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalWin provides win, LocalLayoutDirection provides dir) {
                MaterialTheme(colorScheme = scheme) {
                    Box(Modifier.fillMaxSize().background(win.mica)) { App() }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) RootShell.close()
    }
}

@Composable
fun App() {
    var hasRoot by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { hasRoot = withContext(Dispatchers.IO) { RootShell.open() } }
    when (hasRoot) {
        null -> CenterText(tr("Pidiendo acceso root…\nAcepta el aviso de Magisk / KernelSU."))
        false -> CenterText(tr("No se obtuvo acceso root.\nDale permiso a la app en Magisk / KernelSU y vuelve a abrirla."))
        else -> Shell()
    }
}

@Composable
fun CenterText(text: String) {
    val win = LocalWin.current
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = win.text, fontSize = 14.sp)
    }
}

@Composable
fun Shell() {
    val win = LocalWin.current
    val context = LocalContext.current
    val reader = remember { SystemReader(context.applicationContext) }
    var snap by remember { mutableStateOf<Snapshot?>(null) }
    var hist by remember { mutableStateOf(History()) }
    var samples by remember { mutableIntStateOf(0) }
    // Las notificaciones del modo juego abren directamente la página Juegos
    val openGames = (context as? android.app.Activity)?.intent?.getStringExtra("page") == "games"
    var page by remember { mutableStateOf(if (openGames) Page.GAMES else Page.entries.getOrElse(Prefs.startPage) { Page.PROCESSES }) }
    var navOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var runOpen by remember { mutableStateOf(false) }
    var focusPid by remember { mutableStateOf<Int?>(null) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var latestInfo by remember { mutableStateOf(Updater.cached(context)) }

    // Check on resume and once a minute while visible, including the required-version policy.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            UpdateWorker.prepare(context.applicationContext)
            // Si el modo juego estaba activo, se asegura de que siga funcionando
            if (Prefs.gameMode) GameMode.start(context.applicationContext)
        }
        (context as LifecycleOwner).repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                // Always verify the supported version, even when optional alerts are disabled.
                when (val result = withContext(Dispatchers.IO) { Updater.fetch(context.applicationContext) }) {
                    is UpdateResult.Success -> {
                        val info = result.info
                        latestInfo = info
                        if (Prefs.autoUpdate && update == null && Updater.isOffered(context, info) && !Updater.isSnoozed(info.build)) {
                            update = info
                            UpdateWorker.cancelNotification(context.applicationContext)
                        }
                    }
                    is UpdateResult.Failure -> Unit // Preserve the last verified policy while offline.
                }
                // Con los avisos desactivados solo se comprueba de vez en cuando
                delay(if (Prefs.autoUpdate) 60 * 1000L else 15 * 60 * 1000L)
            }
        }
    }

    val required = latestInfo?.takeIf { Updater.isRequired(context, it) }
    if (required != null) {
        CenterText(tr("Actualiza Monitor para continuar."))
        UpdateDialog(required, required = true) {}
        return
    }

    // Lectura periódica (solo con la app en pantalla)
    LaunchedEffect(Unit) {
        (context as LifecycleOwner).repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val ms = Prefs.refreshMs()
                if (ms < 0 && snap != null) {
                    delay(300)
                    continue
                }
                val s = withContext(Dispatchers.IO) {
                    val r = reader.sample()
                    // Mantiene el modo de eficiencia en procesos nuevos de esas apps
                    if (Prefs.efficiency.isNotEmpty()) {
                        val lagging = r.processes.filter { it.pkg != null && it.pkg in Prefs.efficiency && it.nice < 19 }
                        if (lagging.isNotEmpty()) Actions.setNice(lagging, 19)
                    }
                    r
                }
                snap = s
                hist = hist.add(s)
                // Historial de batería y widget (como mucho una vez por minuto / cada 30 s)
                samples++
                if (samples % 30 == 1) {
                    withContext(Dispatchers.IO) {
                        BatteryLog.record(context.applicationContext)
                        WidgetUpdater.push(
                            context.applicationContext, s.cpuTotal.toInt(), s.mem.usedPct.toInt(),
                            s.mainTempC, s.batt.level,
                        )
                    }
                }
                delay(if (ms < 0) 1000L else ms)
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val compact = maxWidth < 600.dp
        CompositionLocalProvider(LocalCompact provides compact) {
            if (win.mac) {
                MacShell(snap, hist, query, { query = it }, page, { page = it }, focusPid, { focusPid = it }, { latestInfo = it }) { runOpen = true }
                MacDialogs(runOpen, { runOpen = false }, update, required, { update = null })
                return@CompositionLocalProvider
            }
            Column(Modifier.fillMaxSize()) {
                TitleBar(query, { query = it })
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Row(Modifier.fillMaxSize()) {
                        NavRail(page, expanded = false, onMenu = { navOpen = true }) { page = it }
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(cornerTopStart(8))
                                .background(win.content)
                                .border(1.dp, win.border, cornerTopStart(8)),
                        ) {
                            val s = snap
                            if (s == null) {
                                CenterText(tr("Leyendo datos del sistema…"))
                            } else {
                                val onRun = { runOpen = true }
                                when (page) {
                                    Page.PROCESSES -> ProcessesPage(s, query, onRun) { pid ->
                                        focusPid = pid; page = Page.DETAILS
                                    }
                                    Page.PERFORMANCE -> PerformancePage(s, hist, onRun)
                                    Page.HISTORY -> HistoryPage(s, query, onRun)
                                    Page.STARTUP -> StartupPage(s, query, onRun)
                                    Page.USERS -> UsersPage(s, query, onRun)
                                    Page.DETAILS -> DetailsPage(s, query, focusPid, { focusPid = null }, onRun)
                                    Page.SERVICES -> ServicesPage(s, query, onRun) { pid ->
                                        focusPid = pid; page = Page.DETAILS
                                    }
                                    Page.GAMES -> GamesPage(onRun)
                                    Page.SETTINGS -> SettingsPage { latestInfo = it }
                                }
                            }
                        }
                    }
                    if (navOpen) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.25f))
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { navOpen = false },
                        )
                        NavRail(page, expanded = true, onMenu = { navOpen = false }) {
                            page = it
                            navOpen = false
                        }
                    }
                }
            }
        }
    }

    if (runOpen) RunTaskDialog { runOpen = false }
    update?.let { UpdateDialog(it) { update = null } }
}

@Composable
private fun TitleBar(query: String, onQuery: (String) -> Unit) {
    val win = LocalWin.current
    val compact = LocalCompact.current
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(start = 12.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Insights, null, tint = win.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        if (!compact) {
            Text("Monitor", fontSize = 12.sp, color = win.text)
            Spacer(Modifier.weight(1f))
        }
        Row(
            Modifier
                .then(if (compact) Modifier.weight(1f) else Modifier.width(380.dp))
                .height(34.dp)
                .clip(corner(4))
                .background(win.card)
                .border(1.dp, win.border, corner(4))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, null, tint = win.sub, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(tr("Busque un nombre, paquete o PID…"), fontSize = 13.sp, color = win.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = win.text),
                    cursorBrush = SolidColor(win.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Icon(
                    Icons.Outlined.Close, tr("Borrar"), tint = win.sub,
                    modifier = Modifier.size(16.dp).clickable { onQuery("") },
                )
            }
        }
        if (!compact) Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun NavRail(page: Page, expanded: Boolean, onMenu: () -> Unit, onSelect: (Page) -> Unit) {
    val win = LocalWin.current
    Column(
        Modifier
            .width(if (expanded) 250.dp else 52.dp)
            .fillMaxHeight()
            .then(if (expanded) Modifier.shadow(12.dp).background(win.mica) else Modifier)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        NavItem(Icons.Outlined.Menu, "", selected = false, expanded = false, onClick = onMenu)
        Spacer(Modifier.height(4.dp))
        Page.entries.filter { it != Page.SETTINGS }.forEach { p ->
            NavItem(p.icon, tr(p.title), page == p, expanded) { onSelect(p) }
        }
        Spacer(Modifier.weight(1f))
        NavItem(Page.SETTINGS.icon, tr(Page.SETTINGS.title), page == Page.SETTINGS, expanded) { onSelect(Page.SETTINGS) }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun NavItem(icon: ImageVector, title: String, selected: Boolean, expanded: Boolean, onClick: () -> Unit) {
    val win = LocalWin.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(vertical = 2.dp)
            .clip(corner(4))
            .background(if (selected) win.hover else Color.Transparent)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(16.dp)
                .clip(corner(2))
                .background(if (selected) win.accent else Color.Transparent),
        )
        Spacer(Modifier.width(10.dp))
        Icon(icon, title, tint = win.text, modifier = Modifier.size(20.dp))
        if (expanded && title.isNotEmpty()) {
            Spacer(Modifier.width(14.dp))
            Text(title, fontSize = 14.sp, color = win.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
