package com.kevxngg.monitorroot

import java.util.concurrent.TimeUnit

/** Acciones que se ejecutan con root. */
object Actions {
    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun forceStop(pkg: String) {
        RootShell.run("am force-stop ${q(pkg)}")
    }

    fun kill(pids: List<Int>, signal: String = "9") {
        if (pids.isNotEmpty()) RootShell.run("kill -$signal ${pids.joinToString(" ")}")
    }

    /** Cambia el valor nice de cada proceso al valor absoluto [target]. */
    fun setNice(procs: List<ProcInfo>, target: Int) {
        val cmd = procs.filter { it.nice != target }
            .joinToString("; ") { "renice -n ${target - it.nice} -p ${it.pid}" }
        if (cmd.isNotEmpty()) RootShell.run(cmd)
    }

    fun setEfficiency(pkg: String, procs: List<ProcInfo>, on: Boolean) {
        setNice(procs, if (on) 19 else 0)
        RootShell.run("am set-standby-bucket ${q(pkg)} ${if (on) "restricted" else "active"}")
    }

    /** PIDs del proceso y de todos sus descendientes. */
    fun tree(root: Int, all: List<ProcInfo>): List<Int> {
        val children = all.groupBy { it.ppid }
        val out = ArrayList<Int>()
        val stack = ArrayDeque(listOf(root))
        while (stack.isNotEmpty()) {
            val p = stack.removeLast()
            if (p in out) continue
            out.add(p)
            children[p]?.forEach { stack.add(it.pid) }
        }
        return out
    }

    fun getAffinity(pid: Int): Long? =
        RootShell.run("taskset -p $pid").joinToString(" ").trim().split(Regex("\\s+")).lastOrNull()
            ?.toLongOrNull(16)

    fun setAffinity(pid: Int, mask: Long) {
        RootShell.run("taskset -ap ${java.lang.Long.toHexString(mask)} $pid")
    }

    /** Ejecuta un comando y devuelve su salida (máximo 15 s). */
    fun runCommand(cmd: String, asRoot: Boolean): String {
        val wrapped = "timeout 15 sh -c ${q(cmd)} 2>&1"
        val out = if (asRoot) {
            RootShell.run(wrapped).joinToString("\n")
        } else {
            try {
                val p = ProcessBuilder("sh", "-c", wrapped).redirectErrorStream(true).start()
                val text = p.inputStream.bufferedReader().readText()
                p.waitFor(16, TimeUnit.SECONDS)
                text
            } catch (e: Exception) {
                e.message ?: "Error"
            }
        }
        return out.take(8000).ifBlank { "(sin salida)" }
    }

    fun properties(pid: Int): List<Pair<String, String>> {
        val lines = RootShell.run(
            "echo \"exe=$(readlink /proc/$pid/exe)\"; " +
                "echo \"cwd=$(readlink /proc/$pid/cwd)\"; " +
                "echo \"cmd=$(tr '\\0' ' ' < /proc/$pid/cmdline)\"; " +
                "echo \"oom=$(cat /proc/$pid/oom_score_adj)\"; " +
                "grep -E '^(Uid|VmPeak|VmSwap|Cpus_allowed_list|voluntary_ctxt_switches|nonvoluntary_ctxt_switches|SigBlk)' /proc/$pid/status"
        )
        val labels = mapOf(
            "exe" to "Ejecutable", "cwd" to "Carpeta de trabajo", "cmd" to "Línea de comandos",
            "oom" to "Prioridad OOM", "Uid" to "UID", "VmPeak" to "Memoria virtual máxima",
            "VmSwap" to "Memoria en swap", "Cpus_allowed_list" to "Núcleos permitidos",
            "voluntary_ctxt_switches" to "Cambios de contexto voluntarios",
            "nonvoluntary_ctxt_switches" to "Cambios de contexto forzados", "SigBlk" to "Señales bloqueadas",
        )
        return lines.mapNotNull { l ->
            val i = l.indexOfFirst { it == '=' || it == ':' }
            if (i <= 0) null else {
                val k = l.substring(0, i)
                (labels[k] ?: k) to l.substring(i + 1).trim().replace('\t', ' ')
            }
        }
    }
}
