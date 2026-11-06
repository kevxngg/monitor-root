package com.kevxngg.monitorroot

import android.os.Build

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ===================== CONFIGURACIÓN =====================

@Composable
fun SettingsPage(onVerified: (UpdateInfo) -> Unit = {}) {
    val win = LocalWin.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val build = remember { Updater.currentBuild(ctx) }
    val version = remember { Updater.currentVersion(ctx) }
    var checking by remember { mutableStateOf(false) }
    var checkMsg by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<UpdateInfo?>(null) }
    found?.let { UpdateDialog(it) { found = null } }
    var showAbout by remember { mutableStateOf(false) }
    var fpsTesting by remember { mutableStateOf(false) }
    var fpsReport by remember { mutableStateOf<String?>(null) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    fpsReport?.let { report ->
        AlertDialog(
            onDismissRequest = { fpsReport = null },
            title = { Text(tr("Probar medición de FPS"), fontSize = 18.sp) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(tr("Abre un juego, vuelve aquí y prueba de nuevo para comparar."), fontSize = 12.sp, color = win.sub)
                    Spacer(Modifier.height(8.dp))
                    Text(report, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = win.text)
                }
            },
            confirmButton = {
                TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(report)) }) { Text(tr("Copiar información")) }
            },
            dismissButton = { TextButton(onClick = { fpsReport = null }) { Text(tr("Cerrar")) } },
        )
    }
    if (showAbout) {
        AboutPage(build) { showAbout = false }
        return
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader(tr("Configuración")) {}
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SectionLabel(tr("General"))
            SettingCard(Icons.Outlined.Language, tr("Idioma"), tr("Idioma de la aplicación")) {
                val codes = listOf("") + I18n.LANGS.map { it.first }
                val names = listOf(tr("Usar idioma del sistema")) + I18n.LANGS.map { it.second }
                WinDropdown(names, codes.indexOf(Prefs.language).coerceAtLeast(0)) {
                    Prefs.language = codes[it]
                    Prefs.save()
                    I18n.apply(Prefs.language)
                }
            }
            SettingCard(Icons.Outlined.Palette, tr("Tema de la aplicación"), tr("Elige la apariencia de la app")) {
                WinDropdown(
                    listOf(tr("Usar configuración del sistema"), tr("Claro"), tr("Oscuro"), tr("Negro puro")),
                    Prefs.theme.coerceIn(0, 3),
                ) {
                    Prefs.theme = it; Prefs.save()
                }
            }
            SettingCard(Icons.Outlined.Home, tr("Página de inicio predeterminada"), tr("La página que se abre al iniciar la app")) {
                WinDropdown(Page.entries.filter { it != Page.SETTINGS }.map { tr(it.title) }, Prefs.startPage) {
                    Prefs.startPage = it; Prefs.save()
                }
            }
            SettingCard(Icons.Outlined.Speed, tr("Velocidad de actualización en tiempo real"), tr("Cada cuánto se actualizan los datos")) {
                WinDropdown(listOf(tr("Alta (0,5 s)"), tr("Normal (1 s)"), tr("Baja (4 s)"), tr("En pausa")), Prefs.speed) {
                    Prefs.speed = it; Prefs.save()
                }
            }
            Spacer(Modifier.height(8.dp))
            SectionLabel(tr("Herramientas"))
            SettingCard(Icons.Outlined.PictureInPicture, tr("Monitor flotante"), tr("Muestra CPU, GPU, RAM, temperatura y FPS encima de otras apps")) {
                WinSwitch(OverlayState.running) { on ->
                    val app = ctx.applicationContext
                    scope.launch(Dispatchers.IO) {
                        if (on) {
                            if (!OverlayControl.start(app)) OverlayControl.openPermissionSettings(app)
                        } else {
                            OverlayControl.stop(app)
                        }
                    }
                }
            }
            SettingCard(Icons.Outlined.ViewAgenda, tr("Diseño del monitor flotante"), tr("También puedes tocar el monitor flotante para cambiar de diseño.")) {
                WinDropdown(
                    listOf(tr("Completo"), tr("Horizontal"), tr("Compacto"), tr("Solo FPS"), tr("Gráfica")),
                    Prefs.overlayStyle.coerceIn(0, OverlayService.STYLES - 1),
                ) { Prefs.overlayStyle = it; Prefs.save() }
            }
            SettingCard(Icons.Outlined.Speed, tr("Probar medición de FPS"), tr("Comprueba qué métodos de medición funcionan en este teléfono.")) {
                TextButton(enabled = !fpsTesting, onClick = {
                    fpsTesting = true
                    scope.launch {
                        fpsReport = withContext(Dispatchers.IO) {
                            if (RootShell.open()) QuickStats.diagnoseFps() else tr("Sin acceso root")
                        }
                        fpsTesting = false
                    }
                }) { Text(if (fpsTesting) tr("Probando…") else tr("Probar")) }
            }
            SettingCard(Icons.Outlined.FormatSize, tr("Tamaño"), tr("Tamaño del texto del monitor flotante")) {
                WinDropdown(listOf(tr("Pequeño"), tr("Normal"), tr("Grande")), Prefs.overlaySize) { Prefs.overlaySize = it; Prefs.save() }
            }
            SettingCard(Icons.Outlined.Opacity, tr("Opacidad"), tr("Qué tan transparente es el fondo")) {
                WinDropdown(listOf(tr("Baja"), tr("Media"), tr("Alta")), Prefs.overlayOpacity) { Prefs.overlayOpacity = it; Prefs.save() }
            }
            SettingCard(Icons.Outlined.Widgets, tr("Widget"), tr("CPU, RAM, temperatura y batería en la pantalla de inicio. También puedes añadirlo desde los widgets de tu teléfono.")) {
                TextButton(onClick = { pinWidget(ctx) }) { Text(tr("Añadir")) }
            }
            SettingCard(
                Icons.Outlined.Tune, tr("Botón de ajustes rápidos"),
                if (Build.VERSION.SDK_INT >= 33) tr("Enciende o apaga el monitor flotante desde el panel de notificaciones.")
                else tr("Edita el panel de ajustes rápidos y arrastra el botón Monitor."),
            ) {
                if (Build.VERSION.SDK_INT >= 33) TextButton(onClick = { addTile(ctx) }) { Text(tr("Añadir")) }
            }
            Spacer(Modifier.height(8.dp))
            SectionLabel(tr("Alertas"))
            SettingCard(Icons.Outlined.Thermostat, tr("Temperatura alta"), tr("Avisa si el teléfono supera esta temperatura.")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WinDropdown(Prefs.TEMP_LIMITS.map { "$it °C" }, Prefs.alertTempLimit) { Prefs.alertTempLimit = it; Prefs.save() }
                    Spacer(Modifier.width(6.dp))
                    WinSwitch(Prefs.alertTemp) { Prefs.alertTemp = it; Prefs.save() }
                }
            }
            SettingCard(Icons.Outlined.Memory, tr("CPU en segundo plano"), tr("Avisa si una app usa mucha CPU sin estar en pantalla, con un botón para finalizarla.")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WinDropdown(Prefs.CPU_LIMITS.map { "$it %" }, Prefs.alertCpuLimit) { Prefs.alertCpuLimit = it; Prefs.save() }
                    Spacer(Modifier.width(6.dp))
                    WinSwitch(Prefs.alertCpu) { Prefs.alertCpu = it; Prefs.save() }
                }
            }
            SettingCard(Icons.Outlined.BatteryChargingFull, tr("Límite de carga"), tr("Avisa cuando la batería llega a este nivel mientras carga.")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WinDropdown(Prefs.CHARGE_LIMITS.map { "$it %" }, Prefs.alertChargeLimit) { Prefs.alertChargeLimit = it; Prefs.save() }
                    Spacer(Modifier.width(6.dp))
                    WinSwitch(Prefs.alertCharge) { Prefs.alertCharge = it; Prefs.save() }
                }
            }
            Text(tr("Las alertas se revisan cada pocos minutos, también con la app cerrada."), fontSize = 12.sp, color = win.sub, modifier = Modifier.padding(start = 4.dp))
            Spacer(Modifier.height(8.dp))
            SectionLabel(tr("Opciones de vista"))
            SettingCard(Icons.Outlined.Category, tr("Agrupar por tipo"), tr("Separa aplicaciones, procesos en segundo plano y de Android")) {
                WinSwitch(Prefs.groupByType) { Prefs.groupByType = it; Prefs.save() }
            }
            SettingCard(Icons.Outlined.Badge, tr("Mostrar nombres de paquete"), tr("Muestra com.ejemplo.app en lugar del nombre de la app")) {
                WinSwitch(Prefs.showPackageNames) { Prefs.showPackageNames = it; Prefs.save() }
            }
            Spacer(Modifier.height(8.dp))
            SectionLabel(tr("Modo de eficiencia"))
            SettingCard(Icons.Outlined.Eco, tr("Apps en modo de eficiencia"), tr("%d apps con prioridad baja", Prefs.efficiency.size)) {
                TextButton(enabled = Prefs.efficiency.isNotEmpty(), onClick = { Prefs.efficiency = emptySet(); Prefs.save() }) {
                    Text(tr("Quitar todas"))
                }
            }
            Spacer(Modifier.height(8.dp))
            SectionLabel(tr("Actualizaciones"))
            SettingCard(Icons.Outlined.SystemUpdate, tr("Buscar actualizaciones al abrir"), tr("Avisa cuando hay una versión nueva en GitHub")) {
                WinSwitch(Prefs.autoUpdate) { Prefs.autoUpdate = it; Prefs.save() }
            }
            SettingCard(Icons.Outlined.Update, tr("Buscar ahora"), checkMsg ?: tr("Versión instalada: %s", version)) {
                TextButton(enabled = !checking, onClick = {
                    checking = true
                    checkMsg = tr("Buscando…")
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { Updater.fetch(ctx.applicationContext) }
                        checking = false
                        when (result) {
                            is UpdateResult.Success -> {
                                val info = result.info
                                onVerified(info)
                                if (Updater.isOffered(ctx, info, ignoreSkipped = true)) {
                                    if (!Updater.isRequired(ctx, info)) found = info
                                    checkMsg = tr("Versión %s disponible", info.label)
                                } else checkMsg = tr("Ya tienes la última versión (%s)", version)
                            }
                            is UpdateResult.Failure -> checkMsg = result.message()
                        }
                    }
                }) { Text(tr("Buscar")) }
            }
            Spacer(Modifier.height(8.dp))
            SectionLabel(tr("Acerca de"))
            AboutEntry { showAbout = true }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LocalWin.current.text, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
