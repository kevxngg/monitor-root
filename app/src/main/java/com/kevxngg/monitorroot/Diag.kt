package com.kevxngg.monitorroot

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostics log kept only on the phone: which features work on this device and which fail or are
 * not available (temperature sensor used, FPS method, GPU readings, root errors, crashes...).
 *
 * Nothing is sent anywhere. The user can read it, share it or attach it to a GitHub issue from
 * Settings, so a problem on a phone the developer does not have can be understood.
 */
object Diag {
    private const val FILE = "diagnostics.txt"
    private const val MAX_LINES = 400
    @Volatile private var dir: File? = null
    /** The same message is written at most once every 10 minutes (readings repeat every second). */
    private val lastSeen = HashMap<String, Long>()

    /** Call once per process (activity and services). Also records crashes. */
    fun init(ctx: Context) {
        if (dir != null) return
        dir = ctx.applicationContext.filesDir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                write("crash", "in ${t.name}: " + sw.toString().lines().take(25).joinToString("\n    "), force = true)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** [area] groups the line (temperature, fps, gpu, root, game...). */
    fun log(area: String, msg: String) = write(area, msg, force = false)

    @Synchronized
    private fun write(area: String, msg: String, force: Boolean) {
        val d = dir ?: return
        val key = "$area|$msg"
        val now = System.currentTimeMillis()
        if (!force && (lastSeen[key]?.let { now - it < 10 * 60_000 } == true)) return
        lastSeen[key] = now
        try {
            val f = File(d, FILE)
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now))
            val lines = (if (f.exists()) f.readLines() else emptyList()) + "$time [$area] $msg"
            f.writeText(lines.takeLast(MAX_LINES).joinToString("\n") + "\n")
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun clear(ctx: Context) {
        File(ctx.filesDir, FILE).delete()
        File(ctx.filesDir, "game_log.txt").delete()
        lastSeen.clear()
    }

    /** Device data that explains most differences between phones (no personal data). */
    fun deviceInfo(ctx: Context): String = buildString {
        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        appendLine("Monitor $version")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.DISPLAY}")
        appendLine("Chip: $soc, board ${Build.BOARD}, ${Build.SUPPORTED_ABIS.firstOrNull()}")
        appendLine("Access: " + RootShell.mode.name.lowercase())
    }

    /** The full report: device data, then the diagnostics and game mode logs. */
    fun report(ctx: Context): String = buildString {
        append(deviceInfo(ctx))
        appendLine()
        appendLine("== Diagnostics ==")
        val f = File(ctx.filesDir, FILE)
        append(if (f.exists()) f.readText() else "(empty)\n")
        val g = File(ctx.filesDir, "game_log.txt")
        if (g.exists()) {
            appendLine()
            appendLine("== Game mode ==")
            append(g.readLines().takeLast(80).joinToString("\n"))
            appendLine()
        }
    }
}
