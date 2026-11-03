package com.kevxngg.monitorroot.helper

import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.File

/**
 * Servicio privilegiado de Monitor para custom ROMs y ports (sin root).
 *
 * Lo inicia init al arrancar (ver rom/port/) con app_process, dentro de su propio
 * dominio de SELinux. Escucha en el socket abstracto "monitord" y ejecuta los
 * comandos que le manda Monitor. Antes de ejecutar nada comprueba, con las
 * credenciales que da el kernel, que quien se conecta es Monitor.
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
            Thread { handle(client) }.start()
        }
    }

    /** appId de Monitor según /data/system/packages.list (se relee por si se reinstala). */
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
            // uid = usuario * 100000 + appId (vale también para perfiles de trabajo)
            val allowed = uid == 0 || (appId != null && uid % 100000 == appId)
            if (!allowed) return
            val script = s.inputStream.readBytes().toString(Charsets.UTF_8)
            val p = ProcessBuilder("/system/bin/sh", "-c", "exec 2>/dev/null; $script").start()
            p.inputStream.copyTo(s.outputStream)
            s.outputStream.flush()
            p.waitFor()
        }
    }
}
