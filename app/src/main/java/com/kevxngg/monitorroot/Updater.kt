package com.kevxngg.monitorroot

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.IOException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val build: Int,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val minBuild: Int = 0,
    /** Versión visible (por ejemplo "2.3"); las versiones antiguas no la tienen. */
    val version: String = "",
    /** Huella SHA-256 de la APK publicada (vacía en versiones antiguas). */
    val sha256: String = "",
) {
    /** Lo que se muestra al usuario: "2.3", o "build 22" en versiones antiguas. */
    val label: String get() = version.ifEmpty { "build $build" }
}

sealed interface UpdateResult {
    data class Success(val info: UpdateInfo) : UpdateResult
    data class Failure(val reason: UpdateFailure, val privateDns: Boolean) : UpdateResult
}

enum class UpdateFailure { DNS, TIMEOUT, TLS, HTTP, DATA, NETWORK }

fun UpdateResult.Failure.message(): String {
    val detail = tr(when (reason) {
        UpdateFailure.DNS -> "No se pudo resolver la dirección de GitHub."
        UpdateFailure.TIMEOUT -> "GitHub tardó demasiado en responder."
        UpdateFailure.TLS -> "No se pudo establecer una conexión segura con GitHub. Revisa la fecha y hora del teléfono."
        UpdateFailure.HTTP -> "GitHub rechazó la consulta o no está disponible. Inténtalo más tarde."
        UpdateFailure.DATA -> "La información de actualización recibida no es válida."
        UpdateFailure.NETWORK -> "No se pudo conectar con GitHub. Revisa la conexión de esta aplicación."
    })
    return if (privateDns && reason in setOf(UpdateFailure.DNS, UpdateFailure.TIMEOUT, UpdateFailure.TLS, UpdateFailure.NETWORK)) {
        detail + "\n" + tr("Hay DNS privado activo y podría estar relacionado. Prueba DNS automático o desactívalo temporalmente y vuelve a buscar.")
    } else detail
}

/** Busca nuevas versiones en las Releases de GitHub y las instala con root. */
object Updater {
    private const val API = "https://api.github.com/repos/kevxngg/monitor-root/releases/latest"
    private const val TMP = "/data/local/tmp/monitorroot-update.apk"

    /** "Más tarde": no vuelve a preguntar por esa build durante una hora. */
    @Volatile var snoozedBuild = 0
    @Volatile var snoozedUntil = 0L

    fun isSnoozed(build: Int) = build == snoozedBuild && System.currentTimeMillis() < snoozedUntil

    fun snooze(build: Int) {
        snoozedBuild = build
        snoozedUntil = System.currentTimeMillis() + 60 * 60 * 1000
    }

