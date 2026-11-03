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
 *  - HELPER: sin root, con el servicio "monitord" que trae la ROM (ver rom/port/)
 */
object RootShell {
    enum class Mode { NONE, ROOT, SYSTEM, HELPER }

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

    /** Envía un script al servicio monitord y devuelve su salida (null si no está). */
    private fun helperRun(script: String): List<String>? = try {
        android.net.LocalSocket().use { sock ->
            sock.connect(
                android.net.LocalSocketAddress(
                    com.kevxngg.monitorroot.helper.HelperMain.SOCKET,
                    android.net.LocalSocketAddress.Namespace.ABSTRACT,
                )
            )
            sock.soTimeout = 30_000
            sock.outputStream.write(script.toByteArray(Charsets.UTF_8))
            sock.outputStream.flush()
            sock.shutdownOutput()
            sock.inputStream.bufferedReader(Charsets.UTF_8).readLines()
        }
    } catch (e: Exception) {
        null
    }

    /** Abre la shell: servicio de la ROM, app del sistema o root, en ese orden. */
    fun open(): Boolean {
        synchronized(lock) {
            if (mode == Mode.HELPER || process != null) return true
            // En custom ROMs y ports con el servicio monitord: sin root
            if (helperRun("id")?.any { it.contains("uid=0") } == true) {
                mode = Mode.HELPER
                return true
            }
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
            if (mode == Mode.HELPER) {
                helperRun(cmd)?.let { return it }
                // El servicio se reinició o se detuvo: se vuelve a elegir el modo
                mode = Mode.NONE
            }
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
