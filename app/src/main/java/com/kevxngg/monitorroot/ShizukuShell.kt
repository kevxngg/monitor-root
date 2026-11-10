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

    /** Ejecuta un comando con la shell del usuario "shell" (uid 2000). null si falla. */
    fun run(cmd: String): List<String>? {
        if (!available() || !hasPermission()) return null
        return try {
            // newProcess es oculto; se llama por reflexión, como recomienda la API de Shizuku
            @Suppress("PrivateApi")
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
            ).apply { isAccessible = true }
            val process = method.invoke(
                null, arrayOf("sh", "-c", "exec 2>/dev/null; $cmd"), null, null,
            ) as Process
            val out = BufferedReader(InputStreamReader(process.inputStream)).readLines()
            process.waitFor()
            out
        } catch (e: Throwable) {
            null
        }
    }
}