    /** Versión instalada visible (por ejemplo "2.3"). */
    fun currentVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    fun currentBuild(ctx: Context): Int = try {
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0)).toInt()
    } catch (e: Exception) {
        0
    }

    // Archivo que se publica junto a cada APK. Se descarga desde la CDN de GitHub,
    // que no tiene el límite de 60 consultas por hora de la API.
    private const val FEED = "https://github.com/kevxngg/monitor-root/releases/latest/download/update.json"

    private class HttpFailure(val status: Int) : IOException("HTTP $status")

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.useCaches = false
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Cache-Control", "no-cache")
            conn.setRequestProperty("User-Agent", "MonitorRoot-Android")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code != 200) throw HttpFailure(code)
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun validated(info: UpdateInfo): UpdateInfo {
        require(info.build > 0)
        val url = URL(info.apkUrl)
        require(url.protocol == "https" && url.host == "github.com" &&
            url.path.startsWith("/kevxngg/monitor-root/releases/download/") && url.path.endsWith(".apk"))
        return info
    }

    private fun fromFeed(): UpdateInfo {
        val j = JSONObject(get("$FEED?t=${System.currentTimeMillis()}"))
        val build = j.getInt("build")
        val version = j.optString("version")
        return validated(
            UpdateInfo(
                build, "Monitor ${version.ifEmpty { "build $build" }}", j.optString("notes"), j.getString("apk"),
                j.optLong("size"), j.optInt("min_build", 0), version, j.optString("sha256"),
            )
        )
    }

    private fun fromApi(): UpdateInfo {
        val json = JSONObject(get("$API?t=${System.currentTimeMillis()}"))
        val build = json.getString("tag_name").removePrefix("build-").toInt()
        val assets = json.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == "MonitorRoot.apk") {
                // El título de la versión es "Monitor 2.3": se toma el número del final
                val version = json.optString("name").substringAfterLast(' ').takeIf { v -> v.matches(Regex("\\d+(\\.\\d+)+")) } ?: ""
                return validated(UpdateInfo(build, json.optString("name"), json.optString("body"), a.getString("browser_download_url"), a.optLong("size"), version = version))
            }
        }
        error("Release has no MonitorRoot.apk")
    }

    private fun reason(e: Exception): UpdateFailure = when (e) {
        is UnknownHostException -> UpdateFailure.DNS
        is SocketTimeoutException -> UpdateFailure.TIMEOUT
        is SSLException -> UpdateFailure.TLS
        is HttpFailure -> UpdateFailure.HTTP
        is IOException -> UpdateFailure.NETWORK
        else -> UpdateFailure.DATA
    }

    fun failure(ctx: Context, e: Exception) = UpdateResult.Failure(reason(e), privateDnsActive(ctx))

    fun privateDnsActive(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT < 28) false else {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val network = cm?.activeNetwork
            val active = network?.let { cm?.getLinkProperties(it)?.isPrivateDnsActive } == true
            // Strict DNS can be configured but not yet connected.
            active || Settings.Global.getString(ctx.contentResolver, "private_dns_mode") == "hostname"
        }
    } catch (_: Exception) { false }

    /** A result belongs to this request; simultaneous checks cannot overwrite it. */
    @Synchronized
    fun fetch(ctx: Context): UpdateResult {
        val failures = mutableListOf<UpdateFailure>()
        for (source in listOf(::fromFeed, ::fromApi)) {
            try {
                val info = source()
                val j = JSONObject().put("build", info.build).put("title", info.title)
                    .put("notes", info.notes).put("apk", info.apkUrl).put("size", info.sizeBytes).put("min_build", info.minBuild).put("version", info.version).put("sha256", info.sha256)
                ctx.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putString("latest", j.toString()).apply()
                return UpdateResult.Success(info)
            } catch (e: Exception) {
                Log.w("MonitorRootUpdater", "Update source failed: ${e.javaClass.simpleName}: ${e.message}")
                failures += reason(e)
            }
        }
        val reason = failures.firstOrNull { it in setOf(UpdateFailure.DNS, UpdateFailure.TLS, UpdateFailure.TIMEOUT, UpdateFailure.NETWORK) }
            ?: failures.last()
        return UpdateResult.Failure(reason, privateDnsActive(ctx))
    }

    fun cached(ctx: Context): UpdateInfo? = try {
        val text = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE).getString("latest", null)
        if (text == null) null else {
            val j = JSONObject(text)
            validated(UpdateInfo(j.getInt("build"), j.optString("title"), j.optString("notes"), j.getString("apk"), j.optLong("size"), j.optInt("min_build", 0), j.optString("version"), j.optString("sha256")))
        }
    } catch (_: Exception) { null }

    fun isRequired(ctx: Context, info: UpdateInfo) = UpdatePolicy.isRequired(currentBuild(ctx), info.minBuild)

    fun isOffered(ctx: Context, info: UpdateInfo, ignoreSkipped: Boolean = false): Boolean =
        UpdatePolicy.isOffered(currentBuild(ctx), info.build, Prefs.skippedBuild, ignoreSkipped, info.minBuild)

    fun download(ctx: Context, url: String, onProgress: (Float) -> Unit): File {
        val file = File(ctx.cacheDir, "update.apk")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        try {
            val code = conn.responseCode
            if (code != 200) throw HttpFailure(code)
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
    fun install(ctx: Context, file: File, sha256: String? = null, size: Long? = null): Boolean {
        // Se instala con root y sin confirmación: antes se comprueba que la APK sea legítima
        if (!verify(ctx, file, sha256, size)) {
            file.delete()
            return false
        }
        val copied = RootShell.run("cp ${Actions.q(file.path)} $TMP && chmod 644 $TMP && echo OK").any { it.contains("OK") }
        if (!copied) return false
        RootShell.run(
            "setsid sh -c 'pm install -r $TMP && am start -n ${ctx.packageName}/.MainActivity; rm -f $TMP' " +
                "</dev/null >/dev/null 2>&1 &"
        )
        return true
    }

    /**
     * Comprueba la APK descargada antes de instalarla:
     *  - tamaño y huella SHA-256 iguales a los publicados (descarga completa e intacta)
     *  - es Monitor (mismo nombre de paquete)
     *  - está firmada con la misma clave que la versión instalada
     *  - es una versión más nueva
     */
    fun verify(ctx: Context, file: File, sha256: String?, size: Long?): Boolean = try {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.path, flags)
        val installed = pm.getPackageInfo(ctx.packageName, flags)
        when {
            !file.exists() || file.length() == 0L -> false
            size != null && size > 0 && file.length() != size -> false
            !sha256.isNullOrBlank() && !sha256Of(file).equals(sha256, ignoreCase = true) -> false
            archive == null || archive.packageName != ctx.packageName -> false
            PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(installed) -> false
            // Comparte al menos un certificado (admite la rotación de clave de Android 9+)
            else -> signerDigests(archive).intersect(signerDigests(installed)).isNotEmpty()
        }
    } catch (e: Exception) {
        false
    }

    private fun signerDigests(pi: PackageInfo): Set<String> {
        @Suppress("DEPRECATION")
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val si = pi.signingInfo ?: return emptySet()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else pi.signatures
        return sigs.orEmpty().map { sha256Of(it.toByteArray()) }.toSet()
    }

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun openInBrowser(ctx: Context, url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}

@Composable
fun UpdateDialog(info: UpdateInfo, required: Boolean = false, onDismiss: () -> Unit) {
    val win = LocalWin.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val current = remember { Updater.currentVersion(ctx) }

    fun start() {
        busy = true
        status = tr("Descargando…")
        progress = 0f
        scope.launch {
            var failureMessage: String? = null
            val ok = withContext(Dispatchers.IO) {
                try {
                    val f = Updater.download(ctx, info.apkUrl) { p -> progress = p }
                    status = tr("Instalando… la app se cerrará y volverá a abrirse.")
                    Updater.install(ctx, f, info.sha256, info.sizeBytes)
                } catch (e: Exception) {
                    failureMessage = Updater.failure(ctx, e).message()
                    false
                }
            }
            if (!ok) {
                busy = false
                progress = null
                status = listOfNotNull(failureMessage, tr("No se pudo instalar automáticamente. Puedes descargarla desde el navegador.")).joinToString("\n")
            } else {
                // A successful replacement closes this process. If it stays alive,
                // restore the controls so a failed root install cannot trap the user.
                delay(30_000)
                busy = false
                progress = null
                status = tr("La instalación no se ha completado. Puedes reintentar o descargar desde el navegador.")
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy && !required) onDismiss() },
        title = { Text(tr(if (required) "Actualización obligatoria" else "Hay una nueva versión"), fontSize = 18.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr("Versión %s disponible (tienes la %s)", info.label, current) +
                        if (info.sizeBytes > 0) " · ${formatBytes(info.sizeBytes)}" else "",
                    fontSize = 13.sp, color = win.sub,
                )
                if (required) Text(tr("Esta versión ya no es compatible. Actualiza para continuar usando Monitor."), fontSize = 13.sp)
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
                if (!busy && (status != null || required)) {
                    TextButton(onClick = { Updater.openInBrowser(ctx, info.apkUrl) }) { Text(tr("Navegador")) }
                }
                if (!required) TextButton(enabled = !busy, onClick = {
                    Prefs.skippedBuild = info.build
                    Prefs.save()
                    onDismiss()
                }) { Text(tr("Omitir")) }
                if (!required) TextButton(enabled = !busy, onClick = { Updater.snooze(info.build); onDismiss() }) { Text(tr("Más tarde")) }
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
