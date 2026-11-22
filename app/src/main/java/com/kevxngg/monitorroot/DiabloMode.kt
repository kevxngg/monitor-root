package com.kevxngg.monitorroot

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Diablo Mode (root only, during game mode):
 *  - CPU: all clusters pinned at their maximum frequency.
 *  - GPU: pinned at its maximum frequency.
 *  - Stops the vendor thermal services (thermal-engine, mi_thermald…),
 *    which are the ones that lower the frequency when hot.
 *
 * Safety (cannot be disabled):
 *  - Does not touch the kernel's critical thermal protection (thermal_zone), which shuts down the
 *    phone if it reaches a dangerous temperature.
 *  - Automatic cutoff if the battery reaches [BATT_LIMIT] or the CPU reaches [CPU_LIMIT].
 *  - It also works while charging (the temperature cutoff is what protects the battery).
 *  - If the app is killed abruptly, the services are re-enabled when it is reopened
 *    (and always on phone reboot, since none of this is permanent).
 */
object DiabloMode {
    const val BATT_LIMIT = 46f
    const val CPU_LIMIT = 95f

    private const val PREFS = "diablo"
    private val SVC = Regex("""\[init\.svc\.([^\]]+)\]: \[running\]""")

    @Volatile var active = false
        private set

    // Xiaomi/HyperOS service that adjusts game frequency on its own
    private const val JOYOSE = "com.xiaomi.joyose"
    private var lastEnforce = 0L

    // Passive kernel limits that were removed (to restore them on exit)
    private var zonesOff: List<String> = emptyList()          // thermal zones set to "disabled"
    private var mtkBefore: List<Pair<String, String>> = emptyList()  // file → original value (MediaTek limits, cpusets)
    private var littleCpus: String? = null   // CPUs left to the other foreground apps (the little cluster)

    private var cpuBefore: CpuPower.Snapshot? = null
    private var gpuBefore: GpuControl.State? = null
    private var stopped: List<String> = emptyList()

    /** Vendor thermal services currently running (never Android's thermal HAL). */
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

    /** true if it must cut off due to temperature. */
    fun overheated(cpuTemp: Float?, battTemp: Float?): Boolean =
        (battTemp ?: 0f) >= BATT_LIMIT || (cpuTemp ?: 0f) >= CPU_LIMIT

    /** Activates the mode. Returns false if it can't (no root or Shizuku). */
    @Synchronized
    fun enable(ctx: Context): Boolean {
        if (active) return true
        if (!RootShell.open() || RootShell.viaShizuku) return false
        // 1) Save the current state to restore it exactly
        val before = CpuPower.snapshot()
        // Without the CPU state there would be nothing to restore later: it is not activated
        if (before.clusters.isEmpty()) return false
        cpuBefore = before
        gpuBefore = GpuControl.read()
        // 2) Stop the vendor thermal services (remember which ones, to restart them)
        stopped = thermalServices()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("active", true).putString("services", stopped.joinToString(",")).apply()
        stopped.forEach { RootShell.run("stop ${Actions.q(it)}") }
        pauseJoyose()
        val (zones, limits) = liftPassiveLimits()
        val mtk = limits + reserveBigCores(before.clusters)
        zonesOff = zones; mtkBefore = mtk
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("zones", zones.joinToString(","))
            .putString("mtk", mtk.joinToString(";") { "${it.first}=${it.second}" })
            .apply()
        // 3) CPU and GPU at maximum
        liftVendorCpuCap(before.clusters)
        CpuPower.lockMax(before.clusters)
        gpuBefore?.let { pinGpu(it) }
        active = true
        return true
    }

    /**
     * Leaves the big cores to the game: the other apps and services that run in the foreground
     * (another app's service, system_server, audio…) are limited to the little cluster, while the
     * game (top-app) keeps every core. On a Helio G99 the game's threads saturate its only two big
     * cores, and sharing them with those processes made the frame times jump.
     * Returns the cpuset files changed with their original value, to restore them.
     */
    private fun reserveBigCores(clusters: List<CpuPower.Cluster>): List<Pair<String, String>> {
        if (clusters.size < 2) return emptyList()
        val little = clusters.minByOrNull { it.hwMaxKhz ?: Long.MAX_VALUE }?.cpus?.replace('–', '-')
            ?.takeIf { Regex("""\d+(-\d+)?""").matches(it) } ?: return emptyList()
        littleCpus = little
        val out = ArrayList<Pair<String, String>>()
        for (set in listOf("foreground", "foreground_window")) {
            val file = "/dev/cpuset/$set/cpus"
            val cur = RootShell.run("cat $file 2>/dev/null").firstOrNull()?.trim().orEmpty()
            if (cur.isEmpty()) continue
            out.add(file to cur)
            RootShell.run("echo $little > $file")
        }
        return out
    }

