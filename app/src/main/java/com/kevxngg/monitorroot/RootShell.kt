package com.kevxngg.monitorroot

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID

/**
 * Mantiene abierta una única shell privilegiada y ejecuta comandos en ella:
 *  - ROOT: con "su" (Magisk, KernelSU, APatch o root temporal)
 *  - SYSTEM: sin root, cuando la app viene integrada en una ROM como app del
 *    sistema (uid 1000, firmada con la clave de la plataforma)
 */
object RootShell {
    enum class Mode { NONE, ROOT, SYSTEM }

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null
    private val lock = Any()

    @Volatile var mode = Mode.NONE
        private set

    /** true si la app corre como app del sistema (integrada en la ROM). */
    val isSystemApp: Boolean get() = android.os.Process.myUid() == android.os.Process.SYSTEM_UID

    private fun start(cmd: String, needRoot: Boolean): Boolean {
        return try {
            val p = ProcessBuilder(cmd).start()
            val w = OutputStreamWriter(p.outputStream)
            process = p
            writer = w
            reader = BufferedReader(InputStreamReader(p.inputStream))
            // Descarta stderr para que no se llene su buffer y bloquee la shell
            w.write("exec 2>/dev/null\n")
            w.flush()
            val id = runLocked("id")
            val ok = if (needRoot) id.any { it.contains("uid=0") } else id.any { it.contains("uid=") }
            if (!ok) closeLocked()
            ok
        } catch (e: Exception) {
            closeLocked()
            false
        }
    }

    /** Abre la shell. Primero con root; si no hay, como app del sistema. */
    fun open(): Boolean {
        synchronized(lock) {
            if (process != null) return true
            // En una ROM se usa directamente la shell del sistema (no hace falta root)
            if (isSystemApp && start("sh", needRoot = false)) {
                mode = Mode.SYSTEM
                return true
            }
            if (start("su", needRoot = true)) {
                mode = Mode.ROOT
                return true
            }
            mode = Mode.NONE
            return false
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
