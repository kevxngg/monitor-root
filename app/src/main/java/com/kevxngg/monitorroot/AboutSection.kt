package com.kevxngg.monitorroot

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ===================== DATOS DEL PROYECTO =====================

private const val REPO = "https://github.com/kevxngg/monitor-root"
private const val DEV = "kevxngg"

/** Red social: nombre, usuario, enlace (null = se copia el usuario) e icono. */
private class Social(val name: String, val user: String, val url: String?, val icon: ImageVector)

/** Redes sociales del desarrollador (iconos genéricos, sin logotipos de marcas). */
private val SOCIALS = listOf(
    Social("GitHub", "@kevxngg", "https://github.com/kevxngg", Icons.Outlined.Code),
    Social("Instagram", "@kevxngg", "https://www.instagram.com/kevxngg", Icons.Outlined.PhotoCamera),
    Social("Facebook", "kevxngg", "https://www.facebook.com/kevxngg", Icons.Outlined.Groups),
    Social("LinkedIn", "kevxngg", "https://www.linkedin.com/in/kevxngg", Icons.Outlined.Work),
    Social("TikTok", "@kevxngg", "https://www.tiktok.com/@kevxngg", Icons.Outlined.MusicNote),
    Social("Telegram", "@kevxngg", "https://t.me/kevxngg", Icons.Outlined.Send),
    // Discord no tiene enlace público por nombre de usuario: al tocarlo se copia
    Social("Discord", "kevxngg", null, Icons.Outlined.Forum),
)

private fun open(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}

private fun rootManager(): String? {
    val out = RootShell.run(
        "magisk -c 2>/dev/null || /data/adb/ksud -V 2>/dev/null || /data/adb/apd -V 2>/dev/null"
    ).firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        out.contains("MAGISK", true) -> "Magisk " + out.substringBefore(':')
        out.startsWith("ksud", true) -> "KernelSU " + out.substringAfter(' ')
        out.startsWith("apd", true) -> "APatch " + out.substringAfter(' ')
        else -> out
    }
}

/** Logo de la app (el mismo que el icono del lanzador). */
@Composable
fun AppLogo(size: Int) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(Color(0xFF117DBB)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Insights, null, tint = Color.White, modifier = Modifier.size((size * 0.56f).dp))
    }
}

private fun appVersion(ctx: Context): String = try {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
} catch (e: Exception) {
    ""
}

/** Fila de Configuración: "Monitor Root · versión" → abre la página Acerca de. */
@Composable
fun AboutEntry(onOpen: () -> Unit) {
    val win = LocalWin.current
    val ctx = LocalContext.current
    val version = remember { appVersion(ctx) }
    AboutCard {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppLogo(40)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Monitor", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = win.text)
                Text("${tr("Versión")} $version", fontSize = 12.sp, color = win.sub)
            }
            @Suppress("DEPRECATION")
            Icon(Icons.Outlined.KeyboardArrowRight, null, tint = win.sub, modifier = Modifier.size(20.dp))
        }
    }
}

