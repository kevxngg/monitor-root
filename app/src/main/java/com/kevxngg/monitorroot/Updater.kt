package com.kevxngg.monitorroot

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateInfo(val build: Int, val title: String, val notes: String, val apkUrl: String, val sizeBytes: Long)

/** Busca nuevas versiones en las Releases de GitHub y las instala con root. */
object Updater {
    private const val API = "https://api.github.com/repos/kevxngg/monitor-root/releases/latest"
    private const val TMP = "/data/local/tmp/monitorroot-update.apk"

    fun currentBuild(ctx: Context): Int = try {
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0)).toInt()
    } catch (e: Exception) {
        0
    }

    /** Devuelve la última versión publicada, o null si hay error. */
    fun latest(): UpdateInfo? {
        val conn = URL(API).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val build = json.optString("tag_name").removePrefix("build-").toIntOrNull() ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    return UpdateInfo(
                        build = build,
                        title = json.optString("name"),
                        notes = json.optString("body"),
                        apkUrl = a.optString("browser_download_url"),
                        sizeBytes = a.optLong("size"),
                    )
                }
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Hay actualización si la publicada es más nueva que la instalada (y no se omitió). */
    fun check(ctx: Context, ignoreSkipped: Boolean = false): UpdateInfo? {
        val info = latest() ?: return null
        if (info.build <= currentBuild(ctx)) return null
        if (!ignoreSkipped && info.build <= Prefs.skippedBuild) return null
        return info
    }

    fun download(ctx: Context, url: String, onProgress: (Float) -> Unit): File {
        val file = File(ctx.cacheDir, "update.apk")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        try {
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return file
    }

    /**
     * Instala con root y vuelve a abrir la app. Se lanza en segundo plano porque
     * al instalar, Android cierra la versión que está abierta.
     */
    fun install(ctx: Context, file: File): Boolean {
        val copied = RootShell.run("cp ${Actions.q(file.path)} $TMP && chmod 644 $TMP && echo OK").any { it.contains("OK") }
        if (!copied) return false
        RootShell.run(
            "setsid sh -c 'pm install -r $TMP && am start -n ${ctx.packageName}/.MainActivity; rm -f $TMP' " +
                "</dev/null >/dev/null 2>&1 &"
        )
        return true
    }

    fun openInBrowser(ctx: Context, url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}

@Composable
fun UpdateDialog(info: UpdateInfo, onDismiss: () -> Unit) {
    val win = LocalWin.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val current = remember { Updater.currentBuild(ctx) }

    fun start() {
        busy = true
        status = tr("Descargando…")
        progress = 0f
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val f = Updater.download(ctx, info.apkUrl) { p -> progress = p }
                    status = tr("Instalando… la app se cerrará y volverá a abrirse.")
                    Updater.install(ctx, f)
                } catch (e: Exception) {
                    false
                }
            }
            if (!ok) {
                busy = false
                progress = null
                status = tr("No se pudo instalar automáticamente. Puedes descargarla desde el navegador.")
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("Hay una nueva versión"), fontSize = 18.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr("Build %d disponible (tienes la build %d)", info.build, current) +
                        if (info.sizeBytes > 0) " · ${formatBytes(info.sizeBytes)}" else "",
                    fontSize = 13.sp, color = win.sub,
                )
                if (info.notes.isNotBlank()) ReleaseNotes(info.notes)
                progress?.let { p ->
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = win.accent)
                }
                status?.let { Text(it, fontSize = 12.sp, color = win.sub) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { start() }) { Text(tr("Actualizar ahora")) }
        },
        dismissButton = {
            Row {
                if (!busy && status != null) {
                    TextButton(onClick = { Updater.openInBrowser(ctx, info.apkUrl) }) { Text(tr("Navegador")) }
                }
                TextButton(enabled = !busy, onClick = {
                    Prefs.skippedBuild = info.build
                    Prefs.save()
                    onDismiss()
                }) { Text(tr("Omitir")) }
                TextButton(enabled = !busy, onClick = onDismiss) { Text(tr("Más tarde")) }
            }
        },
    )
}

/** Muestra el registro de cambios: "## Título" en negrita y "- punto" como viñetas. */
@Composable
fun ReleaseNotes(notes: String) {
    val win = LocalWin.current
    val lines = notes.trim().lines()
    if (lines.none { it.trim().startsWith("#") }) {
        Text(tr("Novedades"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = win.text)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { raw ->
            val t = raw.trim()
            when {
                t.startsWith("#") -> Text(
                    t.trimStart('#').trim(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = win.text,
                    modifier = Modifier.padding(top = 6.dp),
                )
                t.startsWith("- ") || t.startsWith("* ") -> Row {
                    Text("•", fontSize = 13.sp, color = win.accent, modifier = Modifier.width(14.dp))
                    Text(t.drop(2), fontSize = 13.sp, color = win.text)
                }
                t.isNotEmpty() -> Text(t, fontSize = 13.sp, color = win.text)
            }
        }
    }
}
