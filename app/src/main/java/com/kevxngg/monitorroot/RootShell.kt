package com.kevxngg.monitorroot

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Mantiene abierta una única shell "su" y ejecuta comandos en ella. */
object RootShell {
    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null
    private val lock = Any()

    /** Abre la shell root. Devuelve true si se obtuvo uid=0. */
    fun open(): Boolean {
        synchronized(lock) {
            if (process != null) return true
            return try {
                val p = ProcessBuilder("su").start()
                val w = OutputStreamWriter(p.outputStream)
                process = p
                writer = w
                reader = BufferedReader(InputStreamReader(p.inputStream))
                // Descarta stderr para que no se llene su buffer y bloquee la shell
                w.write("exec 2>/dev/null\n")
                w.flush()
                val ok = runLocked("id").any { it.contains("uid=0") }
                if (!ok) closeLocked()
                ok
            } catch (e: Exception) {
                closeLocked()
                false
            }
        }
    }

    /** Ejecuta un comando (o script de varias líneas) y devuelve su salida. */
    fun run(cmd: String): List<String> {
        synchronized(lock) {
            if (process == null) return emptyList()
            return try {
                runLocked(cmd)
            } catch (e: Exception) {
                closeLocked()
                emptyList()
            }
        }
    }

    fun close() {
        synchronized(lock) { closeLocked() }
    }

    /**
     * Ejecuta el comando y lee su salida hasta la marca de fin.
     *  - La entrada del comando es /dev/null: si un comando intenta leer, no se
     *    "come" las líneas siguientes de la shell.
     *  - Si un comando tarda más de [TIMEOUT_MS] (por ejemplo, un servicio del
     *    sistema colgado), se cierra la shell para no bloquear toda la app; la
     *    siguiente llamada abre una nueva.
     */
    private fun runLocked(cmd: String): List<String> {
        val w = writer ?: return emptyList()
        val r = reader ?: return emptyList()
        val p = process ?: return emptyList()
        val marker = "__FIN_${UUID.randomUUID()}__"
        w.write("{\n$cmd\n} </dev/null\necho $marker\n")
        w.flush()
        val watchdog = timer.schedule({ p.destroy() }, TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val lines = ArrayList<String>()
        try {
            while (true) {
                val line = r.readLine() ?: break
                if (line.endsWith(marker)) {
                    val rest = line.removeSuffix(marker)
                    if (rest.isNotEmpty()) lines.add(rest)
                    return lines
                }
                lines.add(line)
            }
        } finally {
            watchdog.cancel(false)
        }
        // Sin marca de fin: la shell terminó o se cerró por tiempo
        closeLocked()
        return lines
    }

    private const val TIMEOUT_MS = 20_000L
    private val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "RootShell-timeout").apply { isDaemon = true }
    }

    private fun closeLocked() {
        try {
            writer?.write("exit\n")
            writer?.flush()
        } catch (_: Exception) {
        }
        process?.destroy()
        process = null
        writer = null
        reader = null
    }
}
