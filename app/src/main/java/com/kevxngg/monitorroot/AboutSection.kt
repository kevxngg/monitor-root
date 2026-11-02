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
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ===================== DATOS DEL PROYECTO =====================

private const val REPO = "https://github.com/kevxngg/monitor-root"
private const val DEV = "kevxngg"

/** Redes sociales del desarrollador: (nombre, usuario, enlace). */
private val SOCIALS: List<Triple<String, String, String>> = listOf(
    Triple("GitHub", "@kevxngg", "https://github.com/kevxngg"),
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

@Composable
fun AboutSection(build: Int) {
    val win = LocalWin.current
    val ctx = LocalContext.current
    val version = remember {
        try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }
    var root by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { root = withContext(Dispatchers.IO) { rootManager() } }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ---- Cabecera de la app ----
        AboutCard {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF117DBB)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Insights, null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Monitor Root", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = win.text)
                    Text("${tr("Versión")} $version", fontSize = 13.sp, color = win.sub)
                    Spacer(Modifier.height(4.dp))
                    Text(tr("Monitor del sistema para Android con root"), fontSize = 13.sp, color = win.text)
                }
            }
        }

        // ---- Proyecto ----
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
        Text(tr("Redes sociales"), fontSize = 12.sp, color = win.sub, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
        AboutCard {
            SOCIALS.forEachIndexed { i, (name, user, url) ->
                if (i > 0) AboutDivider()
                AboutRow(Icons.Outlined.AlternateEmail, name, user) { open(ctx, url) }
            }
        }

        // ---- Dispositivo ----
        Text(tr("Información del dispositivo"), fontSize = 12.sp, color = win.sub, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
        AboutCard {
            AboutRow(Icons.Outlined.PhoneAndroid, tr("Dispositivo"), "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}")
            AboutDivider()
            AboutRow(Icons.Outlined.Android, "Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            AboutDivider()
            AboutRow(Icons.Outlined.Memory, tr("Procesador"), socName())
            AboutDivider()
            AboutRow(Icons.Outlined.DeveloperBoard, "GPU", GlInfo.renderer ?: "—")
            AboutDivider()
            AboutRow(Icons.Outlined.Terminal, tr("Kernel"), System.getProperty("os.version") ?: "—")
            AboutDivider()
            AboutRow(Icons.Outlined.AdminPanelSettings, tr("Gestor de root"), root ?: "—")
        }
        Text(
            "Monitor Root · build $build · © ${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)} $DEV",
            fontSize = 11.sp, color = win.sub,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
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
private fun AboutRow(icon: ImageVector, title: String, value: String, onClick: (() -> Unit)? = null) {
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
            Icon(Icons.Outlined.OpenInNew, null, tint = win.sub, modifier = Modifier.size(16.dp))
        }
    }
}