fun SettingCard(icon: ImageVector, title: String, subtitle: String, control: @Composable () -> Unit) {
    val win = LocalWin.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(corner(6))
            .background(win.card)
            .border(1.dp, win.border, corner(6))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = win.text, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = win.text)
            Text(subtitle, fontSize = 12.sp, color = win.sub)
        }
        Spacer(Modifier.width(8.dp))
        control()
    }
}

@Composable
fun WinDropdown(options: List<String>, selected: Int, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    val win = LocalWin.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .widthIn(max = 170.dp)
                .clip(corner(4))
                .background(win.content)
                .border(1.dp, win.border, corner(4))
                .clickable(enabled = enabled) { open = true }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                options.getOrElse(selected) { "" }, fontSize = 13.sp, color = if (enabled) win.text else win.sub,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Outlined.KeyboardArrowDown, null, tint = win.sub, modifier = Modifier.size(16.dp))
        }
        WinMenu(open, { open = false }, options.mapIndexed { i, o -> MenuEntry(o, checked = i == selected) { onSelect(i) } })
    }
}

@Composable
fun WinSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val win = LocalWin.current
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = win.accent,
            checkedThumbColor = if (win.dark) win.mica else win.card,
            uncheckedTrackColor = win.content,
            uncheckedBorderColor = win.sub,
            uncheckedThumbColor = win.sub,
        ),
    )
}

