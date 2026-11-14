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
    }

    /** Devuelve todo a como estaba antes de activarlo. */
    @Synchronized
    fun disable(ctx: Context) {
        if (!active) return
        active = false
        if (RootShell.open()) {
            stopped.forEach { RootShell.run("start ${Actions.q(it)}") }
            cpuBefore?.let { CpuPower.restore(it) }
            gpuBefore?.let { g ->
                GpuControl.read()?.let { now ->
                    g.governor?.takeIf { it in now.governors }?.let { GpuControl.setGovernor(now, it) }
                    GpuControl.setFreq(now, if (g.fixed) g.currentKhz else g.currentKhz.takeIf { it != g.freqsKhz.maxOrNull() })
                }
            }
        }
        stopped = emptyList(); cpuBefore = null; gpuBefore = null
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("active", false).remove("services").apply()
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
        sp.getString("services", "").orEmpty().split(',').filter { it.isNotBlank() }
            .forEach { RootShell.run("start ${Actions.q(it)}") }
        CpuPower.apply(CpuPower.Mode.BALANCED, CpuPower.read())
        GpuControl.read()?.let { GpuControl.reset(it) }
        sp.edit().putBoolean("active", false).remove("services").apply()
    }
}