    /**
     * Xiaomi/HyperOS: its thermal service leaves a CPU frequency cap registered in the kernel
     * (thermal_message/cpu_limits) that stays there even after the service is stopped, so the
     * clusters could not go above it. The cap is raised to each cluster's real maximum.
     * When the mode ends the service is started again and sets its own limits.
     */
    private fun liftVendorCpuCap(clusters: List<CpuPower.Cluster>) {
        val file = "/sys/class/thermal/thermal_message/cpu_limits"
        if (RootShell.run("[ -e $file ] && echo yes").none { it.contains("yes") }) return
        clusters.forEach { c ->
            val cpu = c.path.substringAfterLast("policy").toIntOrNull() ?: return@forEach
            val top = c.hwMaxKhz ?: c.freqsKhz.maxOrNull() ?: return@forEach
            RootShell.run("echo ${Actions.q("cpu$cpu $top")} > $file")
        }
    }

    /** GPU at its maximum: pinned on the chips that allow it, otherwise performance governor without a cap. */
    private fun pinGpu(g: GpuControl.State) {
        val top = g.freqsKhz.maxOrNull()
        if (g.fixed) {
            if (top != null && g.currentKhz != top) GpuControl.setFreq(g, top)
        } else {
            if ("performance" in g.governors && g.governor != "performance") GpuControl.setGovernor(g, "performance")
            if (top != null && g.currentKhz != null && g.currentKhz < top) GpuControl.setFreq(g, null)
            // Without a cap it could still scale down with load: the minimum is raised to the maximum
            if (top != null && g.minKhz != top) GpuControl.pinMin(g, true)
        }
    }

    /**
     * Removes the kernel's PASSIVE thermal limits (the ones that lower frequency at low heat).
     * Only zones that have NO "critical" or "hot" trip point are disabled: the ones that really protect
     * the hardware (emergency shutdown) stay active.
     * On MediaTek, thermal EARA and the PPM thermal policy are also turned off. FPSGO is left on:
     * it is MediaTek's frame pacing (it moves the game's render thread to the right cores), and
     * turning it off made the frame rate lower and less even.
     */
    private fun liftPassiveLimits(): Pair<List<String>, List<Pair<String, String>>> {
        val zones = RootShell.run(
            "for z in /sys/class/thermal/thermal_zone*; do " +
                "echo \"${'$'}z|${'$'}(cat ${'$'}z/mode 2>/dev/null)|${'$'}(cat ${'$'}z/trip_point_*_type 2>/dev/null | tr '\\n' ' ')\"; done"
        ).mapNotNull { l ->
            val p = l.split('|')
            if (p.size < 3) return@mapNotNull null
            val types = p[2].trim().split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.lowercase() }
            // Only enabled zones, with some trip point, and without critical protection
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
        setIfExists("/sys/kernel/eara_thermal/enable", "0")
        // PPM: disable only the thermal policy (the others are left alone)
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

    /** Pauses Joyose (Xiaomi/HyperOS) if installed and running. It restarts on its own afterwards. */
    private fun pauseJoyose() {
        if (RootShell.run("pidof $JOYOSE").any { it.isNotBlank() }) RootShell.run("am force-stop $JOYOSE")
    }

    /**
     * Maintains the mode while it lasts (called every few seconds): if another app or the
     * system changed the frequencies or re-enabled a thermal service or Joyose, it is corrected.
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
            (c.minKhz ?: 0L) < top || (c.maxKhz ?: top) < top ||
                ("performance" in c.governors && c.governor != "performance")
        }
        if (drifted) {
            liftVendorCpuCap(clusters)
            CpuPower.lockMax(clusters)
        }
        // The GPU too: the vendor's power service can undo the pinned frequency
        GpuControl.read()?.let { pinGpu(it) }
        thermalServices().filter { it in stopped }.forEach { RootShell.run("stop ${Actions.q(it)}") }
        pauseJoyose()
        zonesOff.forEach { RootShell.run("echo disabled > ${Actions.q("$it/mode")}") }
        mtkBefore.forEach { (file, _) ->
            when {
                file.endsWith("eara_thermal/enable") -> RootShell.run("echo 0 > ${Actions.q(file)}")
                file.startsWith("/dev/cpuset/") -> littleCpus?.let { RootShell.run("echo $it > ${Actions.q(file)}") }
            }
        }
    }

    /** Returns everything to how it was before activation. */
    @Synchronized
    fun disable(ctx: Context) {
        if (!active) return
        active = false
        val root = RootShell.open()
        if (root) {
            restorePassiveLimits(zonesOff, mtkBefore)
            stopped.forEach { RootShell.run("start ${Actions.q(it)}") }
            cpuBefore?.let { CpuPower.restore(it) }
            gpuBefore?.let { g ->
                GpuControl.read()?.let { now ->
                    if (!now.fixed) GpuControl.pinMin(now, false)
                    g.governor?.takeIf { it in now.governors }?.let { GpuControl.setGovernor(now, it) }
                    GpuControl.setFreq(now, if (g.fixed) g.currentKhz else g.currentKhz.takeIf { it != g.freqsKhz.maxOrNull() })
                }
            }
        }
        stopped = emptyList(); cpuBefore = null; gpuBefore = null; zonesOff = emptyList(); mtkBefore = emptyList(); littleCpus = null
        // Without root nothing could be restored: the saved state is kept so recoverIfNeeded() does it later
        if (!root) return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("active", false).remove("services").remove("zones").remove("mtk").apply()
    }

    /**
     * If the app was killed abruptly with the mode active, re-enables the thermal services
     * and returns the CPU and GPU to their normal state.
     */
    @Synchronized
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
