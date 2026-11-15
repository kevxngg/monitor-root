package com.kevxngg.monitorroot

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Keeps a single privileged shell open:
 *  - ROOT: via "su" (Magisk, KernelSU, APatch)
 *  - HELPER: without root, via the "monitord" service shipped with the ROM (see rom/port/)
 */
object RootShell {
    enum class Mode { NONE, ROOT, SYSTEM, HELPER, SHIZUKU }

    @Volatile var mode = Mode.NONE
        private set

    /** true if the connection is via Shizuku: everything can be read, but root files cannot be written. */
    val viaShizuku: Boolean get() = mode == Mode.SHIZUKU

    /** Closes the current connection so the mode is chosen again (e.g. when changed in Settings). */
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

    /** Sends a script to the monitord service and returns its output (null if it is not available). */
    private fun helperRun(script: String): List<String>? = try {
        android.net.LocalSocket().use { sock ->
            sock.connect(
                android.net.LocalSocketAddress(
                    com.kevxngg.monitorroot.helper.HelperMain.SOCKET,
                    android.net.LocalSocketAddress.Namespace.ABSTRACT,
                )
            )
            // Only the real service is trusted (it runs as root); any app could take the socket name
            if (sock.peerCredentials.uid != 0) throw SecurityException("monitord is not root")
            sock.soTimeout = 30_000
            sock.outputStream.write(script.toByteArray(Charsets.UTF_8))
            sock.outputStream.flush()
            sock.shutdownOutput()
            sock.inputStream.bufferedReader(Charsets.UTF_8).readLines()
        }
    } catch (e: Exception) {
        null
    }

    /** Opens the shell: ROM service (no root) or su, in that order. */
    fun open(): Boolean {
        synchronized(lock) {
            // Already connected: fast exit (called often, e.g. every second from the floating monitor)
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
                // Discards stderr so its buffer does not fill up and block the shell
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
            // No root: use Shizuku if available and permitted (read-only access + killing apps)
            if (choice != 0 && ShizukuShell.available() && ShizukuShell.hasPermission()) {
                mode = Mode.SHIZUKU
                return true
            }
            return false
        }
    }

    /** Runs a command (or multi-line script) and returns its output. */
    fun run(cmd: String): List<String> {
        synchronized(lock) {
            // Two attempts: if the connection was lost (Shizuku restarted, su closed by
            // timeout, etc.), it reconnects on its own instead of leaving the app without data.
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

    /** Reopens the connection, at most once every 5 s (to avoid overloading the root manager). */
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
     * Runs the command and reads its output up to the end marker.
     *  - The command's input is /dev/null: if a command tries to read, it does not
     *    "eat" the shell's following lines.
     *  - If a command takes longer than [TIMEOUT_MS] (e.g. a hung system
     *    service), the shell is closed so the whole app is not blocked; the
     *    next call opens a new one.
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
        // No end marker: the shell exited or was closed by timeout
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
