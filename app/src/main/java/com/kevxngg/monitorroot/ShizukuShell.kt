package com.kevxngg.monitorroot

import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Ejecuta comandos de shell a través de Shizuku (permisos de nivel ADB, sin root).
 * Permite leer todos los procesos y sensores, y finalizar apps, en teléfonos sin root.
 * No puede escribir en archivos propiedad de root (gobernadores de CPU/GPU): eso sigue
 * necesitando root de verdad.
 */
object ShizukuShell {
    private const val REQUEST_CODE = 4210

    /** true si el servicio Shizuku está vivo y la versión soporta ejecución remota. */
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

    fun requestPermission() {
        try {
            if (!Shizuku.shouldShowRequestPermissionRationale()) Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Throwable) {
        }
    }

    // El método se busca una sola vez (antes se buscaba por reflexión en cada comando)
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
     * Ejecuta un comando con la shell del usuario "shell" (uid 2000). null si falla.
     * Si tarda más de 20 s (por ejemplo, un servicio del sistema colgado) se cancela,
     * para no bloquear el resto de la app.
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
