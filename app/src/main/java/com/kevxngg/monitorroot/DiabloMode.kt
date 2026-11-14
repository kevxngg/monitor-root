package com.kevxngg.monitorroot

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Modo Diablo (solo con root, durante el modo juego):
 *  - CPU: todos los clústeres fijos en su frecuencia máxima.
 *  - GPU: fija en su frecuencia máxima.
 *  - Detiene los servicios térmicos del fabricante (thermal-engine, mi_thermald…),
 *    que son los que bajan la frecuencia con el calor.
 *
 * Seguridad (no se puede desactivar):
 *  - No toca la protección térmica crítica del kernel (thermal_zone), que apaga el
 *    teléfono si llega a una temperatura peligrosa.
 *  - Corte automático si la batería llega a [BATT_LIMIT] o la CPU a [CPU_LIMIT].
 *  - No se activa mientras el teléfono carga.
 *  - Si la app se cierra de golpe, los servicios se reactivan al volver a abrirla
 *    (y siempre al reiniciar el teléfono, porque nada de esto es permanente).
 */
object DiabloMode {
    const val BATT_LIMIT = 46f
    const val CPU_LIMIT = 95f

    private const val PREFS = "diablo"
    private val SVC = Regex("""\[init\.svc\.([^\]]+)\]: \[running\]""")

    @Volatile var active = false
        private set

    // Servicio de Xiaomi/HyperOS que ajusta la frecuencia de los juegos por su cuenta
    private const val JOYOSE = "com.xiaomi.joyose"
    private var lastEnforce = 0L

    // Límites pasivos del kernel que se quitaron (para restaurarlos al salir)
    private var zonesOff: List<String> = emptyList()          // zonas térmicas puestas en "disabled"
    private var mtkBefore: List<Pair<String, String>> = emptyList()  // archivo de MediaTek → valor original

    private var cpuBefore: CpuPower.Snapshot? = null
    private var gpuBefore: GpuControl.State? = null
    private var stopped: List<String> = emptyList()

    /** Servicios térmicos del fabricante en ejecución (nunca el HAL térmico de Android). */
    private fun thermalServices(): List<String> =
        RootShell.run("getprop").mapNotNull { SVC.find(it)?.groupValues?.get(1) }
            .filter { n ->
                val l = n.lowercase()
                l.contains("therm") && !l.contains("hal") && !l.startsWith("android.hardware")
            }

    fun isCharging(ctx: Context): Boolean {
        val i = ctx.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
    }

    /** true si hay que cortar por temperatura. */
    fun overheated(cpuTemp: Float?, battTemp: Float?): Boolean =
        (battTemp ?: 0f) >= BATT_LIMIT || (cpuTemp ?: 0f) >= CPU_LIMIT

    /** Activa el modo. Devuelve false si no se puede (sin root, Shizuku o cargando). */
    @Synchronized
    fun enable(ctx: Context): Boolean {
        if (active) return true
        if (!RootShell.open() || RootShell.viaShizuku || isCharging(ctx)) return false
        // 1) Guardar el estado actual para devolverlo exactamente igual
        cpuBefore = CpuPower.snapshot()
        gpuBefore = GpuControl.read()
        // 2) Detener los servicios térmicos del fabricante (se recuerda cuáles para reiniciarlos)
        stopped = thermalServices()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("active", true).putString("services", stopped.joinToString(",")).apply()
        stopped.forEach { RootShell.run("stop ${Actions.q(it)}") }
        pauseJoyose()
        val (zones, mtk) = liftPassiveLimits()
        zonesOff = zones; mtkBefore = mtk
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("zones", zones.joinToString(","))
            .putString("mtk", mtk.joinToString(";") { "${it.first}=${it.second}" })
            .apply()
        // 3) CPU y GPU al máximo
        CpuPower.lockMax(cpuBefore?.clusters.orEmpty())
        gpuBefore?.let { g ->
            if (g.fixed) {
                g.freqsKhz.maxOrNull()?.let { GpuControl.setFreq(g, it) }
            } else {
                if ("performance" in g.governors) GpuControl.setGovernor(g, "performance")
                GpuControl.setFreq(g, null)
            }
        }
        active = true
        return true
    }

    /**
     * Quita los límites térmicos PASIVOS del kernel (los que bajan la frecuencia con poco calor).
     * Solo se desactivan las zonas que NO tienen un punto "critical" ni "hot": las que protegen
     * el hardware de verdad (apagado de emergencia) siguen activas.
     * En MediaTek también se apagan FPSGO, EARA térmico y la política térmica de PPM.
     */
    private fun liftPassiveLimits(): Pair<List<String>, List<Pair<String, String>>> {
        val zones = RootShell.run(
            "for z in /sys/class/thermal/thermal_zone*; do " +
                "echo \"${'$'}z|${'$'}(cat ${'$'}z/mode 2>/dev/null)|${'$'}(cat ${'$'}z/trip_point_*_type 2>/dev/null | tr '\\n' ' ')\"; done"
        ).mapNotNull { l ->
            val p = l.split('|')
            if (p.size < 3) return@mapNotNull null
            val types = p[2].trim().split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.lowercase() }
            // Solo zonas activas, con algún punto de corte, y sin protección crítica
            if (p[1].trim() == "enabled" && types.isNotEmpty() && types.none { it == "critical" || it == "hot" }) p[0] else null
        }
        zones.forEach { RootShell.run("echo disabled > ${Actions.q("$it/mode")}") }

