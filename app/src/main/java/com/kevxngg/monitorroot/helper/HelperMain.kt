package com.kevxngg.monitorroot.helper

import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.File

/**
 * Monitor's privileged service for custom ROMs and ports (no root).
 *
 * Started by init at boot (see rom/port/) with app_process, inside its own
 * SELinux domain. Listens on the abstract socket "monitord" and runs the
 * commands Monitor sends it. Before running anything it checks, using the
 * credentials provided by the kernel, that the caller is Monitor.
 */
object HelperMain {
    const val SOCKET = "monitord"
    private const val PKG = "com.kevxngg.monitorroot"

    @JvmStatic
    fun main(args: Array<String>) {
        val server = LocalServerSocket(SOCKET)
        println("monitord: escuchando en @$SOCKET")
        while (true) {
            val client = try {
                server.accept()
            } catch (e: Exception) {
                continue
            }
            // A client that disconnects halfway must not bring the whole service down
            Thread {
                try {
                    handle(client)
                } catch (_: Throwable) {
                }
            }.start()
        }
    }

    /** Monitor's appId according to /data/system/packages.list (re-read in case it is reinstalled). */
    private fun monitorAppId(): Int? = try {
        File("/data/system/packages.list").useLines { lines ->
            lines.firstOrNull { it.startsWith("$PKG ") }?.split(' ')?.getOrNull(1)?.toIntOrNull()
        }
    } catch (e: Exception) {
        null
    }

    private fun handle(socket: LocalSocket) {
        socket.use { s ->
            val uid = s.peerCredentials.uid
            val appId = monitorAppId()
            // uid = user * 100000 + appId (also valid for work profiles)
            val allowed = uid == 0 || (appId != null && uid % 100000 == appId)
            if (!allowed) return
            val script = s.inputStream.readBytes().toString(Charsets.UTF_8)
            val p = ProcessBuilder("/system/bin/sh", "-c", "exec 2>/dev/null; $script").start()
            try {
                p.inputStream.copyTo(s.outputStream)
                s.outputStream.flush()
                p.waitFor()
            } finally {
                p.destroy()
            }
        }
    }
}
