package com.kevxngg.monitorroot

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID

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

    private fun runLocked(cmd: String): List<String> {
        val w = writer ?: return emptyList()
        val r = reader ?: return emptyList()
        val marker = "__FIN_${UUID.randomUUID()}__"
        w.write("$cmd\necho $marker\n")
        w.flush()
        val lines = ArrayList<String>()
        while (true) {
            val line = r.readLine() ?: break
            if (line.endsWith(marker)) {
                val rest = line.removeSuffix(marker)
                if (rest.isNotEmpty()) lines.add(rest)
                break
            }
            lines.add(line)
        }
        return lines
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
