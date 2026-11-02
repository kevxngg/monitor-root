package com.kevxngg.monitorroot

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
fun SettingsPage() {
    val win = LocalWin.current
    Column(Modifier.fillMaxSize()) {
        PageHeader("Configuración") {}
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SectionLabel("General")
            SettingCard(Icons.Outlined.Palette, "Tema de la aplicación", "Elige la apariencia de la app") {
                WinDropdown(listOf("Usar configuración del sistema", "Claro", "Oscuro"), Prefs.theme) {
                    Prefs.theme = it; Prefs.save()
                }
            }
            SettingCard(Icons.Outlined.Home, "Página de inicio predeterminada", "La página que se abre al iniciar la app") {
                WinDropdown(Page.entries.filter { it != Page.SETTINGS }.map { it.title }, Prefs.startPage) {
                    Prefs.startPage = it; Prefs.save()
                }
            }
            SettingCard(Icons.Outlined.Speed, "Velocidad de actualización en tiempo real", "Cada cuánto se actualizan los datos") {
                WinDropdown(listOf("Alta (0,5 s)", "Normal (1 s)", "Baja (4 s)", "En pausa"), Prefs.speed) {
                    Prefs.speed = it; Prefs.save()
                }
            }
            Spacer(Modifier.height(8.dp))
            SectionLabel("Opciones de vista")
            SettingCard(Icons.Outlined.Category, "Agrupar por tipo", "Separa aplicaciones, procesos en segundo plano y de Android") {
                WinSwitch(Prefs.groupByType) { Prefs.groupByType = it; Prefs.save() }
            }
            SettingCard(Icons.Outlined.Badge, "Mostrar nombres de paquete", "Muestra com.ejemplo.app en lugar del nombre de la app") {
                WinSwitch(Prefs.showPackageNames) { Prefs.showPackageNames = it; Prefs.save() }
            }
            Spacer(Modifier.height(8.dp))
            SectionLabel("Modo de eficiencia")
            SettingCard(Icons.Outlined.Eco, "Apps en modo de eficiencia", "${Prefs.efficiency.size} apps con prioridad baja") {
                TextButton(enabled = Prefs.efficiency.isNotEmpty(), onClick = { Prefs.efficiency = emptySet(); Prefs.save() }) {
                    Text("Quitar todas")
                }
            }
            Spacer(Modifier.height(8.dp))
            SectionLabel("Acerca de")
            SettingCard(Icons.Outlined.Info, "Monitor Root 2.0", "Monitor del sistema para Android con root") {}
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LocalWin.current.text, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
private fun SettingCard(icon: ImageVector, title: String, subtitle: String, control: @Composable () -> Unit) {
    val win = LocalWin.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(win.card)
            .border(1.dp, win.border, RoundedCornerShape(6.dp))
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
private fun WinDropdown(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val win = LocalWin.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .widthIn(max = 170.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(win.content)
                .border(1.dp, win.border, RoundedCornerShape(4.dp))
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                options.getOrElse(selected) { "" }, fontSize = 13.sp, color = win.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Outlined.KeyboardArrowDown, null, tint = win.sub, modifier = Modifier.size(16.dp))
        }
        WinMenu(open, { open = false }, options.mapIndexed { i, o -> MenuEntry(o, checked = i == selected) { onSelect(i) } })
    }
}

@Composable
private fun WinSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
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
        title = { Text("Crear nueva tarea", fontSize = 18.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Escribe el nombre de una app para abrirla, o un comando para ejecutarlo.",
                    fontSize = 13.sp, color = win.sub,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; output = null },
                    label = { Text("Abrir") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                suggestions.forEach { a ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).clickable { if (launch(a.pkg)) onDismiss() }.padding(6.dp),
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
                    Text("Crear esta tarea con privilegios de administrador (root)", fontSize = 13.sp, color = win.text)
                }
                if (running) Text("Ejecutando…", fontSize = 12.sp, color = win.sub)
                output?.let {
                    Text(
                        it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = win.text,
                        modifier = Modifier.fillMaxWidth().background(win.content).padding(8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(enabled = !running && text.isNotBlank(), onClick = { submit() }) { Text("Aceptar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}