/** Página completa de información de Monitor Root. */
@Composable
fun AboutPage(build: Int, onBack: () -> Unit) {
    val win = LocalWin.current
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val version = remember { appVersion(ctx) }
    var root by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var copiedUser by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { root = withContext(Dispatchers.IO) { rootManager() } }
    BackHandler(onBack = onBack)

    val device = listOf(
        Triple(Icons.Outlined.PhoneAndroid, tr("Dispositivo"), "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"),
        Triple(Icons.Outlined.Android, "Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
        Triple(Icons.Outlined.Memory, tr("Procesador"), socName()),
        Triple(Icons.Outlined.DeveloperBoard, "GPU", GlInfo.renderer ?: "—"),
        Triple(Icons.Outlined.Terminal, tr("Kernel"), System.getProperty("os.version") ?: "—"),
        Triple(Icons.Outlined.AdminPanelSettings, tr("Gestor de root"), root ?: "—"),
    )

    Column(Modifier.fillMaxSize()) {
        // Barra superior con botón de volver
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onBack).padding(10.dp)) {
                @Suppress("DEPRECATION")
                Icon(Icons.Outlined.ArrowBack, tr("Volver"), tint = win.text, modifier = Modifier.size(20.dp))
            }
            Text(tr("Acerca de"), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = win.text)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ---- Cabecera ----
            Column(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppLogo(80)
                Spacer(Modifier.height(12.dp))
                Text("Monitor", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = win.text)
                Text("${tr("Versión")} $version", fontSize = 13.sp, color = win.sub)
                Spacer(Modifier.height(6.dp))
                Text(tr("Monitor del sistema para Android con root"), fontSize = 13.sp, color = win.text)
            }

            // ---- Proyecto ----
            SectionTitle(tr("Proyecto"))
            AboutCard {
                AboutRow(Icons.Outlined.Person, tr("Desarrollador"), DEV) { open(ctx, "https://github.com/$DEV") }
                AboutDivider()
                AboutRow(Icons.Outlined.Code, tr("Repositorio"), REPO.removePrefix("https://")) { open(ctx, REPO) }
                AboutDivider()
                AboutRow(Icons.Outlined.History, tr("Registro de cambios"), tr("Ver todas las versiones")) { open(ctx, "$REPO/releases") }
                AboutDivider()
                AboutRow(Icons.Outlined.BugReport, tr("Informar un problema"), "GitHub Issues") { open(ctx, "$REPO/issues/new") }
            }

            // ---- Redes sociales ----
            SectionTitle(tr("Redes sociales"))
            AboutCard {
                SOCIALS.forEachIndexed { i, sn ->
                    if (i > 0) AboutDivider()
                    val url = sn.url
                    if (url != null) {
                        AboutRow(sn.icon, sn.name, sn.user) { open(ctx, url) }
                    } else {
                        AboutRow(sn.icon, sn.name, if (copiedUser == sn.name) tr("Usuario copiado") else sn.user, copyIcon = true) {
                            clipboard.setText(AnnotatedString(sn.user))
                            copiedUser = sn.name
                        }
                    }
                }
            }

            // ---- Dispositivo (útil al informar un problema) ----
            SectionTitle(tr("Información del dispositivo"))
            AboutCard {
                device.forEachIndexed { i, (icon, title, value) ->
                    if (i > 0) AboutDivider()
                    AboutRow(icon, title, value)
                }
                AboutDivider()
                Row(
                    Modifier.fillMaxWidth().clickable {
                        val text = "Monitor $version\n" + device.joinToString("\n") { "${it.second}: ${it.third}" }
                        clipboard.setText(AnnotatedString(text))
                        copied = true
                    }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.ContentCopy, null, tint = win.accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (copied) tr("Información copiada") else tr("Copiar información"), fontSize = 14.sp, color = win.accent)
                        Text(tr("Útil al informar un problema"), fontSize = 12.sp, color = win.sub)
                    }
                }
            }

            Text(
                "Monitor $version · © ${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)} $DEV",
                fontSize = 11.sp, color = win.sub, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LocalWin.current.text,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun AboutCard(content: @Composable ColumnScope.() -> Unit) {
    val win = LocalWin.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(win.card)
            .border(1.dp, win.border, RoundedCornerShape(8.dp)),
        content = content,
    )
}

@Composable
private fun AboutDivider() {
    Box(Modifier.padding(start = 52.dp).fillMaxWidth().height(1.dp).background(LocalWin.current.divider))
}

@Composable
private fun AboutRow(icon: ImageVector, title: String, value: String, copyIcon: Boolean = false, onClick: (() -> Unit)? = null) {
    val win = LocalWin.current
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = win.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = win.text)
            Text(value, fontSize = 12.sp, color = win.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (onClick != null) {
            @Suppress("DEPRECATION")
            Icon(if (copyIcon) Icons.Outlined.ContentCopy else Icons.Outlined.OpenInNew, null, tint = win.sub, modifier = Modifier.size(16.dp))
        }
    }
}
