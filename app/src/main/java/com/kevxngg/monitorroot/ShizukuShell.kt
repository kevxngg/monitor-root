package com.kevxngg.monitorroot

import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Runs shell commands through Shizuku (ADB-level permissions, no root).
 * Allows reading all processes and sensors, and killing apps, on non-rooted phones.
 * It cannot write to root-owned files (CPU/GPU governors): that still
 * requires real root.
 */
object ShizukuShell {
    private const val REQUEST_CODE = 4210

    /** true if the Shizuku service is alive and the version supports remote execution. */
    fun available(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.isPreV11()
    } catch (e: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    /**
     * Requests the permission. Returns false if the user denied it before: in that case Shizuku
     * no longer shows the prompt and it must be allowed from the Shizuku app itself.
     */
    fun requestPermission(): Boolean = try {
        if (Shizuku.shouldShowRequestPermissionRationale()) false
        else { Shizuku.requestPermission(REQUEST_CODE); true }
    } catch (e: Throwable) {
        false
    }

    // The method is looked up only once (it used to be looked up via reflection on every command)
    private val newProcess by lazy {
        try {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
            ).apply { isAccessible = true }
        } catch (e: Throwable) {
            null
        }
    }

    private const val TIMEOUT_MS = 20_000L
    private val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "Shizuku-timeout").apply { isDaemon = true }
    }

    /**
     * Runs a command with the "shell" user's shell (uid 2000). null on failure.
     * If it takes more than 20 s (for example, a hung system service) it is cancelled,
     * so it doesn't block the rest of the app.
     */
    fun run(cmd: String): List<String>? {
        if (!available() || !hasPermission()) return null
        val m = newProcess ?: return null
        return try {
            val process = m.invoke(
                null, arrayOf("sh", "-c", "exec 2>/dev/null </dev/null; $cmd"), null, null,
            ) as Process
            val watchdog = timer.schedule({ process.destroy() }, TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            try {
                val out = BufferedReader(InputStreamReader(process.inputStream)).readLines()
                process.waitFor()
                out
            } finally {
                watchdog.cancel(false)
            }
        } catch (e: Throwable) {
            null
        }
    }
}