        val mtk = ArrayList<Pair<String, String>>()
        fun setIfExists(file: String, value: String) {
            val cur = RootShell.run("cat ${Actions.q(file)} 2>/dev/null").firstOrNull()?.trim() ?: return
            if (cur.isEmpty()) return
            mtk.add(file to cur)
            RootShell.run("echo $value > ${Actions.q(file)}")
        }
        setIfExists("/sys/kernel/fpsgo/common/fpsgo_enable", "0")
        setIfExists("/sys/kernel/eara_thermal/enable", "0")
        // PPM: desactivar solo la política térmica (las demás se dejan)
        val ppm = RootShell.run("cat /proc/ppm/policy_status 2>/dev/null")
        ppm.firstOrNull { it.contains("THERMAL", ignoreCase = true) && it.contains("enabled", ignoreCase = true) }
            ?.let { Regex("""\[(\d+)]""").find(it)?.groupValues?.get(1) }
            ?.let { idx ->
                mtk.add("/proc/ppm/policy_status" to "$idx 1")
                RootShell.run("echo '$idx 0' > /proc/ppm/policy_status")
            }
        return zones to mtk
    }

    private fun restorePassiveLimits(zones: List<String>, mtk: List<Pair<String, String>>) {
        zones.forEach { RootShell.run("echo enabled > ${Actions.q("$it/mode")}") }
        mtk.forEach { (file, value) -> RootShell.run("echo ${Actions.q(value)} > ${Actions.q(file)}") }
    }

    /** Pausa Joyose (Xiaomi/HyperOS) si está instalado y en marcha. Vuelve a iniciarse solo después. */
    private fun pauseJoyose() {
        if (RootShell.run("pidof $JOYOSE").any { it.isNotBlank() }) RootShell.run("am force-stop $JOYOSE")
    }

    /**
     * Mantiene el modo mientras dura (se llama cada pocos segundos): si otra app o el
     * sistema cambió las frecuencias, reactivó un servicio térmico o Joyose, se corrige.
     */
    @Synchronized
    fun enforce() {
        if (!active) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastEnforce < 4000) return
        lastEnforce = now
        val clusters = CpuPower.read()
        val drifted = clusters.any { c ->
            val top = c.hwMaxKhz ?: c.freqsKhz.maxOrNull() ?: return@any false
            (c.minKhz ?: 0L) < top || ("performance" in c.governors && c.governor != "performance")
        }
        if (drifted) CpuPower.lockMax(clusters)
        thermalServices().filter { it in stopped }.forEach { RootShell.run("stop ${Actions.q(it)}") }
        pauseJoyose()
        zonesOff.forEach { RootShell.run("echo disabled > ${Actions.q("$it/mode")}") }
        mtkBefore.forEach { (file, _) ->
            when {
                file.endsWith("fpsgo_enable") || file.endsWith("eara_thermal/enable") -> RootShell.run("echo 0 > ${Actions.q(file)}")
            }
        }
    }

    /** Devuelve todo a como estaba antes de activarlo. */
    @Synchronized
    fun disable(ctx: Context) {
        if (!active) return
        active = false
        if (RootShell.open()) {
            restorePassiveLimits(zonesOff, mtkBefore)
            stopped.forEach { RootShell.run("start ${Actions.q(it)}") }
            cpuBefore?.let { CpuPower.restore(it) }
            gpuBefore?.let { g ->
                GpuControl.read()?.let { now ->
                    g.governor?.takeIf { it in now.governors }?.let { GpuControl.setGovernor(now, it) }
                    GpuControl.setFreq(now, if (g.fixed) g.currentKhz else g.currentKhz.takeIf { it != g.freqsKhz.maxOrNull() })
                }
            }
        }
        stopped = emptyList(); cpuBefore = null; gpuBefore = null; zonesOff = emptyList(); mtkBefore = emptyList()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("active", false).remove("services").remove("zones").remove("mtk").apply()
    }

    /**
     * Si la app se cerró de golpe con el modo activo, reactiva los servicios térmicos
     * y devuelve la CPU y la GPU a su estado normal.
     */
    fun recoverIfNeeded(ctx: Context) {
        if (active) return
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!sp.getBoolean("active", false)) return
        if (!RootShell.open()) return
        val zones = sp.getString("zones", "").orEmpty().split(',').filter { it.isNotBlank() }
        val mtk = sp.getString("mtk", "").orEmpty().split(';').filter { it.contains('=') }
            .map { it.substringBefore('=') to it.substringAfter('=') }
        restorePassiveLimits(zones, mtk)
        sp.getString("services", "").orEmpty().split(',').filter { it.isNotBlank() }
            .forEach { RootShell.run("start ${Actions.q(it)}") }
        CpuPower.apply(CpuPower.Mode.BALANCED, CpuPower.read())
        GpuControl.read()?.let { GpuControl.reset(it) }
        sp.edit().putBoolean("active", false).remove("services").remove("zones").remove("mtk").apply()
    }
}