// ===================== EJECUTAR NUEVA TAREA =====================

@Composable
fun RunTaskDialog(onDismiss: () -> Unit) {
    val win = LocalWin.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var admin by remember { mutableStateOf(true) }
    var output by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<AppMeta>>(emptyList()) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            Apps.launchablePackages().mapNotNull { Apps.meta(it) }.sortedBy { it.label.lowercase() }
        }
    }
    val suggestions = if (text.isBlank()) emptyList() else apps.filter {
        it.label.contains(text, true) || it.pkg.contains(text, true)
    }.take(5)

    fun launch(pkg: String): Boolean {
        val i = Apps.pm()?.getLaunchIntentForPackage(pkg) ?: return false
        ctx.startActivity(i)
        return true
    }

    fun submit() {
        val t = text.trim()
        if (t.isEmpty()) return
        val exact = apps.firstOrNull { it.pkg == t || it.label.equals(t, true) }
        if (exact != null && launch(exact.pkg)) {
            onDismiss()
            return
        }
        running = true
        scope.launch {
            output = withContext(Dispatchers.IO) { Actions.runCommand(t, admin) }
            running = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Crear nueva tarea"), fontSize = 18.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr("Escribe el nombre de una app para abrirla, o un comando para ejecutarlo."),
                    fontSize = 13.sp, color = win.sub,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; output = null },
                    label = { Text(tr("Abrir")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                suggestions.forEach { a ->
                    Row(
                        Modifier.fillMaxWidth().clip(corner(4)).clickable { if (launch(a.pkg)) onDismiss() }.padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(a.icon, a.label, 20.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(a.label, fontSize = 13.sp, color = win.text)
                            Text(a.pkg, fontSize = 11.sp, color = win.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { admin = !admin }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = admin, onCheckedChange = { admin = it })
                    Text(tr("Crear esta tarea con privilegios de administrador (root)"), fontSize = 13.sp, color = win.text)
                }
                if (running) Text(tr("Ejecutando…"), fontSize = 12.sp, color = win.sub)
                output?.let {
                    Text(
                        it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = win.text,
                        modifier = Modifier.fillMaxWidth().background(win.content).padding(8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(enabled = !running && text.isNotBlank(), onClick = { submit() }) { Text(tr("Aceptar")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cerrar")) } },
    )
}

private fun pinWidget(ctx: android.content.Context) {
    val manager = android.appwidget.AppWidgetManager.getInstance(ctx)
    if (manager.isRequestPinAppWidgetSupported) {
        manager.requestPinAppWidget(android.content.ComponentName(ctx, MonitorWidget::class.java), null, null)
    }
}

private fun addTile(ctx: android.content.Context) {
    if (Build.VERSION.SDK_INT < 33) return
    try {
        ctx.getSystemService(android.app.StatusBarManager::class.java)?.requestAddTileService(
            android.content.ComponentName(ctx, MonitorTileService::class.java),
            tr("Monitor flotante"),
            android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_fg),
            ctx.mainExecutor,
        ) { }
    } catch (_: Exception) {
    }
}
