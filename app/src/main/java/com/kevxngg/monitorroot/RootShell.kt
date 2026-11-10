package com.kevxngg.monitorroot

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Mantiene abierta una única shell privilegiada:
 *  - ROOT: con "su" (Magisk, KernelSU, APatch)
 *  - HELPER: sin root, con el servicio "monitord" que trae la ROM (ver rom/port/)
 */
object RootShell {
    enum class Mode { NONE, ROOT, SYSTEM, HELPER, SHIZUKU }

    @Volatile var mode = Mode.NONE
        private set

    /** true si la conexión es por Shizuku: se puede leer todo, pero no escribir en archivos de root. */
    val viaShizuku: Boolean get() = mode == Mode.SHIZUKU

    /** Cierra la conexión actual para volver a elegir modo (por ejemplo, al cambiarlo en Configuración). */
    fun reset() {
        synchronized(lock) {
            closeLocked()
            mode = Mode.NONE
        }
    }

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null
    private val lock = Any()

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

    /** Abre la shell: servicio de la ROM (sin root) o su, en ese orden. */
    fun open(): Boolean {
        synchronized(lock) {
            // Ya conectado: salida rápida (se llama a menudo, p. ej. cada segundo desde el flotante)
            if (mode == Mode.HELPER || mode == Mode.SHIZUKU) return true
            if (process != null) return true
            if (helperRun("id")?.any { it.contains("uid=0") } == true) {
                mode = Mode.HELPER
                return true
            }
            val choice = Prefs.accessMode
            val rooted = if (choice == 1) false else try {
                val p = ProcessBuilder("su").start()
                val w = OutputStreamWriter(p.outputStream)
                process = p
                writer = w
                reader = BufferedReader(InputStreamReader(p.inputStream))
                // Descarta stderr para que no se llene su buffer y bloquee la shell
                w.write("exec 2>/dev/null\n")
                w.flush()
                val ok = runLocked("id").any { it.contains("uid=0") }
                if (ok) mode = Mode.ROOT else closeLocked()
                ok
            } catch (e: Exception) {
                closeLocked()
                false
            }
            if (rooted) return true
            // Sin root: usar Shizuku si está disponible y con permiso (acceso de solo lectura + finalizar apps)
            if (choice != 0 && ShizukuShell.available() && ShizukuShell.hasPermission()) {
                mode = Mode.SHIZUKU
                return true
            }
            return false
        }
    }

    /** Ejecuta un comando (o script de varias líneas) y devuelve su salida. */
    fun run(cmd: String): List<String> {
        synchronized(lock) {
            // Dos intentos: si la conexión se perdió (Shizuku reiniciado, su cerrado por
            // tiempo, etc.), se reconecta sola en vez de dejar la app sin datos.
            for (attempt in 0..1) {
                when (mode) {
                    Mode.HELPER -> {
                        helperRun(cmd)?.let { return it }
                        mode = Mode.NONE
                    }
                    Mode.SHIZUKU -> {
                        ShizukuShell.run(cmd)?.let { return it }
                        mode = Mode.NONE
                    }
                    else -> if (process != null) {
                        try {
                            return runLocked(cmd)
                        } catch (e: Exception) {
                            closeLocked()
                        }
                    }
                }
                if (attempt == 0 && !reconnectLocked()) return emptyList()
            }
            return emptyList()
        }
    }

    private var lastReconnect = 0L

    /** Vuelve a abrir la conexión, como mucho una vez cada 5 s (para no saturar al gestor de root). */
    private fun reconnectLocked(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastReconnect < 5000) return false
        lastReconnect = now
        return open()
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
        if (mode == Mode.ROOT) mode = Mode.NONE
    }
}
